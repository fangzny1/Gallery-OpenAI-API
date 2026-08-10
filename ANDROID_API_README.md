# Gallery 本地 API 服务器（魔改说明）

本文档说明如何在 Google AI Edge Gallery App 上启用「对外 OpenAI 兼容 API」功能，
让电脑 / 局域网内的程序通过 HTTP 调用手机 / 平板上加载的本地离线模型。

## 这个魔改做了什么

项目原本把模型推理完全跑在设备本地，App 不对外提供任何 HTTP 接口。
本魔改新增了一个 **OpenAI 兼容的本地 HTTP 服务器**，让外部客户端可以这样调用本地模型：

```
POST http://<手机局域网IP>:8080/v1/chat/completions
```

支持 OpenAI 的两种调用方式：
- 普通（一次性返回）：`stream: false`
- 流式 SSE：`stream: true`（边推理边吐字）

任何兼容 OpenAI 的工具都能直接连：`curl`、Python 的 `openai` 库、各种聊天客户端等。

## 改动清单

| 文件 | 作用 |
| --- | --- |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/api/OpenAiApiServer.kt` | 新增。核心 HTTP 服务器（基于 NanoHTTPD），实现 `/v1/chat/completions`、`/v1/models`、`/health` |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/api/ApiServerViewModel.kt` | 新增。Compose 用的 ViewModel，封装 server 的启停和状态 |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/ChatView.kt` | 在聊天页顶栏接入 API 开关 + snackbar 反馈 |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/ModelPageAppBar.kt` | 在顶栏 actions 区加「链接」图标按钮（点亮=运行中） |
| `Android/src/app/src/main/res/values/strings.xml` | 新增几条 UI 文案 |
| `Android/src/gradle/libs.versions.toml` | 新增 NanoHTTPD 依赖 |
| `Android/src/app/build.gradle.kts` | 引入 NanoHTTPD |
| `Android/src/app/.../runtime/LlmModelHelper.kt`、`ui/llmchat/LlmChatModelHelper.kt`、`runtime/aicore/AICoreModelHelper.kt` | 支持工具调用：`resetConversation` 加 `automaticToolCalling`、`runInference` 加 `onToolCall`（手工/客户端驱动模式） |
| `Android/src/app/.../agent/AgentEvent.kt`、`AgentRequest.kt`、`DefaultAgentRuntimeExecutor.kt`、`ui/llmchat/LlmChatViewModel.kt` | 新增 `AgentEvent.ToolCalls` 事件并按需转发模型发出的工具调用 |

## 工作原理（重要概念）

关键设计：**服务器不自己初始化模型，而是复用 App 里已经跑起来的模型会话。**

- 用户先在聊天界面下载并初始化一个模型（比如 Gemma）。
- 点顶栏的「链接」图标，服务器启动，并把当前这个已初始化的模型设为对外服务对象。
- 外部请求进来时，服务器调用同一个 `AgentRuntimeExecutor`（与 App 内聊天共用），
  把 `AgentEvent.StreamToken` 流式转发成 SSE 返回。

这意味着：
1. **必须先**在 App 里把模型跑起来（下载 + 初始化），点开关才有意义。
2. 同一时刻只能有一个推理在跑（本地 runtime 不支持并发多轮），并发请求会自动**排队**依次执行，不会报错。
3. 服务器只服务「当前选中的那个模型」（`model` 字段会被忽略，始终用当前激活模型）。

## 使用步骤

### 编译
按项目官方 `DEVELOPMENT.md` 用 Android Studio 打开 `Android/` 目录，配置好 HuggingFace
OAuth 凭据后正常构建即可（新增的 NanoHTTPD 依赖会自动从 Maven Central 拉取，无需额外配置）。

### 运行
1. 手机和电脑连同一个 Wi-Fi（局域网）。
2. 打开 App → 进入 AI Chat → 下载并初始化一个模型。
3. 点顶栏的「链接」图标（History 图标旁边的 Link 图标），弹出 API 设置弹窗。
4. 在弹窗里：
   - 打开 **Enable API server** 开关启动服务器；
   - （可选）在 **API access token** 里填一个 token 并保存，开启 Bearer Token 鉴权。
