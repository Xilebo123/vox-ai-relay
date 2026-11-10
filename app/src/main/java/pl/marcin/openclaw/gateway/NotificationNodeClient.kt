package pl.marcin.openclaw.gateway

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONObject
import pl.marcin.openclaw.BuildConfig
import pl.marcin.openclaw.notification.AlertNotifier
import pl.marcin.openclaw.storage.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed interface NotificationNodeEvent {
    data object Connected : NotificationNodeEvent
    data class PairingRequired(val requestId: String?) : NotificationNodeEvent
    data class CapabilityPairingRequired(val requestId: String?) : NotificationNodeEvent
    data object CapabilityApproved : NotificationNodeEvent
    data class Error(val message: String) : NotificationNodeEvent
}

/** Minimal second Gateway connection. It exposes only the official system.notify node command. */
class NotificationNodeClient(context: Context, private val listener: (NotificationNodeEvent) -> Unit) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)
    private val identities = DeviceIdentityStore(appContext)
    private val reconnect = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var generation = 0L
    private var desired: GatewaySettings? = null
    private var gatewayId: String? = null
    private var connectId: String? = null

    fun connect(settings: GatewaySettings) {
        if (settings.url.isBlank()) return
        desired = settings; generation++; socket?.cancel(); open(settings, generation)
    }

    fun close() { desired = null; generation++; reconnect.removeCallbacksAndMessages(null); socket?.close(1000, "closing"); socket = null }

    fun reconnectForCapabilityPairing(settings: GatewaySettings) = connect(settings)

    private fun open(settings: GatewaySettings, current: Long) {
        val url = settings.url.trim()
        gatewayId = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        val insecure = BuildConfig.DEBUG && settings.allowUntrustedTls && url.startsWith("wss://", true)
        socket = client(insecure).newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (current != generation) return
                GatewayProtocol.parseChallenge(text)?.let { sendConnect(webSocket, it, settings); return }
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (frame.optString("type") == "event" && frame.optString("event") == "node.invoke.request") {
                    handleInvoke(webSocket, frame.optJSONObject("payload")); return
                }
                if (frame.optString("type") == "event" && frame.optString("event") == "node.pair.requested") {
                    val payload = frame.optJSONObject("payload")
                    listener(NotificationNodeEvent.CapabilityPairingRequired(
                        payload?.optString("requestId")?.ifBlank { payload.optString("id").ifBlank { null } },
                    ))
                    return
                }
                if (frame.optString("type") == "event" && frame.optString("event") == "node.pair.resolved") {
                    val payload = frame.optJSONObject("payload")
                    if (payload?.optBoolean("approved") == true || payload?.optString("status") in setOf("approved", "paired")) {
                        listener(NotificationNodeEvent.CapabilityApproved)
                    }
                    return
                }
                if (frame.optString("type") == "res" && frame.optString("id") == connectId) handleConnect(frame)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (current == generation) schedule(current, t.message) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (current == generation) schedule(current, reason) }
        })
    }

    private fun sendConnect(webSocket: WebSocket, challenge: ConnectChallenge, settings: GatewaySettings) {
        val identity = identities.loadOrCreate()
        val stored = gatewayId?.let(preferences::loadNodeDeviceToken)
        val credential = stored ?: settings.credential
        val type = if (stored != null || settings.authType == BootstrapAuthType.TOKEN) "token" else "password"
        val id = UUID.randomUUID().toString().also { connectId = it }
        val now = System.currentTimeMillis()
        webSocket.send(GatewayProtocol.nodeConnectRequest(id, challenge, identity, type, credential, { identities.sign(it, identity) }, now))
    }

    private fun handleConnect(frame: JSONObject) {
        if (!frame.optBoolean("ok")) {
            val error = frame.optJSONObject("error"); val details = error?.optJSONObject("details")
            val code = details?.optString("code")?.ifBlank { null } ?: error?.optString("code")
            if (code == "PAIRING_REQUIRED") listener(NotificationNodeEvent.PairingRequired(details?.optString("requestId")?.ifBlank { null }))
            else listener(NotificationNodeEvent.Error(error?.optString("message") ?: "node connection rejected"))
            return
        }
        val payload = frame.optJSONObject("payload") ?: return
        payload.optJSONObject("auth")?.optString("deviceToken")?.takeIf(String::isNotBlank)?.let { token -> gatewayId?.let { preferences.saveNodeDeviceToken(it, token) } }
        listener(NotificationNodeEvent.Connected)
    }

    private fun handleInvoke(webSocket: WebSocket, payload: JSONObject?) {
        payload ?: return
        val id = payload.optString("id"); val nodeId = payload.optString("nodeId")
        if (id.isBlank() || nodeId.isBlank()) return
        val command = payload.optString("command")
        val params = payload.optString("paramsJSON").takeIf(String::isNotBlank)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: payload.optJSONObject("params")
        val result = if (command != "system.notify") Result.failure(IllegalArgumentException("command not supported")) else {
            val title = params?.optString("title")?.trim().orEmpty(); val body = params?.optString("body")?.trim().orEmpty()
            if (title.isBlank() && body.isBlank()) Result.failure(IllegalArgumentException("empty notification"))
            else AlertNotifier(appContext).show(title, body, params?.optString("priority"), params?.optString("sound"))
        }
        val response = JSONObject().put("type", "req").put("id", UUID.randomUUID().toString()).put("method", "node.invoke.result")
            .put("params", JSONObject().put("id", id).put("nodeId", nodeId).put("ok", result.isSuccess).apply {
                if (result.isFailure) put("error", JSONObject().put("code", if (result.exceptionOrNull() is SecurityException) "NOT_AUTHORIZED" else "INVALID_REQUEST")
                    .put("message", result.exceptionOrNull()?.message ?: "notification failed"))
            })
        webSocket.send(response.toString())
    }

    private fun schedule(current: Long, message: String?) {
        listener(NotificationNodeEvent.Error(message ?: "notification node disconnected"))
        reconnect.postDelayed({ if (generation == current) desired?.let { open(it, current) } }, 5_000)
    }

    private fun client(insecure: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
        if (!insecure) return builder.build()
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        return builder.sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { _, _ -> true }.build()
    }
}
