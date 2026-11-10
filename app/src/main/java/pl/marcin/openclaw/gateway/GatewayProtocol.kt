package pl.marcin.openclaw.gateway

import org.json.JSONArray
import org.json.JSONObject

data class ConnectChallenge(val nonce: String, val issuedAtMs: Long)

object GatewayProtocol {
    const val VERSION = 4
    val scopes = listOf("operator.read", "operator.write")

    fun parseChallenge(text: String): ConnectChallenge? = runCatching {
        val frame = JSONObject(text)
        if (frame.optString("type") != "event" || frame.optString("event") != "connect.challenge") return null
        val payload = frame.getJSONObject("payload")
        ConnectChallenge(payload.getString("nonce"), payload.getLong("ts"))
    }.getOrNull()

    fun connectRequest(
        requestId: String,
        challenge: ConnectChallenge,
        identity: DeviceIdentity,
        authType: String,
        authCredential: String?,
        signer: (String) -> String,
        signedAtMs: Long,
    ): String {
        val payload = DeviceAuthPayload.buildV3(
            deviceId = identity.deviceId,
            clientId = "openclaw-android",
            clientMode = "ui",
            role = "operator",
            scopes = scopes,
            signedAtMs = signedAtMs,
            token = if (authType == "password") null else authCredential,
            nonce = challenge.nonce,
            platform = "android",
            deviceFamily = "phone",
        )
        val auth = JSONObject()
        if (!authCredential.isNullOrBlank()) auth.put(authType, authCredential)
        val params = JSONObject()
            .put("minProtocol", VERSION)
            .put("maxProtocol", VERSION)
            .put("client", JSONObject()
                .put("id", "openclaw-android")
                .put("version", pl.marcin.openclaw.BuildConfig.VERSION_NAME)
                .put("platform", "android")
                .put("deviceFamily", "phone")
                .put("mode", "ui"))
            .put("role", "operator")
            .put("scopes", JSONArray(scopes))
            .put("caps", JSONArray(listOf("tool-events")))
            .put("commands", JSONArray())
            .put("permissions", JSONObject())
            .put("auth", auth)
            .put("locale", "pl-PL")
            .put("userAgent", "openclaw-android/${pl.marcin.openclaw.BuildConfig.VERSION_NAME}")
            .put("device", JSONObject()
                .put("id", identity.deviceId)
                .put("publicKey", identity.publicKey)
                .put("signature", signer(payload))
                .put("signedAt", signedAtMs)
                .put("nonce", challenge.nonce))
        return JSONObject().put("type", "req").put("id", requestId)
            .put("method", "connect").put("params", params).toString()
    }

    fun nodeConnectRequest(
        requestId: String, challenge: ConnectChallenge, identity: DeviceIdentity,
        authType: String, authCredential: String?, signer: (String) -> String, signedAtMs: Long,
    ): String {
        val nodeScopes = emptyList<String>()
        val payload = DeviceAuthPayload.buildV3(identity.deviceId, "openclaw-android", "node", "node",
            nodeScopes, signedAtMs, if (authType == "password") null else authCredential,
            challenge.nonce, "android", "phone")
        val auth = JSONObject().apply { if (!authCredential.isNullOrBlank()) put(authType, authCredential) }
        val params = JSONObject()
            .put("minProtocol", VERSION).put("maxProtocol", VERSION)
            .put("client", JSONObject().put("id", "openclaw-android").put("version", pl.marcin.openclaw.BuildConfig.VERSION_NAME)
                .put("platform", "android").put("deviceFamily", "phone").put("mode", "node"))
            .put("role", "node").put("scopes", JSONArray(nodeScopes))
            .put("caps", JSONArray(listOf("notifications", "system")))
            .put("commands", JSONArray(listOf("system.notify"))).put("permissions", JSONObject())
            .put("auth", auth).put("locale", java.util.Locale.getDefault().toLanguageTag())
            .put("userAgent", "openclaw-android/${pl.marcin.openclaw.BuildConfig.VERSION_NAME}")
            .put("device", JSONObject().put("id", identity.deviceId).put("publicKey", identity.publicKey)
                .put("signature", signer(payload)).put("signedAt", signedAtMs).put("nonce", challenge.nonce))
        return JSONObject().put("type", "req").put("id", requestId).put("method", "connect").put("params", params).toString()
    }
}
