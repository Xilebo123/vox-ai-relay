package pl.marcin.openclaw.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GatewayProtocolTest {
    @Test fun parsesConnectChallenge() {
        val challenge = GatewayProtocol.parseChallenge(
            """{"type":"event","event":"connect.challenge","payload":{"nonce":"abc","ts":42}}""",
        )
        assertEquals(ConnectChallenge("abc", 42), challenge)
    }

    @Test fun buildsProtocolV4OperatorConnectFrame() {
        val frame = JSONObject(GatewayProtocol.connectRequest(
            requestId = "request-1",
            challenge = ConnectChallenge("nonce", 42),
            identity = DeviceIdentity("device", "public", "private"),
            authType = "token",
            authCredential = "secret",
            signer = { "signature" },
            signedAtMs = 100,
        ))
        assertEquals("req", frame.getString("type"))
        assertEquals("connect", frame.getString("method"))
        val params = frame.getJSONObject("params")
        assertEquals(4, params.getInt("minProtocol"))
        assertEquals(4, params.getInt("maxProtocol"))
        assertEquals("operator", params.getString("role"))
        assertEquals("openclaw-android", params.getJSONObject("client").getString("id"))
        assertEquals("nonce", params.getJSONObject("device").getString("nonce"))
        assertEquals("signature", params.getJSONObject("device").getString("signature"))
        assertNotNull(params.getJSONObject("auth").getString("token"))
    }
}
