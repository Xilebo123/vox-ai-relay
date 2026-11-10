package pl.marcin.openclaw.gateway

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.json.JSONArray
import pl.marcin.openclaw.attachment.ChatAttachment
import pl.marcin.openclaw.BuildConfig
import pl.marcin.openclaw.storage.AppPreferences
import pl.marcin.openclaw.storage.BootstrapAuthType
import pl.marcin.openclaw.storage.GatewaySettings
import pl.marcin.openclaw.storage.RecentChatSession
import pl.marcin.openclaw.chat.ChatMessage
import pl.marcin.openclaw.chat.ChatStreamEvent
import pl.marcin.openclaw.chat.ChatStreamSource
import pl.marcin.openclaw.chat.VisibleChatText
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed interface GatewayConnectionEvent {
    data object Connecting : GatewayConnectionEvent
    data class PairingRequired(val requestId: String?) : GatewayConnectionEvent
    data class Ready(val serverVersion: String?, val untrustedTls: Boolean) : GatewayConnectionEvent
    data class Error(val code: String?, val message: String) : GatewayConnectionEvent
    data object Disconnected : GatewayConnectionEvent
    data class History(val messages: List<ChatMessage>) : GatewayConnectionEvent
    data class SendAccepted(val runId: String, val sessionKey: String) : GatewayConnectionEvent
    data class ChatEvent(val event: ChatStreamEvent) : GatewayConnectionEvent
    data class AgentActivity(
        val sessionKey: String,
        val runId: String,
        val kind: AgentActivityKind,
        val phase: String,
        val toolCallId: String? = null,
        val toolName: String? = null,
    ) : GatewayConnectionEvent
    data class DebugFrame(val text: String) : GatewayConnectionEvent
    data object SessionReset : GatewayConnectionEvent
}

enum class AgentActivityKind { WORKING, THINKING, SEARCH, WEB, FILE, COMMAND, SKILL, TOOL }

