package pl.marcin.openclaw.gateway

object DeviceAuthPayload {
    fun buildV3(
        deviceId: String,
        clientId: String,
        clientMode: String,
        role: String,
        scopes: List<String>,
        signedAtMs: Long,
        token: String?,
        nonce: String,
        platform: String?,
        deviceFamily: String?,
    ): String = listOf(
        "v3", deviceId, clientId, clientMode, role, scopes.joinToString(","),
        signedAtMs.toString(), token.orEmpty(), nonce,
        normalizeMetadataField(platform), normalizeMetadataField(deviceFamily),
    ).joinToString("|")

    internal fun normalizeMetadataField(value: String?): String = buildString {
        for (char in value?.trim().orEmpty()) {
            append(if (char in 'A'..'Z') (char.code + 32).toChar() else char)
        }
    }
}

