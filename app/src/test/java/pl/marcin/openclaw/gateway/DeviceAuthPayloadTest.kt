package pl.marcin.openclaw.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAuthPayloadTest {
    @Test fun buildsCanonicalV3Payload() {
        assertEquals(
            "v3|device123|openclaw-android|ui|operator|operator.read,operator.write|123456|secret|nonce|android|phone",
            DeviceAuthPayload.buildV3(
                "device123", "openclaw-android", "ui", "operator",
                listOf("operator.read", "operator.write"), 123456, "secret", "nonce",
                "Android", "Phone",
            ),
        )
    }

    @Test fun normalizesOnlyAsciiMetadata() {
        assertEquals("android-Ż", DeviceAuthPayload.normalizeMetadataField(" Android-Ż "))
    }
}