5. 启动成功会弹出 snackbar，显示服务器地址，例如：
   `Local API server started at http://192.168.1.5:8080`
6. 在电脑上测试：

```bash
# 列出可用模型
curl http://192.168.1.5:8080/v1/models

# 普通调用（一次性返回）
curl http://192.168.1.5:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"你好，介绍一下你自己"}],"stream":false}'

# 流式调用（SSE，边推理边吐字）
curl -N http://192.168.1.5:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"讲个笑话"}],"stream":true}'
```

> 如果开启了 Token 鉴权，所有请求都要额外带 `Authorization: Bearer <token>` 头，
> 未带或错误会返回 `401`。例如：
> ```bash
> curl -H "Authorization: Bearer 你的token" http://192.168.1.5:8080/v1/models
> ```

用 Python `openai` 库（把 base_url 指向手机）：

```python
from openai import OpenAI

client = OpenAI(base_url="http://192.168.1.5:8080/v1", api_key="not-needed")
resp = client.chat.completions.create(
    model="any",  # 服务器只认当前激活的模型，这个字段被忽略
    messages=[{"role": "user", "content": "你好"}],
)
print(resp.choices[0].message.content)
```

6. 再点一下「链接」图标即可关闭服务器。

## 工具调用（Function Calling）

服务器支持 OpenAI 的**客户端驱动**工具调用：客户端定义 `tools`，模型决定调用某个工具时，
服务器不是自己在手机上执行，而是把 `tool_calls` 返回给客户端；客户端执行后把结果以
`role: "tool"` 的消息回传，模型再基于结果继续回答。

用 curl 完整走一遍（两步）——第一步模型返回 `tool_calls`，第二步回传工具结果得到最终回答：

```bash
# ① 模型要求调用 get_current_time
curl -N http://<手机IP>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"现在几点了？用 get_current_time 查"}],
       "tools":[{"type":"function","function":{"name":"get_current_time",
                 "description":"获取当前时间",
                 "parameters":{"type":"object","properties":{}}}}],
       "stream":false}'
# → 返回 tool_calls:[{id:"call_local_0", function:{name:"get_current_time", arguments:"{}"}}], finish_reason:"tool_calls"

# ② 客户端"执行"工具后，把 assistant 的 tool_calls 和 tool 结果一起回传
curl -N http://<手机IP>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"messages":[
         {"role":"user","content":"现在几点了？用 get_current_time 查"},
         {"role":"assistant","content":null,
          "tool_calls":[{"id":"call_local_0","type":"function",
                         "function":{"name":"get_current_time","arguments":"{}"}}]},
         {"role":"tool","tool_call_id":"call_local_0",
          "content":"{\"time\":\"2026-08-10 12:44:00\"}"}],
       "tools":[{"type":"function","function":{"name":"get_current_time",
                 "description":"获取当前时间",
                 "parameters":{"type":"object","properties":{}}}}],
       "stream":false}'
# → 返回最终回答，如 "现在是 2026年8月10日 12:44:00。"
```

用 Python `openai` 库实测（`tools` → `tool_calls` → `tool` 结果 → 最终回答）：

```python
from openai import OpenAI

client = OpenAI(base_url="http://192.168.1.5:8080/v1", api_key="not-needed")

# 1. 定义一个工具（函数）
tools = [
    {
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "查询某个城市的天气",
            "parameters": {
                "type": "object",
                "properties": {
                    "city": {"type": "string", "description": "城市名，如 北京"}
                },
                "required": ["city"],
            },
        },
    }
]

# 2. 第一次请求：模型会要求调用 get_weather
resp = client.chat.completions.create(
    model="any",
    messages=[{"role": "user", "content": "北京的天气怎么样？"}],
    tools=tools,
)
msg = resp.choices[0].message
print("tool_calls:", msg.tool_calls)  # finish_reason = "tool_calls"

# 3. 客户端"执行"工具（这里是模拟），再把结果回传
tool_call = msg.tool_calls[0]
tool_result = '{"temperature": 23, "condition": "晴"}'
messages = [
    {"role": "user", "content": "北京的天气怎么样？"},
    msg,  # assistant 的 tool_calls 消息也要带上
    {
        "role": "tool",
        "tool_call_id": tool_call.id,
        "content": tool_result,
    },
]

# 4. 第二次请求：模型基于工具结果给出最终回答
final = client.chat.completions.create(model="any", messages=messages, tools=tools)
print(final.choices[0].message.content)
```

