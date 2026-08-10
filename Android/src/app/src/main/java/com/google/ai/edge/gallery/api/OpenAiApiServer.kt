/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.ai.edge.gallery.agent.AgentEvent
import com.google.ai.edge.gallery.agent.AgentExecutionContext
import com.google.ai.edge.gallery.agent.AgentRequest
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.AiChatExecutor
import com.google.ai.edge.gallery.agent.Attachment
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "AGOpenAiApiServer"
private const val DEFAULT_PORT = 8080

/**
 * An OpenAI-compatible HTTP server (backed by NanoHTTPD) that exposes the on-device LLM loaded by
 * the Gallery app over the local network, so that any OpenAI-compatible client (curl, the Python
 * `openai` library, etc.) can call it via [/v1/chat/completions].
 *
 * The server does NOT initialize a model itself. It reuses the model session already initialized
 * in the app UI (the user must first download and initialize a model in the chat screen). Only one
 * model is served at a time — the one set via [setActiveModel].
 *
 * Supports both streaming (SSE) and non-streaming OpenAI chat completions.
 */
@Singleton
open class OpenAiApiServer
@Inject
constructor(
  @ApplicationContext private val context: Context,
  @AiChatExecutor private val executor: AgentRuntimeExecutor,
) : NanoHTTPD(DEFAULT_PORT) {
  @Volatile private var activeModel: Model? = null
  /** Serializes inference: only one request runs at a time; others block and wait in queue. */
  private val inferenceLock = ReentrantLock()

  /** When non-null, requests to /v1 endpoints must present this token via Authorization Bearer. */
  @Volatile private var authToken: String? = null

  /** Diagnostics of the most recent `/v1/chat/completions` request, exposed via `/debug` so I can
   *  troubleshoot tool calling on-device without needing logcat. */
  data class RequestDiagnostics(
    var hasToolsField: Boolean = false,
    var toolCount: Int = 0,
    var toolNames: List<String> = emptyList(),
    var toolDescriptionJson: String = "",
    var automaticToolCalling: Boolean = true,
    var toolCallEvents: Int = 0,
    var streamTokenEvents: Int = 0,
    var error: String? = null,
  )

  @Volatile private var lastDiagnostics = RequestDiagnostics()

  val isRunning: Boolean
    get() = isAlive()

  /** Enables/disables bearer-token auth for the /v1 endpoints. */
  fun setAuthToken(token: String?) {
    authToken = token?.takeIf { it.isNotBlank() }
    Log.d(TAG, "Auth token set: " + if (authToken != null) "enabled" else "disabled")
  }

  /** Sets the model (with its task) that the HTTP server should serve. */
  @Suppress("UNUSED_PARAMETER")
  fun setActiveModel(model: Model, taskId: String = BuiltInTaskId.LLM_CHAT) {
    activeModel = model
    Log.d(TAG, "Active model set to ${model.name} (task=$taskId)")
  }

  /**
   * Starts the HTTP server on the configured port.
   *
   * @return the base URL (e.g. "http://192.168.1.5:8080") if started successfully, or null if the
   *   server could not be started.
   */
  fun startServer(port: Int = DEFAULT_PORT): String? {
    if (isAlive()) {
      Log.w(TAG, "Server already running")
      return baseUrl()
    }
    return try {
      super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
      Log.d(TAG, "OpenAI-compatible server started on port $port")
      baseUrl()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start server", e)
      null
    }
  }

  /** Stops the HTTP server. Safe to call even if it is not running. */
  fun stopServer() {
    if (!isAlive()) return
    try {
      super.stop()
    } catch (e: Exception) {
      Log.w(TAG, "Error stopping server", e)
    }
    Log.d(TAG, "Server stopped")
  }

  /** Best-effort base URL of the running server (first private IPv4 found). */
  private fun baseUrl(): String? {
    val port = getListeningPort()
    val ip = getLocalIpV4() ?: return null
    return "http://$ip:$port"
  }

  private fun getLocalIpV4(): String? {
    try {
      NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
        if (!nif.isLoopback && nif.isUp) {
          val addr = nif.inetAddresses.toList().firstOrNull { it is Inet4Address }
          if (addr != null) return addr.hostAddress
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to enumerate network interfaces", e)
    }
    return null
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // NanoHTTPD routing
  ////////////////////////////////////////////////////////////////////////////////////////////////

  override fun serve(
    session: IHTTPSession,
  ): Response {
    val uri = session.uri
    val method = session.method
    // Handle CORS preflight (browser sends OPTIONS before a cross-origin POST with
    // custom headers like Authorization). Must be answered BEFORE auth so the browser
    // isn't blocked; the actual request is still authenticated.
    if (method == Method.OPTIONS) {
      return corsResponse()
    }
    // Protect /v1/* endpoints with optional bearer token auth.
    if (uri.startsWith("/v1") && !isAuthorized(session)) {
      return jsonError(Response.Status.UNAUTHORIZED, "Unauthorized")
    }
    return when {
      uri == "/health" -> newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
      uri == "/debug" -> handleDebug()
      uri == "/v1/models" -> handleModels()
      uri == "/v1/chat/completions" && method == Method.POST ->
        handleChatCompletions(session)
      uri.startsWith("/v1") ->
        jsonError(Response.Status.NOT_FOUND, "Not found")
      else -> newFixedLengthResponse(
        Response.Status.OK,
        "text/plain",
        listOf("/v1/chat/completions", "/v1/models", "/health").joinToString("\n"),
      )
    }
  }

  /** Empty 200 response with CORS headers, used to answer browser OPTIONS preflight. */
  private fun corsResponse(): Response {
    val res = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
    res.addHeader("Access-Control-Allow-Origin", "*")
    res.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    res.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
    res.addHeader("Access-Control-Max-Age", "86400")
    return res
  }

  /** Returns true if no token is set, or the request carries a matching Authorization Bearer token. */
  private fun isAuthorized(session: IHTTPSession): Boolean {
    val expected = authToken ?: return true
    val header = session.headers["authorization"] ?: session.headers["Authorization"] ?: return false
    val provided = header.removePrefix("Bearer ").removePrefix("bearer ").trim()
    return provided.isNotEmpty() && provided == expected
  }

  private fun handleModels(): Response {
    val model = getActiveOrNull()
    val ids = if (model != null) "\"${escapeJson(model.name)}\"" else ""
    val body = "{\"object\":\"list\",\"data\":[${ids}]}"
    return jsonResponse(Response.Status.OK, body)
  }

  /** Returns diagnostics about the last chat completion request (for tool-calling debugging). */
  private fun handleDebug(): Response {
    val d = lastDiagnostics
    val body =
      "{\"hasToolsField\":${d.hasToolsField},\"toolCount\":${d.toolCount}," +
        "\"toolNames\":${d.toolNames.joinToString(",") { "\"${escapeJson(it)}\"" }.let { "[$it]" }}," +
        "\"automaticToolCalling\":${d.automaticToolCalling}," +
        "\"toolCallEvents\":${d.toolCallEvents},\"streamTokenEvents\":${d.streamTokenEvents}," +
        "\"toolDescriptionJson\":${d.toolDescriptionJson.let { "\"${escapeJson(it)}\"" }}," +
        "\"error\":${d.error?.let { "\"${escapeJson(it)}\"" } ?: "null"}}"
    return jsonResponse(Response.Status.OK, body)
  }

  private fun handleChatCompletions(session: IHTTPSession): Response {
    val model = getActiveOrNull()
    if (model == null) {
      return jsonError(
        Response.Status.BAD_REQUEST,
        "No active model. Please open the chat screen and initialize a model first.",
      )
    }

    val body = readRequestBody(session)
    val request = parseChatRequest(body, model.name)
    if (request == null) {
      lastDiagnostics = RequestDiagnostics(error = "Invalid request body")
      return jsonError(Response.Status.BAD_REQUEST, "Invalid request body")
    }

    // Record diagnostics for the /debug endpoint.
    lastDiagnostics = RequestDiagnostics(
      hasToolsField = request.hasToolsField,
      toolCount = request.tools.size,
      toolNames = request.toolNames,
      toolDescriptionJson = request.toolDescriptionJson,
      automaticToolCalling = request.tools.isEmpty(),
    )
    Log.d(
      TAG,
      "Chat request: tools=${request.tools.size} names=${request.toolNames} " +
        "autoCall=${request.tools.isEmpty()} stream=${request.stream}",
    )

    // The underlying runtime is not concurrent-safe for multiple turns. Serialize inference:
    // if another request is running, block this thread until it finishes (queue) instead of failing.
    inferenceLock.lock()
    return try {
      // Rebuild the conversation from the full request history on every call. This gives the
      // model multi-turn context AND cleanly resets when the client starts a new chat (a new
      // request carries only its own history).
      resetConversationForRequest(model, request)
      if (request.stream) {
        streamResponse(model, request)
      } else {
        runBlockingResponse(model, request)
      }
    } finally {
      inferenceLock.unlock()
    }
  }

  /** Rebuilds the model's conversation from [request.historyMessages] before inference. */
  private fun resetConversationForRequest(model: Model, request: ChatRequest) {
    try {
      val hasTools = request.tools.isNotEmpty()
      model.runtimeHelper.resetConversation(
        model = model,
        supportImage = request.images.isNotEmpty(),
        supportAudio = false,
        systemInstruction = null,
        tools = request.tools,
        // Client-driven tool calling: hand tool calls back to the client instead of executing
        // them server-side. When there are no tools, keep the original auto-execution behaviour.
        automaticToolCalling = !hasTools,
        // Constrained decoding helps the model emit well-formed tool call JSON.
        enableConversationConstrainedDecoding = hasTools,
        initialMessages = request.historyMessages,
      )
    } catch (e: Exception) {
      Log.w(TAG, "Failed to reset conversation for API request", e)
    }
  }

  /** Reads the request body from a NanoHTTPD session (POST JSON payload), decoded as UTF-8. */
  private fun readRequestBody(session: IHTTPSession): String {
    return try {
      // NanoHTTPD keeps the connection alive, so InputStream.readBytes() would block waiting
      // for EOF that never comes. Read exactly Content-Length bytes instead.
      val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
      val input = session.inputStream
      val bytes =
        if (contentLength > 0) {
          val buffer = ByteArray(contentLength.toInt())
          var total = 0
          while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
          }
          buffer.copyOf(total)
        } else {
          // Fallback: read whatever is available (no Content-Length header).
          input.readBytes()
        }
      String(bytes, Charsets.UTF_8)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to read request body", e)
      ""
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // Request parsing
  ////////////////////////////////////////////////////////////////////////////////////////////////

  private data class ChatRequest(
    val prompt: String,
    val historyMessages: List<Message>,
    val images: List<Bitmap>,
    val stream: Boolean,
    val temperature: Double?,
    val maxTokens: Int?,
    val tools: List<ToolProvider>,
    // Diagnostics only (used by /debug).
    val hasToolsField: Boolean = false,
    val toolNames: List<String> = emptyList(),
    val toolDescriptionJson: String = "",
  )

  /**
   * Bridges an OpenAI `function` tool definition into LiteRT-LM's [OpenApiTool] so the model can
   * call it. Used in manual (client-driven) tool calling mode: the model emits a tool call, which
   * the server returns to the client; this [execute] is never invoked locally.
   */
  private class OpenAiFunctionTool(private val descriptionJson: String) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = descriptionJson
    override fun execute(json: String): String = ""
  }

  private fun parseChatRequest(body: String, defaultModel: String): ChatRequest? {
    return try {
      val obj = Json.parseToJsonElement(body).jsonObject
      val messages = obj["messages"]?.jsonArray ?: return null

      // Convert all messages to LiteRT messages (except the last user one, which becomes the prompt).
      val litertMessages = mutableListOf<Message>()
      val images = mutableListOf<Bitmap>()
      var prompt: String? = null
      // Maps OpenAI tool_call_id -> tool name so that subsequent `tool` role messages can be
      // converted to LiteRT Content.ToolResponse (which is keyed by tool name).
      val toolCallIdToName = mutableMapOf<String, String>()

      for ((index, msgEl) in messages.withIndex()) {
        val msg = msgEl.jsonObject
        val role = msg["role"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: continue
        val contentEl = msg["content"]
        val isLast = index == messages.lastIndex

        // content can be a plain string or an array of parts (text / image_url).
        val textParts = mutableListOf<String>()
        if (contentEl is JsonPrimitive) {
          val text = contentEl.takeIf { it.isString }?.content
          if (!text.isNullOrBlank()) textParts.add(text)
        } else if (contentEl is JsonArray) {
          for (part in contentEl) {
            val partObj = part.jsonObject
            val type = partObj["type"]?.jsonPrimitive?.takeIf { it.isString }?.content
            when (type) {
              "text" -> {
                val text = partObj["text"]?.jsonPrimitive?.takeIf { it.isString }?.content
                if (!text.isNullOrBlank()) textParts.add(text)
              }
              "image_url" -> {
                val url =
                  partObj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.takeIf { it.isString }
                    ?.content
                if (url != null) {
                  decodeImageFromDataUrl(url)?.let { images.add(it) }
                }
              }
            }
          }
        }

        val text = textParts.joinToString("\n")
        when (role) {
          "user" -> {
            if (isLast) {
              prompt = text
            } else {
              litertMessages.add(Message.user(text))
            }
          }
          "assistant" -> {
            val toolCallsEl = msg["tool_calls"]
            if (toolCallsEl is JsonArray && toolCallsEl.isNotEmpty()) {
              // Assistant message that requested tool calls. Convert to a LiteRT model message
              // carrying the tool calls so the conversation history stays consistent.
              val litertToolCalls = mutableListOf<ToolCall>()
              for (tc in toolCallsEl) {
                val tcObj = tc.jsonObject
                val id = tcObj["id"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: ""
                val fnObj = tcObj["function"]?.jsonObject
                val name = fnObj?.get("name")?.jsonPrimitive?.takeIf { it.isString }?.content ?: ""
                val argsStr =
                  fnObj?.get("arguments")?.jsonPrimitive?.takeIf { it.isString }?.content ?: "{}"
                val argsMap =
                  runCatching {
                    jsonElementToAny(Json.parseToJsonElement(argsStr)) as Map<String, Any>
                  }.getOrDefault(emptyMap<String, Any>())
                if (name.isNotEmpty()) {
                  litertToolCalls.add(ToolCall(name, argsMap))
                  if (id.isNotEmpty()) toolCallIdToName[id] = name
                }
              }
              if (litertToolCalls.isNotEmpty()) {
                litertMessages.add(
                  Message.model(
                    Contents.of(Content.Text(text)),
                    litertToolCalls,
                    emptyMap(),
                  )
                )
              } else {
                litertMessages.add(Message.model(text))
              }
            } else {
              litertMessages.add(Message.model(text))
            }
          }
          "tool" -> {
            // Tool result fed back by the client. Keyed by the tool name resolved from the
            // preceding assistant tool_calls (or an explicit `name` field).
            val name =
              toolCallIdToName[msg["tool_call_id"]?.jsonPrimitive?.takeIf { it.isString }?.content]
                ?: msg["name"]?.jsonPrimitive?.takeIf { it.isString }?.content
                ?: ""
            val response: Any =
              when (contentEl) {
                is JsonPrimitive -> contentEl.content
                else -> contentEl?.let { runCatching { jsonElementToAny(it) }
                  .getOrDefault("") } ?: ""
              }
            if (name.isNotEmpty()) {
              litertMessages.add(Message.tool(Contents.of(Content.ToolResponse(name, response))))
            }
          }
          // system messages are folded into the user prompt context implicitly; skip for now.
        }
      }

      // The final prompt is the last `user` message. In a tool-calling round-trip the request may
      // end with a `tool` (or assistant tool_calls) message instead — then there is no new user
      // prompt and the model should continue from the tool result already in history, so allow an
      // empty prompt here.
      val finalPrompt = prompt ?: ""
      val stream = obj["stream"]?.jsonPrimitive?.booleanOrNull ?: false
      val temperature = obj["temperature"]?.jsonPrimitive?.doubleOrNull
      val maxTokens =
        obj["max_tokens"]?.jsonPrimitive?.intOrNull ?: obj["max_completion_tokens"]?.jsonPrimitive?.intOrNull
      @Suppress("UNUSED_VARIABLE")
      val requestedModel = obj["model"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: defaultModel
      val tools = parseTools(obj)
      val hasToolsField = (obj["tools"] as? JsonArray)?.isNotEmpty() == true
      ChatRequest(
        prompt = finalPrompt,
        historyMessages = litertMessages,
        images = images,
        stream = stream,
        temperature = temperature,
        maxTokens = maxTokens,
        tools = tools,
        hasToolsField = hasToolsField,
        toolNames = extractToolNames(obj),
        toolDescriptionJson = extractToolDescriptionJson(obj),
      )
    } catch (e: Exception) {
      Log.w(TAG, "Failed to parse chat request", e)
      null
    }
  }

  /**
   * Parses the OpenAI `tools` array into LiteRT-LM [ToolProvider]s. Each `function` definition is
   * bridged as an [OpenAiFunctionTool] whose description JSON mirrors the [ReflectionTool] schema
   * (`{name, description, parameters}`), so the model can call it in manual (client-driven) mode.
   */
  private fun parseTools(obj: JsonObject): List<ToolProvider> {
    val toolsEl = obj["tools"] ?: return emptyList()
    if (toolsEl !is JsonArray) return emptyList()
    val providers = mutableListOf<ToolProvider>()
    for (t in toolsEl) {
      val tObj = t.jsonObject
      if (tObj["type"]?.jsonPrimitive?.takeIf { it.isString }?.content != "function") continue
      val fn = tObj["function"]?.jsonObject ?: continue
      val name = fn["name"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: continue
      val description = fn["description"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: ""
      val parameters = fn["parameters"]?.toString() ?: "{}"
      val descriptionJson =
        "{\"name\":${jsonString(name)},\"description\":${jsonString(description)}," +
          "\"parameters\":$parameters}"
      providers.add(tool(OpenAiFunctionTool(descriptionJson)))
      Log.d(TAG, "Tool parsed: name=$name descriptionJson=$descriptionJson")
    }
    Log.d(TAG, "parseTools parsed ${providers.size} tool(s)")
    return providers
  }

  /** Extracts the tool function names from the request body (for diagnostics). */
  private fun extractToolNames(obj: JsonObject): List<String> {
    val toolsEl = obj["tools"] as? JsonArray ?: return emptyList()
    return toolsEl.mapNotNull { t ->
      val tObj = t as? JsonObject ?: return@mapNotNull null
      if (tObj["type"]?.jsonPrimitive?.takeIf { it.isString }?.content != "function") return@mapNotNull null
      tObj["function"]?.jsonObject?.get("name")?.jsonPrimitive?.takeIf { it.isString }?.content
    }
  }

  /** Extracts the first tool's full description JSON (for diagnostics). */
  private fun extractToolDescriptionJson(obj: JsonObject): String {
    val toolsEl = obj["tools"] as? JsonArray ?: return ""
    val first =
      toolsEl.firstNotNullOfOrNull { t ->
        val tObj = t as? JsonObject ?: return@firstNotNullOfOrNull null
        if (tObj["type"]?.jsonPrimitive?.takeIf { it.isString }?.content != "function") return@firstNotNullOfOrNull null
        tObj["function"]?.jsonObject
      } ?: return ""
    return buildString {
      append("{\"name\":${jsonString(first["name"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: "")},")
      append("\"description\":${jsonString(first["description"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: "")},")
      append("\"parameters\":${first["parameters"]?.toString() ?: "{}"}}")
    }
  }

  /** Converts a [JsonElement] into a plain [Any]: maps / lists / primitives / null. */
  private fun jsonElementToAny(element: JsonElement): Any {
    return when (element) {
      is JsonObject -> {
        val map = LinkedHashMap<String, Any>()
        for ((k, v) in element) map[k] = jsonElementToAny(v)
        map
      }
      is JsonArray -> element.map { jsonElementToAny(it) }
      is JsonPrimitive ->
        when {
          element.isString -> element.content
          element.booleanOrNull != null -> element.booleanOrNull!!
          element.intOrNull != null -> element.intOrNull!!
          element.doubleOrNull != null -> element.doubleOrNull!!
          else -> element.content
        }
      else -> ""
    }
  }

  /** Serializes a string into a JSON string literal (with escaping). */
  private fun jsonString(s: String): String = "\"${escapeJson(s)}\""

  /** Decodes a data:image/...;base64,... URL into a [Bitmap], or null if not decodable. */
  private fun decodeImageFromDataUrl(url: String): Bitmap? {
    return try {
      val comma = url.indexOf(',')
      val base64Data = if (comma >= 0) url.substring(comma + 1) else url
      val bytes = Base64.decode(base64Data, Base64.DEFAULT)
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to decode image from data URL", e)
      null
    }
  }

  private fun getActiveOrNull(): Model? {
    val m = activeModel
    return if (m != null && m.instance != null) m else null
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // Streaming (SSE) response
  ////////////////////////////////////////////////////////////////////////////////////////////////

  private fun streamResponse(model: Model, request: ChatRequest): Response {
    val sse = StringBuilder()
    val toolCalls = mutableListOf<ToolCall>()
    try {
      runBlocking {
        val attachments = request.images.map { Attachment.ImageBitmap(it) }
        val metadata = mutableMapOf<String, Any>()
        if (request.tools.isNotEmpty()) metadata[AgentRequest.CAPTURE_TOOL_CALLS] = "true"
        val agentRequest =
          AgentRequest(query = request.prompt, attachments = attachments, metadata = metadata)
        executor.executeStream(AgentExecutionContext(), agentRequest).collect { event ->
          when (event) {
            is AgentEvent.StreamToken -> {
              lastDiagnostics.streamTokenEvents++
              if (event.token.isNotEmpty()) {
                sse.append("data: ").append(chunkJson(model.name, event.token, finish = null)).append("\n\n")
              }
            }
            is AgentEvent.ToolCalls -> {
              lastDiagnostics.toolCallEvents++
              toolCalls.addAll(event.toolCalls)
              sse.append("data: ").append(toolCallChunkJson(model.name, event.toolCalls)).append("\n\n")
            }
            is AgentEvent.LoopTerminated -> {
              val finish = if (toolCalls.isNotEmpty()) "tool_calls" else "stop"
              sse.append("data: ").append(chunkJson(model.name, "", finish = finish)).append("\n\n")
              sse.append("data: [DONE]\n\n")
            }
            is AgentEvent.Error -> {
              sse.append("data: {\"error\":\"${escapeJson(event.errorMessage)}\"}\n\n")
              sse.append("data: [DONE]\n\n")
            }
            else -> {}
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Streaming error", e)
      sse.append("data: {\"error\":\"${escapeJson(e.message ?: "unknown")}\"}\n\n")
      sse.append("data: [DONE]\n\n")
    }

    val res = newChunkedResponse(
      Response.Status.OK,
      "text/event-stream",
      ByteArrayInputStream(sse.toString().toByteArray()),
    )
    res.addHeader("Access-Control-Allow-Origin", "*")
    res.addHeader("Cache-Control", "no-cache")
    return res
  }

  private fun chunkJson(model: String, content: String, finish: String?): String {
    val finishField = if (finish != null) "\"finish_reason\":\"$finish\"" else "\"finish_reason\":null"
    val delta = if (content.isEmpty()) "{}" else "{\"content\":\"${escapeJson(content)}\"}"
    return "{\"id\":\"chatcmpl-local\",\"object\":\"chat.completion.chunk\"," +
      "\"created\":${System.currentTimeMillis() / 1000},\"model\":\"${escapeJson(model)}\"," +
      "\"choices\":[{\"index\":0,\"delta\":$delta,$finishField}]}"
  }

  /** SSE chunk carrying a complete tool call (streaming tool calling). */
  private fun toolCallChunkJson(model: String, toolCalls: List<ToolCall>): String {
    val deltas =
      toolCalls.mapIndexed { i, tc ->
        "{\"index\":$i,\"id\":\"call_local_$i\",\"type\":\"function\"," +
          "\"function\":{\"name\":\"${escapeJson(tc.name)}\"," +
          "\"arguments\":\"${escapeJson(anyToJsonString(tc.arguments))}\"}}"
      }.joinToString(",")
    return "{\"id\":\"chatcmpl-local\",\"object\":\"chat.completion.chunk\"," +
      "\"created\":${System.currentTimeMillis() / 1000},\"model\":\"${escapeJson(model)}\"," +
      "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[$deltas]},\"finish_reason\":null}]}"
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // Non-streaming response
  ////////////////////////////////////////////////////////////////////////////////////////////////

  private fun runBlockingResponse(model: Model, request: ChatRequest): Response {
    val collected = StringBuilder()
    val toolCalls = mutableListOf<ToolCall>()
    var errorMessage: String? = null
    try {
      runBlocking {
        val attachments = request.images.map { Attachment.ImageBitmap(it) }
        val metadata = mutableMapOf<String, Any>()
        if (request.tools.isNotEmpty()) metadata[AgentRequest.CAPTURE_TOOL_CALLS] = "true"
        val agentRequest =
          AgentRequest(query = request.prompt, attachments = attachments, metadata = metadata)
        executor.executeStream(AgentExecutionContext(), agentRequest).collect { event ->
          when (event) {
            is AgentEvent.StreamToken -> {
              lastDiagnostics.streamTokenEvents++
              collected.append(event.token)
            }
            is AgentEvent.ToolCalls -> {
              lastDiagnostics.toolCallEvents++
              toolCalls.addAll(event.toolCalls)
            }
            is AgentEvent.Error -> errorMessage = event.errorMessage
            else -> {}
          }
        }
      }
    } catch (e: Exception) {
      errorMessage = e.message
    }

    if (errorMessage != null) {
      return jsonError(Response.Status.INTERNAL_ERROR, errorMessage!!)
    }

    // The model decided to call tools: hand the calls back to the client for execution.
    if (toolCalls.isNotEmpty()) {
      return jsonResponse(Response.Status.OK, toolCallsBody(model.name, request.prompt, toolCalls))
    }

    val content = escapeJson(collected.toString())
    val body =
      "{\"id\":\"chatcmpl-local\",\"object\":\"chat.completion\"," +
        "\"created\":${System.currentTimeMillis() / 1000},\"model\":\"${escapeJson(model.name)}\"," +
        "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"$content\"}," +
        "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":${request.prompt.length / 4}," +
        "\"completion_tokens\":${collected.length / 4},\"total_tokens\":${(request.prompt.length + collected.length) / 4}}}"
    return jsonResponse(Response.Status.OK, body)
  }

  /** Non-streaming `chat.completion` body carrying the requested tool calls. */
  private fun toolCallsBody(model: String, prompt: String, toolCalls: List<ToolCall>): String {
    val calls =
      toolCalls.mapIndexed { i, tc ->
        "{\"id\":\"call_local_$i\",\"type\":\"function\"," +
          "\"function\":{\"name\":\"${escapeJson(tc.name)}\"," +
          "\"arguments\":\"${escapeJson(anyToJsonString(tc.arguments))}\"}}"
      }.joinToString(",")
    return "{\"id\":\"chatcmpl-local\",\"object\":\"chat.completion\"," +
      "\"created\":${System.currentTimeMillis() / 1000},\"model\":\"${escapeJson(model)}\"," +
      "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null," +
      "\"tool_calls\":[$calls]},\"finish_reason\":\"tool_calls\"}]," +
      "\"usage\":{\"prompt_tokens\":${prompt.length / 4},\"completion_tokens\":0," +
      "\"total_tokens\":${prompt.length / 4}}}"
  }

  /** Serializes a plain [Map]/`List` value (from a LiteRT [ToolCall]) back into a JSON string. */
  private fun anyToJsonString(value: Any?): String {
    return when (value) {
      null -> "null"
      is String -> "\"${escapeJson(value)}\""
      is Boolean -> value.toString()
      is Int, is Long, is Double, is Float -> value.toString()
      is Map<*, *> ->
        value.entries.joinToString(",") { (k, v) ->
          "\"${escapeJson(k.toString())}\":${anyToJsonString(v)}"
        }.let { "{$it}" }
      is List<*> -> value.joinToString(",") { anyToJsonString(it) }.let { "[$it]" }
      else -> "\"${escapeJson(value.toString())}\""
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // Helpers
  ////////////////////////////////////////////////////////////////////////////////////////////////

  private fun jsonResponse(status: Response.Status, body: String): Response {
    val res = newFixedLengthResponse(status, "application/json", body)
    res.addHeader("Access-Control-Allow-Origin", "*")
    return res
  }

  private fun jsonError(status: Response.Status, message: String): Response {
    return jsonResponse(status, "{\"error\":{\"message\":\"${escapeJson(message)}\"}}")
  }

  private fun escapeJson(s: String): String {
    val sb = StringBuilder(s.length + 16)
    for (c in s) {
      when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        '\b' -> sb.append("\\b")
        '\u000C' -> sb.append("\\f")
        else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
      }
    }
    return sb.toString()
  }
}