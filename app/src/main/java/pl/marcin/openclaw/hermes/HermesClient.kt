package pl.marcin.openclaw.hermes

import android.content.Context
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import pl.marcin.openclaw.BuildConfig
import pl.marcin.openclaw.chat.ChatMessage
import pl.marcin.openclaw.chat.ChatStreamEvent
import pl.marcin.openclaw.gateway.GatewayConnectionEvent
import pl.marcin.openclaw.gateway.AgentActivityKind
import pl.marcin.openclaw.storage.AppPreferences
import pl.marcin.openclaw.storage.HermesSettings
import pl.marcin.openclaw.attachment.ChatAttachment
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

class HermesClient(context: Context, private val listener: (GatewayConnectionEvent) -> Unit) {
    private val preferences = AppPreferences(context.applicationContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    @Volatile private var ready = false
    @Volatile private var activeCall: Call? = null
    @Volatile private var sessionKey = preferences.loadOrCreateHermesSessionKey()
    private val memoryKey = preferences.loadOrCreateHermesMemoryKey()

    fun connect(settings: HermesSettings) {
        activeCall?.cancel()
        ready = false
        val baseUrl = normalizedBaseUrl(settings.url) ?: run {
            listener(GatewayConnectionEvent.Error("INVALID_HERMES_URL", "Use an HTTPS URL or a private-network HTTP URL"))
            return
        }
        listener(GatewayConnectionEvent.Connecting)
        val request = Request.Builder().url("$baseUrl/health").apply {
            settings.apiKey?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
        }.build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) listener(GatewayConnectionEvent.Error("HERMES_UNAVAILABLE", e.message ?: "Hermes is unavailable"))
            }