要点：

- 只有请求里带 `tools` 时才会启用工具调用；不带 `tools` 的行为和原来完全一致。
- 工具调用走**客户端驱动**：服务器不执行工具，只把 `tool_calls` 交还给你。
- 回传 `tool` 结果时，`messages` 里要带上前面那条带 `tool_calls` 的 `assistant` 消息，
  并保持 `tool_call_id` 与返回的 `id` 一致。
- 流式（`stream: true`）下，工具调用会以单个 `delta.tool_calls` 块整体返回（不做逐字符增量）。
- 工具调用目前只支持 LlmChat（Gemma 等 Magic LM）模型；AICore 模型不支持。

## 常见问题

- **工具调用不生效**：先 `curl http://<手机IP>:8080/debug` 看 `lastDiagnostics`——
  - `toolCount` 为 `0` 说明请求里没带 `tools`（或格式不对），客户端没把工具发过来。
  - `toolCount > 0` 但 `toolCallEvents == 0` 说明模型没有发出工具调用（工具 schema 没被模型识别）。
  - `toolCallEvents > 0` 说明工具调用已捕获、多轮回传有问题。
  - 该端点返回最近一次请求的诊断信息，无需 adb / logcat。
- **点了开关提示 "Failed to start local API server"**：通常是端口 8080 被占用或没网络权限。
  代码里默认端口是 8080，可改 `OpenAiApiServer.kt` 的 `startServer(port = 8080)`。
- **"No active model"**：模型还没初始化。先进聊天界面确保模型下载并跑起来。
- **并发请求**：本地 runtime 一次只能跑一个推理，并发请求会自动排队依次执行（不会报错）。
- **手机能访问但电脑不行**：确认同一局域网、路由没开 AP 隔离；服务器监听在 0.0.0.0（默认）。
- **浏览器 / 网页客户端连不上**：确认用的是 `http://`（不是 `https://`），且地址带 `/v1`；
  服务器已支持 CORS 预检，浏览器跨域可访问。
- **中文乱码 / 返回 "Invalid request body"**：需重新编译最新代码（已修复 keep-alive 下的
  请求体读取和 UTF-8 解码）。

## 已支持的功能

- ✅ **多轮上下文**：请求里的完整 `messages` 数组会转成 LiteRT 消息传给模型，模型能记住对话历史。
- ✅ **新建对话自动重置**：客户端开新对话时带上自己的历史，上一场对话不会被串进来。
- ✅ **图片理解（Ask Image）**：支持多模态模型，通过 `image_url`（base64 data URL）传图。
- ✅ **工具调用（Function Calling）**：支持 OpenAI 的 `tools` 参数，返回 `tool_calls` 交给客户端执行（见下文）。
- ✅ **Bearer Token 鉴权**：设置 token 后未带/错误 token 的请求返回 401。
- ✅ **请求排队**：并发请求自动排队，替代原来的 409。
- ✅ **CORS**：支持浏览器跨域访问（网页端 Chat 客户端可用）。

## 已知限制 / 后续可扩展

- `temperature` / `max_tokens` 参数当前被读取但未真正应用（本地模型用自身配置）。
- 图片仅支持 base64 data URL（`data:image/...;base64,...`），暂不支持远程 URL 或文件路径。
- 工具调用为**客户端驱动**：服务器不执行工具，只返回 `tool_calls`；且只支持 LlmChat（Gemma 等）模型，AICore 模型不支持。
- 流式工具调用以单个 `delta.tool_calls` 块返回，不做逐字符增量（多数客户端可接受，但严格按迹解析的客户端可能要求增量）。
- 可通过 `model` 字段 + 多模型管理扩展成「按模型名路由」。
- 鉴权是局域网级别的 Bearer Token，非生产级安全（足够共享网络防误用）。