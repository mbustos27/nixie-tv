// SPDX-FileCopyrightText: 2026 Nixie contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.mbustos27.nixie.tv.data

import io.github.glacier_jellyfin.androidtv.core.data.Session
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.jellyfin.sdk.model.api.UserDataChangeInfo

/** What a message of the server's websocket asks of the app, or tells it ([SocketMessages.parse]). */
sealed interface SocketMessage {
    /** Send a KeepAlive every [seconds] / 2 seconds, or the server drops the connection. */
    data class ForceKeepAlive(val seconds: Int) : SocketMessage

    /** The server checks the connection: answer with a KeepAlive. */
    data object KeepAlive : SocketMessage

    data class UserData(val userId: UUID, val changes: List<UserDataChange>) : SocketMessage

    /** Anything else (sessions, library changes, commands): not followed here. */
    data object Other : SocketMessage
}

object SocketMessages {
    private val json = Json { ignoreUnknownKeys = true }

    /** The message an app sends to keep the connection. */
    const val KEEP_ALIVE = """{"MessageType":"KeepAlive"}"""

    fun parse(text: String): SocketMessage = runCatching {
        val message = json.parseToJsonElement(text).jsonObject
        val data = message["Data"]
        when (message["MessageType"]?.jsonPrimitive?.contentOrNull) {
            "ForceKeepAlive" -> SocketMessage.ForceKeepAlive(data?.jsonPrimitive?.intOrNull ?: DEFAULT_KEEP_ALIVE)

            "KeepAlive" -> SocketMessage.KeepAlive

            "UserDataChanged" -> (data as? JsonObject)?.let { info ->
                val changed = json.decodeFromJsonElement(UserDataChangeInfo.serializer(), info)
                SocketMessage.UserData(changed.userId, changed.userDataList.mapNotNull { it.toChange() })
            } ?: SocketMessage.Other

            else -> SocketMessage.Other
        }
    }.getOrDefault(SocketMessage.Other)

    /** The websocket's address for a server at [baseUrl]: ws(s)://…/socket. */
    fun socketUrl(baseUrl: String): String =
        baseUrl.trimEnd('/').replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws") + "/socket"

    private const val DEFAULT_KEEP_ALIVE = 60
}

/**
 * Nixie: the server's websocket over OkHttp, one connection per collection (see [SocketLink]).
 * The token goes in the Authorization header with the profile's own device id, never in the
 * address. A handshake answered 401 or 403 fails with [SocketRefused].
 */
@Singleton
class OkHttpSocketLink @Inject constructor() : SocketLink {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
        .build()

    override fun open(session: Session): Flow<SocketEvent> = callbackFlow {
        val baseUrl = checkNotNull(session.api.baseUrl) { "The session has no server address" }
        val request = Request.Builder()
            .url(SocketMessages.socketUrl(baseUrl))
            .header("Authorization", session.authorization())
            .build()
        var keepAlive: Job? = null
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    trySend(SocketEvent.Opened)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val message = SocketMessages.parse(text)) {
                        is SocketMessage.ForceKeepAlive -> {
                            keepAlive?.cancel()
                            val everyMs = message.seconds.coerceAtLeast(2) * 1_000L / 2
                            keepAlive = launch {
                                while (isActive) {
                                    delay(everyMs)
                                    webSocket.send(SocketMessages.KEEP_ALIVE)
                                }
                            }
                        }

                        SocketMessage.KeepAlive -> webSocket.send(SocketMessages.KEEP_ALIVE)

                        is SocketMessage.UserData -> trySend(SocketEvent.Changed(message.userId, message.changes))

                        SocketMessage.Other -> Unit
                    }
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    webSocket.close(NORMAL_CLOSURE, null)
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    close()
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    val code = response?.code
                    close(if (code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN) SocketRefused("HTTP $code") else t)
                }
            },
        )
        awaitClose {
            keepAlive?.cancel()
            socket.close(NORMAL_CLOSURE, null)
        }
    }

    private companion object {
        const val PING_SECONDS = 30L
        const val NORMAL_CLOSURE = 1000
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