            override fun onResponse(call: Call, response: Response) = response.use {
                if (!it.isSuccessful) {
                    listener(GatewayConnectionEvent.Error("HERMES_HTTP_${it.code}", "Hermes health check returned HTTP ${it.code}"))
                    return
                }
                ready = true
                listener(GatewayConnectionEvent.Ready("Hermes Agent", false))
                loadHistory(baseUrl, settings.apiKey)
            }
        })
    }

    fun sendChat(message: String, settings: HermesSettings, attachments: List<ChatAttachment> = emptyList()): String? {
        if (!ready || message.isBlank()) return null
        val baseUrl = normalizedBaseUrl(settings.url) ?: return null
        val apiKey = settings.apiKey?.takeIf(String::isNotBlank) ?: run {
            listener(GatewayConnectionEvent.Error("HERMES_KEY_REQUIRED", "Enter the Hermes API key in Settings"))
            return null
        }
        val runId = UUID.randomUUID().toString()
        val body = JSONObject()
            .put("model", "hermes-agent")
            .put("stream", true)
            .put("messages", JSONArray().put(HermesRequestBuilder.userMessage(
                message,
                attachments.flatMap(ChatAttachment::visionImages),
            )))
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            // Transcript continuity and long-term memory are separate Hermes concepts.
            .header(HERMES_SESSION_ID_HEADER, sessionKey)
            .header(HERMES_SESSION_KEY_HEADER, memoryKey)
            .header("Idempotency-Key", runId)
            .post(body)
            .build()
        val call = client.newCall(request)
        activeCall = call
        call.enqueue(StreamCallback(runId))
        return runId
    }

    fun abort() {
        activeCall?.cancel()
        activeCall = null
    }

    fun resetSession() {
        abort()
        sessionKey = "android:${UUID.randomUUID()}"
        preferences.saveHermesSessionKey(sessionKey)
        preferences.saveHermesLastActivityMs(System.currentTimeMillis())
        listener(GatewayConnectionEvent.SessionReset)
    }

    fun currentSessionKey(): String = sessionKey

    /** Returns true when the shared inactivity rule starts a fresh Hermes transcript. */
    fun prepareSessionForSend(preview: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastActivity = preferences.loadHermesLastActivityMs()
        val timeoutMinutes = preferences.loadConversationSettings().inactivityMinutes
        val rotated = lastActivity > 0L && timeoutMinutes > 0 &&
            nowMs - lastActivity >= timeoutMinutes * 60_000L
        if (rotated) {
            sessionKey = "android:${UUID.randomUUID()}"
            preferences.saveHermesSessionKey(sessionKey)
        }
        preferences.saveHermesLastActivityMs(nowMs)
        return rotated
    }

    fun markSessionActivity(nowMs: Long = System.currentTimeMillis()) =
        preferences.saveHermesLastActivityMs(nowMs)

    private fun loadHistory(baseUrl: String, apiKey: String?) {
        val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegment("api")
            ?.addPathSegment("sessions")
            ?.addPathSegment(sessionKey)
            ?.addPathSegment("messages")
            ?.addQueryParameter("limit", "40")
            ?.addQueryParameter("order", "latest")
            ?.addQueryParameter("inline_images", "false")
            ?.build() ?: return
        val request = Request.Builder().url(url).apply {
            apiKey?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
        }.build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit

            override fun onResponse(call: Call, response: Response) = response.use {
                // Hermes creates a new transcript lazily, so a pre-first-message 404 is normal.
                if (!it.isSuccessful) return
                val payload = it.body?.string()?.takeIf(String::isNotBlank) ?: return
                listener(GatewayConnectionEvent.History(HermesHistoryParser.fromPayload(payload)))
            }
        })
    }

    private inner class StreamCallback(private val runId: String) : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!call.isCanceled()) listener(GatewayConnectionEvent.Error("HERMES_NETWORK", e.message ?: "Hermes request failed"))
        }

        override fun onResponse(call: Call, response: Response) = response.use {
            if (!it.isSuccessful) {
                val detail = it.body?.string()?.take(500).orEmpty()
                listener(GatewayConnectionEvent.Error("HERMES_HTTP_${it.code}", detail.ifBlank { "Hermes returned HTTP ${it.code}" }))
                return
            }
            listener(GatewayConnectionEvent.SendAccepted(runId, sessionKey))
            listener(GatewayConnectionEvent.AgentActivity(
                sessionKey = sessionKey,
                runId = runId,
                kind = AgentActivityKind.WORKING,
                phase = "start",
            ))
            val source = it.body?.source() ?: run {
                listener(GatewayConnectionEvent.Error("HERMES_EMPTY", "Hermes returned an empty response"))
                return
            }
            var sequence = 0L
            var terminalSent = false
            val accumulatedContent = StringBuilder()
            var lastVisibleText = ""
            var toolActivityShown = false
            var currentSseEvent: String? = null
            var reasoningShown = false
            while (!source.exhausted() && !call.isCanceled()) {
                val line = source.readUtf8Line() ?: break
                val eventName = HermesSseParser.eventNameFromLine(line)
                if (eventName != null) {
                    currentSseEvent = eventName
                    continue
                }
                if (line.isBlank()) {
                    currentSseEvent = null
                    continue
                }
                val data = HermesSseParser.dataFromLine(line) ?: continue
                if (currentSseEvent == HermesSseParser.TOOL_PROGRESS_EVENT) {
                    HermesSseParser.toolProgressFromData(data)?.let { progress ->
                        val kind = activityKindForTool(progress.tool)
                        val phase = when (progress.status.lowercase()) {
                            "completed", "complete", "done", "tool.completed" -> "completed"
                            "failed", "error", "tool.failed" -> "error"
                            else -> "start"
                        }
                        listener(GatewayConnectionEvent.AgentActivity(
                            sessionKey = sessionKey,
                            runId = runId,
                            kind = kind,
                            phase = phase,
                            toolCallId = progress.callId,
                            toolName = progress.tool,
                        ))
                    }
                    continue
                }
                if (data == "[DONE]") {
                    listener(GatewayConnectionEvent.ChatEvent(ChatStreamEvent(sessionKey, runId, ++sequence, "final")))
                    terminalSent = true
                    break
                }
                val reasoning = HermesSseParser.reasoningFromData(data)
                if (!reasoning.isNullOrBlank() && !reasoningShown) {
                    reasoningShown = true
                    listener(GatewayConnectionEvent.AgentActivity(
                        sessionKey = sessionKey,
                        runId = runId,
                        kind = AgentActivityKind.THINKING,
                        phase = "start",
                        toolCallId = "hermes-reasoning",
                    ))
                }
                val chunk = HermesSseParser.contentFromData(data) ?: continue
                if (chunk.isNotEmpty()) {
                    if (reasoningShown) {
                        reasoningShown = false
                        listener(GatewayConnectionEvent.AgentActivity(
                            sessionKey = sessionKey,
                            runId = runId,
                            kind = AgentActivityKind.THINKING,
                            phase = "completed",
                            toolCallId = "hermes-reasoning",
                        ))
                    }
                    accumulatedContent.append(chunk)
                    if (!toolActivityShown && HermesVisibleText.containsToolCall(accumulatedContent.toString())) {
                        toolActivityShown = true
                        listener(GatewayConnectionEvent.AgentActivity(
                            sessionKey = sessionKey,
                            runId = runId,
                            kind = AgentActivityKind.TOOL,
                            phase = "start",
                            toolCallId = "hermes-tool-stream",
                        ))
                    }
                    val visibleText = HermesVisibleText.fromAccumulated(accumulatedContent.toString())
                    if (visibleText != lastVisibleText) {
                        lastVisibleText = visibleText
                        listener(GatewayConnectionEvent.ChatEvent(ChatStreamEvent(
                            sessionKey = sessionKey,
                            runId = runId,
                            seq = ++sequence,
                            state = "delta",
                            snapshotText = visibleText,
                        )))
                    }
                }
            }
            if (!terminalSent && !call.isCanceled()) {
                listener(GatewayConnectionEvent.ChatEvent(ChatStreamEvent(sessionKey, runId, ++sequence, "final")))
            }
            if (activeCall === call) activeCall = null
        }
    }

    private fun normalizedBaseUrl(raw: String): String? {
        val value = raw.trim().trimEnd('/')
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (scheme == "https") return value
        if (scheme != "http" || !BuildConfig.DEBUG || !isPrivateHost(host)) return null
        return value
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
        val octets = host.split('.').mapNotNull(String::toIntOrNull)
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            (octets[0] == 100 && octets[1] in 64..127) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    private fun activityKindForTool(name: String): AgentActivityKind {
        val normalized = name.lowercase()
        return when {
            normalized == "_thinking" || "reason" in normalized -> AgentActivityKind.THINKING
            "search" in normalized -> AgentActivityKind.SEARCH
            "browser" in normalized || "web" in normalized || "http" in normalized -> AgentActivityKind.WEB
            "file" in normalized || "read" in normalized || "write" in normalized -> AgentActivityKind.FILE
            "terminal" in normalized || "shell" in normalized || "exec" in normalized || "code" in normalized -> AgentActivityKind.COMMAND
            "skill" in normalized -> AgentActivityKind.SKILL
            else -> AgentActivityKind.TOOL
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val HERMES_SESSION_ID_HEADER = "X-Hermes-Session-Id"
        const val HERMES_SESSION_KEY_HEADER = "X-Hermes-Session-Key"
    }
}

object HermesHistoryParser {
    fun fromPayload(payload: String): List<ChatMessage> = runCatching {
        val root = JSONObject(payload)
        val items = root.optJSONArray("messages")
            ?: root.optJSONArray("data")
            ?: root.optJSONObject("session")?.optJSONArray("messages")
            ?: return@runCatching emptyList()
        buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                if (item.optString("display_kind") == "hidden") continue
                val role = item.optString("role")
                if (role != "user" && role != "assistant") continue
                val rawText = messageText(item.opt("content"))
                val text = if (role == "assistant") HermesVisibleText.fromAccumulated(rawText) else rawText
                if (text.isNotBlank()) {
                    add(ChatMessage(role, text, item.optString("id").takeIf(String::isNotBlank)))
                }
            }
        }.takeLast(40)
    }.getOrDefault(emptyList())

    private fun messageText(content: Any?): String = when (content) {
        is String -> content
        is JSONObject -> content.optString("text")
        is JSONArray -> buildString {
            for (index in 0 until content.length()) {
                val part = content.optJSONObject(index) ?: continue
                val type = part.optString("type")
                if (type in setOf("text", "input_text", "output_text") || type.isBlank()) {
                    val text = part.optString("text")
                    if (text.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(text)
                    }
                }
            }
        }
        else -> ""
    }
}

