package pl.marcin.openclaw.notification

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class StoredAlert(
    val id: Int,
    val title: String,
    val body: String,
    val urgent: Boolean,
    val receivedAt: Long,
    val unread: Boolean,
)

class AlertMessageStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun add(id: Int, title: String, body: String, urgent: Boolean) {
        val updated = listOf(StoredAlert(id, title, body, urgent, System.currentTimeMillis(), true)) + load()
        save(updated.take(MAX_MESSAGES))
    }

    @Synchronized
    fun load(): List<StoredAlert> = runCatching {
        val array = JSONArray(prefs.getString(KEY_MESSAGES, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(StoredAlert(
                    id = item.getInt("id"),
                    title = item.optString("title"),
                    body = item.optString("body"),
                    urgent = item.optBoolean("urgent"),
                    receivedAt = item.optLong("receivedAt"),
                    unread = item.optBoolean("unread", true),
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun unreadCount(): Int = load().count(StoredAlert::unread)

    @Synchronized
    fun markAllRead() = save(load().map { it.copy(unread = false) })

    private fun save(messages: List<StoredAlert>) {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(JSONObject()
                .put("id", message.id).put("title", message.title).put("body", message.body)
                .put("urgent", message.urgent).put("receivedAt", message.receivedAt).put("unread", message.unread))
        }
        prefs.edit().putString(KEY_MESSAGES, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "openclaw_alert_inbox"
        private const val KEY_MESSAGES = "messages"
        private const val MAX_MESSAGES = 50
    }
}
