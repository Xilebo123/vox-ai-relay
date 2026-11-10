package pl.marcin.openclaw.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import pl.marcin.openclaw.backend.BackendTarget
import java.util.UUID

enum class BootstrapAuthType { TOKEN, PASSWORD }

data class GatewaySettings(
    val url: String,
    val authType: BootstrapAuthType,
    val credential: String?,
    val allowUntrustedTls: Boolean = false,
)

data class HermesSettings(
    val url: String,
    val apiKey: String?,
)

data class HermesPushSettings(
    val serverUrl: String = "https://ntfy.sh",
    val topic: String = "",
    val token: String? = null,
)

data class VoiceSettings(
    val automaticTts: Boolean = true,
    val preferLocalStt: Boolean = true,
    val allowNetworkStt: Boolean = false,
    val muted: Boolean = false,
    val localeTag: String = "system",
)

data class AppearanceSettings(val appLanguage: String = "system", val theme: String = "system")
data class ConversationSettings(val inactivityMinutes: Int = 60)
data class NotificationSettings(val enabled: Boolean = true, val urgentEnabled: Boolean = false)
data class RecentChatSession(val key: String, val updatedAtMs: Long, val preview: String)

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
    private val secrets = SecureCredentialStore(context)

    fun loadGateway(): GatewaySettings = GatewaySettings(
        url = prefs.getString("gateway.url", "") ?: "",
        authType = runCatching {
            BootstrapAuthType.valueOf(prefs.getString("gateway.authType", "TOKEN")!!)
        }.getOrDefault(BootstrapAuthType.TOKEN),
        credential = secrets.get("gateway.bootstrapCredential"),
        allowUntrustedTls = prefs.getBoolean("gateway.allowUntrustedTls", false),
    )

    fun saveGateway(settings: GatewaySettings) {
        prefs.edit()
            .putString("gateway.url", settings.url.trim())
            .putString("gateway.authType", settings.authType.name)
            .putBoolean("gateway.allowUntrustedTls", settings.allowUntrustedTls)
            .apply()
        if (settings.credential != null) secrets.put("gateway.bootstrapCredential", settings.credential)
    }

    fun loadHermes(): HermesSettings = HermesSettings(
        url = prefs.getString("hermes.url", "http://100.90.77.115:8642") ?: "",
        apiKey = secrets.get("hermes.apiKey"),
    )

    fun saveHermes(settings: HermesSettings) {
        prefs.edit().putString("hermes.url", settings.url.trim().trimEnd('/')).apply()
        if (settings.apiKey != null) secrets.put("hermes.apiKey", settings.apiKey)
    }

    fun loadHermesPush(): HermesPushSettings = HermesPushSettings(
        serverUrl = prefs.getString("hermes.ntfyServerUrl", "https://ntfy.sh") ?: "https://ntfy.sh",
        topic = secrets.get("hermes.ntfyTopic").orEmpty(),
        token = secrets.get("hermes.ntfyToken"),
    )

    fun saveHermesPush(settings: HermesPushSettings) {
        prefs.edit().putString("hermes.ntfyServerUrl", settings.serverUrl.trim().trimEnd('/')).apply()
        secrets.put("hermes.ntfyTopic", settings.topic.trim())
        if (settings.token != null) secrets.put("hermes.ntfyToken", settings.token)
    }

    fun loadHermesPushLastId(): String? = prefs.getString("hermes.ntfyLastId", null)

    fun saveHermesPushLastId(id: String) {
        prefs.edit().putString("hermes.ntfyLastId", id).apply()
    }

    fun loadBackendTarget(): BackendTarget = runCatching {
        BackendTarget.valueOf(prefs.getString("backend.target", BackendTarget.OPENCLAW.name)!!)
    }.getOrDefault(BackendTarget.OPENCLAW)

    fun saveBackendTarget(target: BackendTarget) {
        prefs.edit().putString("backend.target", target.name).apply()
    }

    fun loadOrCreateHermesSessionKey(): String {
        prefs.getString("hermes.sessionKey", null)?.takeIf(String::isNotBlank)?.let { return it }
        return "android:${UUID.randomUUID()}".also(::saveHermesSessionKey)
    }

    fun saveHermesSessionKey(sessionKey: String) {
        prefs.edit().putString("hermes.sessionKey", sessionKey).apply()
    }

    /** Stable Hermes long-term-memory identity; it does not rotate with a transcript. */
    fun loadOrCreateHermesMemoryKey(): String {
        prefs.getString("hermes.memoryKey", null)?.takeIf(String::isNotBlank)?.let { return it }
        val migrated = prefs.getString("hermes.sessionKey", null)?.takeIf(String::isNotBlank)
            ?: "android-user:${UUID.randomUUID()}"
        prefs.edit().putString("hermes.memoryKey", migrated).apply()
        return migrated
    }

    fun loadHermesLastActivityMs(): Long = prefs.getLong("hermes.lastActivityMs", 0L)

    fun saveHermesLastActivityMs(value: Long) {
        prefs.edit().putLong("hermes.lastActivityMs", value).apply()
    }

    fun saveDeviceToken(gatewayId: String, token: String) = secrets.put("deviceToken.$gatewayId", token)
    fun loadDeviceToken(gatewayId: String): String? = secrets.get("deviceToken.$gatewayId")
    fun saveNodeDeviceToken(gatewayId: String, token: String) = secrets.put("nodeDeviceToken.$gatewayId", token)
    fun loadNodeDeviceToken(gatewayId: String): String? = secrets.get("nodeDeviceToken.$gatewayId")

    fun loadNotifications(): NotificationSettings = NotificationSettings(
        enabled = prefs.getBoolean("notifications.enabled", true),
        urgentEnabled = prefs.getBoolean("notifications.urgentEnabled", false),
    )

    fun saveNotifications(settings: NotificationSettings) {
        prefs.edit().putBoolean("notifications.enabled", settings.enabled)
            .putBoolean("notifications.urgentEnabled", settings.urgentEnabled).apply()
    }

    fun loadChatSessionKey(): String =
        prefs.getString("chat.sessionKey", "main")?.takeIf { it.isNotBlank() } ?: "main"

    fun saveChatSessionKey(sessionKey: String) {
        prefs.edit().putString("chat.sessionKey", sessionKey).apply()
    }

    fun loadConversationSettings(): ConversationSettings =
        ConversationSettings(prefs.getInt("chat.inactivityMinutes", 60))

    fun saveConversationSettings(settings: ConversationSettings) {
        prefs.edit().putInt("chat.inactivityMinutes", settings.inactivityMinutes).apply()
    }

    fun loadRecentSessions(): List<RecentChatSession> {
        val raw = prefs.getString("chat.recentSessions", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val key = item.optString("key").takeIf(String::isNotBlank) ?: continue
                    add(RecentChatSession(key, item.optLong("updatedAtMs"), item.optString("preview")))
                }
            }.sortedByDescending { it.updatedAtMs }.take(MAX_RECENT_SESSIONS)
        }.getOrDefault(emptyList())
    }

    fun touchSession(sessionKey: String, preview: String? = null, nowMs: Long = System.currentTimeMillis()) {
        val existing = loadRecentSessions()
        val previous = existing.firstOrNull { it.key == sessionKey }
        val cleanedPreview = preview?.replace(Regex("\\s+"), " ")?.trim()?.take(80)
            ?.takeIf(String::isNotBlank) ?: previous?.preview.orEmpty()
        val updated = (listOf(RecentChatSession(sessionKey, nowMs, cleanedPreview)) +
            existing.filterNot { it.key == sessionKey }).take(MAX_RECENT_SESSIONS)
        val json = JSONArray().apply {
            updated.forEach { session -> put(JSONObject()
                .put("key", session.key)
                .put("updatedAtMs", session.updatedAtMs)
                .put("preview", session.preview)) }
        }
        prefs.edit().putString("chat.recentSessions", json.toString()).apply()
    }

    fun isSessionExpired(sessionKey: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val timeoutMinutes = loadConversationSettings().inactivityMinutes
        if (timeoutMinutes <= 0) return false
        val lastActivity = loadRecentSessions().firstOrNull { it.key == sessionKey }?.updatedAtMs ?: return true
        return nowMs - lastActivity >= timeoutMinutes * 60_000L
    }

    fun loadVoice(): VoiceSettings = VoiceSettings(
        automaticTts = prefs.getBoolean("voice.automaticTts", true),
        preferLocalStt = prefs.getBoolean("voice.preferLocalStt", true),
        allowNetworkStt = prefs.getBoolean("voice.allowNetworkStt", false),
        muted = prefs.getBoolean("voice.muted", false),
        localeTag = prefs.getString("voice.localeTag", "system") ?: "system",
    )

    fun saveVoice(settings: VoiceSettings) {
        prefs.edit()
            .putBoolean("voice.automaticTts", settings.automaticTts)
            .putBoolean("voice.preferLocalStt", settings.preferLocalStt)
            .putBoolean("voice.allowNetworkStt", settings.allowNetworkStt)
            .putBoolean("voice.muted", settings.muted)
            .putString("voice.localeTag", settings.localeTag)
            .apply()
    }

    fun loadAppearance(): AppearanceSettings = AppearanceSettings(
        appLanguage = prefs.getString("appearance.language", "system") ?: "system",
        theme = prefs.getString("appearance.theme", "system") ?: "system",
    )

    fun saveAppearance(settings: AppearanceSettings) {
        prefs.edit().putString("appearance.language", settings.appLanguage).putString("appearance.theme", settings.theme).apply()
    }

    companion object { private const val MAX_RECENT_SESSIONS = 5 }
}