object HermesSseParser {
    const val TOOL_PROGRESS_EVENT = "hermes.tool.progress"

    data class ToolProgress(val tool: String, val callId: String, val status: String)

    fun eventNameFromLine(line: String): String? = line
        .takeIf { it.startsWith("event:") }
        ?.removePrefix("event:")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    fun dataFromLine(line: String): String? = line
        .takeIf { it.startsWith("data:") }
        ?.removePrefix("data:")
        ?.trimStart()

    fun contentFromData(data: String): String? = runCatching {
        val root = JSONObject(data)
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return@runCatching null
        (choice.optJSONObject("delta")?.opt("content") as? String)
            ?: (choice.optJSONObject("message")?.opt("content") as? String)
    }.getOrNull()

    fun reasoningFromData(data: String): String? = runCatching {
        val choice = JSONObject(data).optJSONArray("choices")?.optJSONObject(0) ?: return@runCatching null
        choice.optJSONObject("delta")?.opt("reasoning_content") as? String
    }.getOrNull()

    fun toolProgressFromData(data: String): ToolProgress? = runCatching {
        val root = JSONObject(data)
        val tool = sequenceOf("tool", "tool_name", "name")
            .map { root.optString(it) }.firstOrNull(String::isNotBlank) ?: return@runCatching null
        val callId = sequenceOf("toolCallId", "tool_call_id", "call_id")
            .map { root.optString(it) }.firstOrNull(String::isNotBlank)
            ?: "hermes:$tool"
        val status = sequenceOf("status", "event_type", "type")
            .map { root.optString(it) }.firstOrNull(String::isNotBlank) ?: "running"
        ToolProgress(tool, callId, status)
    }.getOrNull()
}