class GatewayClient(context: Context, private val listener: (GatewayConnectionEvent) -> Unit) {
    private val appContext = context.applicationContext
    private val identities = DeviceIdentityStore(appContext)
    private val preferences = AppPreferences(appContext)
    private var socket: WebSocket? = null
    private var connectRequestId: String? = null
    private var gatewayId: String? = null
    private val pending = ConcurrentHashMap<String, (JSONObject?, JSONObject?) -> Unit>()
    @Volatile private var ready = false
    @Volatile private var sessionKey = preferences.loadChatSessionKey()
    @Volatile private var manuallyResumedSession: String? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())
    @Volatile private var desiredSettings: GatewaySettings? = null
    @Volatile private var connectionGeneration = 0L
    private var reconnectAttempt = 0

    fun connect(settings: GatewaySettings) {
        desiredSettings = settings
        reconnectAttempt = 0
        connectionGeneration += 1
        socket?.cancel()
        openSocket(settings, connectionGeneration)
    }

    private fun openSocket(settings: GatewaySettings, generation: Long) {
        val url = settings.url.trim()
        if (!isAllowedGatewayUrl(url)) {
            listener(GatewayConnectionEvent.Error("INVALID_URL", "Use wss:// or a private-LAN ws:// address in debug builds"))
            return
        }
        listener(GatewayConnectionEvent.Connecting)
        gatewayId = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        val allowUntrustedTls = BuildConfig.DEBUG && settings.allowUntrustedTls && url.startsWith("wss://", true)
        socket = createHttpClient(allowUntrustedTls)
            .newWebSocket(Request.Builder().url(url).build(), SocketListener(settings, allowUntrustedTls, generation))
    }

    private fun createHttpClient(allowUntrustedTls: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
        if (!allowUntrustedTls) return builder.build()

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        return builder
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun isAllowedGatewayUrl(url: String): Boolean {
        if (url.startsWith("wss://", ignoreCase = true)) return true
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        if (!url.startsWith("ws://", ignoreCase = true)) return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
        val octets = host.split('.').mapNotNull(String::toIntOrNull)
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    fun close() {
        desiredSettings = null
        connectionGeneration += 1
        reconnectHandler.removeCallbacksAndMessages(null)
        ready = false
        pending.clear()
        socket?.close(1000, "client closing")
        socket = null
    }

    fun sendChat(message: String, attachments: List<ChatAttachment> = emptyList()): String? {
        if (!ready || (message.isBlank() && attachments.isEmpty())) return null
        val idempotencyKey = UUID.randomUUID().toString()
        val params = JSONObject()
            .put("sessionKey", sessionKey)
            .put("message", message)
            .put("idempotencyKey", idempotencyKey)
        if (attachments.isNotEmpty()) params.put("attachments", JSONArray().apply {
            attachments.forEach { attachment -> put(JSONObject()
                .put("type", if (attachment.mimeType.startsWith("image/")) "image" else "file")
                .put("mimeType", attachment.mimeType)
                .put("fileName", attachment.fileName)
                .put("content", attachment.contentBase64)) }
        })
        request("chat.send", params) { payload, error ->
            if (error != null) {
                listener(GatewayConnectionEvent.Error(error.optString("code"), error.optString("message", "chat.send failed")))
            } else {
                val runId = payload?.optString("runId")?.takeIf { it.isNotBlank() } ?: idempotencyKey
                listener(GatewayConnectionEvent.SendAccepted(runId, sessionKey))
            }
        }
        return idempotencyKey
    }

    fun currentSessionKey(): String = sessionKey

    /** Returns true when a stale conversation was replaced before this send. */
    fun prepareSessionForSend(preview: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val manualResume = manuallyResumedSession == sessionKey
        manuallyResumedSession = null
        val rotated = !manualResume && preferences.isSessionExpired(sessionKey, nowMs)
        if (rotated) {
            if (preferences.loadRecentSessions().none { it.key == sessionKey }) {
                val timeout = preferences.loadConversationSettings().inactivityMinutes.coerceAtLeast(1) * 60_000L
                preferences.touchSession(sessionKey, nowMs = nowMs - timeout - 1)
            }
            sessionKey = newAndroidSessionKey()
            preferences.saveChatSessionKey(sessionKey)
        }
        preferences.touchSession(sessionKey, preview, nowMs)
        return rotated
    }

    fun markSessionActivity(nowMs: Long = System.currentTimeMillis()) =
        preferences.touchSession(sessionKey, nowMs = nowMs)

    fun recentSessions(): List<RecentChatSession> = preferences.loadRecentSessions()

    fun resumeSession(key: String) {
        if (!ready || key.isBlank()) return
        sessionKey = key
        manuallyResumedSession = key
        preferences.saveChatSessionKey(key)
        listener(GatewayConnectionEvent.SessionReset)
        loadHistory()
    }

    fun abort(runId: String?) {
        if (!ready) return
        val params = JSONObject().put("sessionKey", sessionKey)
        runId?.takeIf { it.isNotBlank() }?.let { params.put("runId", it) }
        request("chat.abort", params) { _, error ->
            if (error != null) listener(GatewayConnectionEvent.Error(error.optString("code"), error.optString("message", "chat.abort failed")))
        }
    }

    fun resetSession() {
        if (!ready) return
        sessionKey = newAndroidSessionKey()
        manuallyResumedSession = null
        preferences.saveChatSessionKey(sessionKey)
        preferences.touchSession(sessionKey)
        listener(GatewayConnectionEvent.SessionReset)
    }

    private fun newAndroidSessionKey(): String = "agent:main:android:${UUID.randomUUID()}"

    private fun loadHistory() {
        request("chat.history", JSONObject().put("sessionKey", sessionKey).put("limit", 40)) { payload, error ->
            if (error != null) {
                listener(GatewayConnectionEvent.Error(error.optString("code"), error.optString("message", "chat.history failed")))
            } else {
                listener(GatewayConnectionEvent.History(parseHistory(payload)))
            }
        }
    }

    private fun request(method: String, params: JSONObject, callback: (JSONObject?, JSONObject?) -> Unit) {
        val id = UUID.randomUUID().toString()
        pending[id] = callback
        val sent = socket?.send(JSONObject().put("type", "req").put("id", id).put("method", method).put("params", params).toString()) == true
        if (!sent) pending.remove(id)?.invoke(null, JSONObject().put("code", "DISCONNECTED").put("message", "Gateway is disconnected"))
    }

    private inner class SocketListener(
        private val settings: GatewaySettings,
        private val untrustedTls: Boolean,
        private val generation: Long,
    ) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (generation != connectionGeneration) return
            GatewayProtocol.parseChallenge(text)?.let { challenge ->
                sendConnect(webSocket, challenge, settings)
                return
            }
            val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
            val eventName = frame.optString("event")
            val eventPayload = frame.optJSONObject("payload")
            val eventSession = eventPayload?.optString("sessionKey").orEmpty()
            val belongsToCurrentSession = eventSession.isBlank() || eventSession == sessionKey
            if (BuildConfig.DEBUG && frame.optString("type") == "event" &&
                belongsToCurrentSession && (eventName == "chat" || eventName == "agent" || eventName == "session.tool")) {
                val trace = buildString {
                    append("EVENT ").append(eventName).append('\n')
                    append(frame.optJSONObject("payload")?.toString(2) ?: "null")
                }.take(100_000)
                listener(GatewayConnectionEvent.DebugFrame(trace))
            }
            if (frame.optString("type") == "event" && frame.optString("event") == "chat") {
                if (!belongsToCurrentSession) return
                parseChatEvent(frame.optJSONObject("payload"))?.let {
                    listener(GatewayConnectionEvent.ChatEvent(it))
                    if (it.state == "final" || it.state == "aborted" || it.state == "error") loadHistory()
                }
                return
            }
            if (frame.optString("type") == "event" && frame.optString("event") == "agent") {
                if (!belongsToCurrentSession) return
                val payload = frame.optJSONObject("payload")
                parseAgentActivityEvent(payload)?.let(listener)
                parseAgentAssistantEvent(payload)?.let {
                    listener(GatewayConnectionEvent.ChatEvent(it))
                }
                return
            }
            if (frame.optString("type") == "event" && frame.optString("event") == "session.tool") {
                if (!belongsToCurrentSession) return
                parseAgentActivityEvent(frame.optJSONObject("payload"), forceToolStream = true)?.let(listener)
                return
            }
            handleResponse(frame, untrustedTls)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (generation != connectionGeneration) return
            ready = false
            pending.clear()
            listener(GatewayConnectionEvent.Error("TRANSPORT", t.message ?: "WebSocket failure"))
            scheduleReconnect(generation)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (generation != connectionGeneration) return
            ready = false
            listener(GatewayConnectionEvent.Disconnected)
            scheduleReconnect(generation)
        }
    }

    private fun scheduleReconnect(generation: Long) {
        val settings = desiredSettings ?: return
        reconnectAttempt += 1
        val base = minOf(15_000L, 500L shl minOf(reconnectAttempt - 1, 5))
        val jitter = (0..250).random().toLong()
        reconnectHandler.postDelayed({
            if (generation == connectionGeneration && desiredSettings != null) {
                listener(GatewayConnectionEvent.Connecting)
                openSocket(settings, generation)
            }
        }, base + jitter)
    }

    private fun sendConnect(webSocket: WebSocket, challenge: ConnectChallenge, settings: GatewaySettings) {
        val identity = identities.loadOrCreate()
        val storedToken = gatewayId?.let(preferences::loadDeviceToken)
        val authCredential = storedToken ?: settings.credential
        val authType = if (storedToken != null || settings.authType == BootstrapAuthType.TOKEN) "token" else "password"
        val requestId = UUID.randomUUID().toString().also { connectRequestId = it }
        val signedAt = System.currentTimeMillis()
        webSocket.send(GatewayProtocol.connectRequest(
            requestId, challenge, identity, authType, authCredential,
            signer = { identities.sign(it, identity) }, signedAtMs = signedAt,
        ))
    }

    private fun handleResponse(frame: JSONObject, untrustedTls: Boolean) {
        try {
            if (frame.optString("type") != "res") return
            val responseId = frame.optString("id")
            if (responseId != connectRequestId) {
                pending.remove(responseId)?.invoke(frame.optJSONObject("payload"), frame.optJSONObject("error"))
                return
            }
            if (!frame.optBoolean("ok")) {
                val error = frame.optJSONObject("error")
                val details = error?.optJSONObject("details")
                val code = details?.optString("code")?.ifBlank { null } ?: error?.optString("code")
                if (code == "PAIRING_REQUIRED") {
                    desiredSettings = null
                    listener(GatewayConnectionEvent.PairingRequired(details?.optString("requestId")?.ifBlank { null }))
                } else {
                    listener(GatewayConnectionEvent.Error(code, error?.optString("message") ?: "Gateway rejected connection"))
                }
                return
            }
            val payload = frame.getJSONObject("payload")
            if (payload.optString("type") != "hello-ok" || payload.optInt("protocol") != GatewayProtocol.VERSION) {
                listener(GatewayConnectionEvent.Error("PROTOCOL_MISMATCH", "Invalid hello-ok response"))
                return
            }
            val auth = payload.getJSONObject("auth")
            auth.optString("deviceToken").takeIf { it.isNotBlank() }?.let { token ->
                gatewayId?.let { preferences.saveDeviceToken(it, token) }
            }
            listener(GatewayConnectionEvent.Ready(
                payload.optJSONObject("server")?.optString("version"),
                untrustedTls,
            ))
            ready = true
            reconnectAttempt = 0
            loadHistory()
        } catch (_: Throwable) {
            listener(GatewayConnectionEvent.Error("INVALID_FRAME", "Invalid Gateway response"))
        }
    }

    private fun parseHistory(payload: JSONObject?): List<ChatMessage> {
        val array = payload?.optJSONArray("messages") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val role = item.optString("role")
                if (role != "user" && role != "assistant") continue
                val text = extractMessageText(item) ?: continue
                add(ChatMessage(role, text, item.optJSONObject("__openclaw")?.optString("id")))
            }
        }
    }

    private fun parseChatEvent(payload: JSONObject?): ChatStreamEvent? {
        payload ?: return null
        val session = normalizeSessionKey(payload.optString("sessionKey").takeIf { it.isNotBlank() } ?: return null)
        val run = payload.optString("runId").takeIf { it.isNotBlank() } ?: return null
        return ChatStreamEvent(
            sessionKey = session,
            runId = run,
            seq = payload.optLong("seq", -1),
            state = payload.optString("state"),
            deltaText = payload.optString("deltaText"),
            snapshotText = payload.optJSONObject("message")?.let(::extractMessageText),
            replace = payload.optBoolean("replace", false),
            errorMessage = payload.optString("errorMessage").takeIf { it.isNotBlank() },
        )
    }

    private fun parseAgentAssistantEvent(payload: JSONObject?): ChatStreamEvent? {
        payload ?: return null
        if (payload.optString("stream") != "assistant") return null
        val session = normalizeSessionKey(payload.optString("sessionKey").takeIf { it.isNotBlank() } ?: sessionKey)
        val run = payload.optString("runId").takeIf { it.isNotBlank() } ?: return null
        val seq = payload.optLong("seq", -1)
        if (seq < 0) return null
        val data = payload.optJSONObject("data") ?: return null
        if (data.optString("phase").equals("commentary", ignoreCase = true)) return null
        val snapshot = data.optString("text").takeIf { it.isNotEmpty() }
            ?.let(VisibleChatText::fromPossiblyPartialWire)
            ?.takeIf { it.isNotEmpty() }
        val delta = (data.optString("delta").takeIf { it.isNotEmpty() }
            ?: data.optString("deltaText").takeIf { it.isNotEmpty() })
            ?.let(VisibleChatText::fromPossiblyPartialWire)
            .orEmpty()
        if (snapshot == null && delta.isEmpty()) return null
        return ChatStreamEvent(
            sessionKey = session,
            runId = run,
            seq = seq,
            state = "delta",
            deltaText = delta,
            snapshotText = snapshot,
            source = ChatStreamSource.AGENT,
        )
    }

    private fun parseAgentActivityEvent(payload: JSONObject?, forceToolStream: Boolean = false): GatewayConnectionEvent.AgentActivity? {
        payload ?: return null
        val stream = if (forceToolStream) "tool" else payload.optString("stream")
        if (stream == "assistant") return null
        val session = normalizeSessionKey(payload.optString("sessionKey").takeIf { it.isNotBlank() } ?: sessionKey)
        val run = payload.optString("runId").takeIf { it.isNotBlank() } ?: return null
        val data = payload.optJSONObject("data")
        val phase = data?.optString("phase")?.ifBlank { "update" } ?: "update"
        val rawToolName = sequenceOf("name", "toolName", "tool").mapNotNull { key -> data?.optString(key)?.takeIf(String::isNotBlank) }.firstOrNull().orEmpty()
        val toolName = rawToolName.lowercase()
        val toolCallId = sequenceOf("toolCallId", "callId", "id").mapNotNull { key -> data?.optString(key)?.takeIf(String::isNotBlank) }.firstOrNull()
        val kind = when {
            stream == "lifecycle" -> AgentActivityKind.WORKING
            stream == "thinking" -> AgentActivityKind.THINKING
            stream == "tool" && toolName.contains(Regex("search|query|find")) -> AgentActivityKind.SEARCH
            stream == "tool" && toolName.contains(Regex("browser|web|fetch|open|url")) -> AgentActivityKind.WEB
            stream == "tool" && toolName.contains(Regex("file|read|write|edit|pdf|document")) -> AgentActivityKind.FILE
            stream == "tool" && toolName.contains(Regex("exec|shell|command|terminal|process")) -> AgentActivityKind.COMMAND
            stream == "tool" && toolName.contains("skill") -> AgentActivityKind.SKILL
            stream == "tool" -> AgentActivityKind.TOOL
            else -> return null
        }
        return GatewayConnectionEvent.AgentActivity(session, run, kind, phase, toolCallId, rawToolName.ifBlank { null })
    }

    private fun extractMessageText(message: JSONObject): String? {
        if (message.optString("role") != "user" && message.optString("role") != "assistant") return null
        return VisibleChatText.fromMessage(message).takeIf { it.isNotBlank() }
    }

    private fun normalizeSessionKey(wireSessionKey: String): String =
        if (wireSessionKey == "agent:main:main") sessionKey else wireSessionKey
}
