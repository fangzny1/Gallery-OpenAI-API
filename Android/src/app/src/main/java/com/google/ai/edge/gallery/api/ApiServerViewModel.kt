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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI-facing wrapper around [OpenAiApiServer] so Compose screens can toggle the API server
 * and observe its state (running / URL / auth token).
 */
@HiltViewModel
class ApiServerViewModel
@Inject
constructor(
  private val server: OpenAiApiServer,
  private val dataStoreRepository: DataStoreRepository,
) : ViewModel() {

  private val _isRunning = MutableStateFlow(server.isRunning)
  val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

  private val _baseUrl = MutableStateFlow<String?>(null)
  val baseUrl: StateFlow<String?> = _baseUrl.asStateFlow()

  private val _authToken = MutableStateFlow<String?>(null)
  val authToken: StateFlow<String?> = _authToken.asStateFlow()

  /** Loads the saved auth token from persistence and applies it to the server. Call once at init. */
  fun loadAuthToken() {
    val saved = dataStoreRepository.readSecret(TOKEN_KEY)
    _authToken.value = saved
    server.setAuthToken(saved)
  }

  /** Enables/disables bearer-token auth and persists the token. Pass null to disable. */
  fun setAuthToken(token: String?) {
    val clean = token?.trim()?.takeIf { it.isNotEmpty() }
    _authToken.value = clean
    server.setAuthToken(clean)
    viewModelScope.launch {
      if (clean == null) {
        dataStoreRepository.deleteSecret(TOKEN_KEY)
      } else {
        dataStoreRepository.saveSecret(TOKEN_KEY, clean)
      }
    }
  }

  /** Points the server at the given model and task, then starts it. */
  fun start(model: Model, taskId: String): String? {
    server.setActiveModel(model, taskId)
    val url = server.startServer()
    _isRunning.value = server.isRunning
    _baseUrl.value = url
    return url
  }

  /** Stops the server. */
  fun stop() {
    server.stopServer()
    _isRunning.value = false
    _baseUrl.value = null
  }

  /**
   * Toggles the server on/off.
   *
   * @return the base URL if the server is now running (was just started), the string "STOPPED"
   *   if it was just stopped, or null if it is not running (e.g. failed to start).
   */
  fun toggle(model: Model, taskId: String): String? {
    return if (_isRunning.value) {
      stop()
      STOPPED
    } else {
      start(model, taskId)
    }
  }

  companion object {
    const val STOPPED = "\u0000STOPPED"
    private const val TOKEN_KEY = "api_auth_token"
  }
}