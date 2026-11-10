package pl.marcin.openclaw.hermes

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import pl.marcin.openclaw.BuildConfig
import pl.marcin.openclaw.notification.AlertNotifier
import pl.marcin.openclaw.storage.AppPreferences
import pl.marcin.openclaw.storage.HermesPushSettings
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Receives Hermes cron/background deliveries through the official ntfy platform. */
class HermesNtfySubscriber(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var desired: HermesPushSettings? = null
    private var activeCall: Call? = null
    private var generation = 0L
    private var reconnectAttempt = 0

    @Synchronized
    fun start(settings: HermesPushSettings) {
        val normalized = settings.copy(
            serverUrl = settings.serverUrl.trim().trimEnd('/'),
            topic = settings.topic.trim(),
        )
        if (normalized.topic.isBlank()) {
            stop()
            return
        }
        if (desired == normalized && activeCall != null) return
        desired = normalized
        generation += 1
        reconnectAttempt = 0
        handler.removeCallbacksAndMessages(null)
        activeCall?.cancel()
        open(normalized, generation)
    }

    @Synchronized
    fun stop() {
        desired = null
        generation += 1
        handler.removeCallbacksAndMessages(null)
        activeCall?.cancel()
        activeCall = null
    }

    private fun open(settings: HermesPushSettings, currentGeneration: Long) {
        val endpoint = subscriptionUrl(settings) ?: return
        val request = Request.Builder().url(endpoint).apply {
            settings.token?.takeIf(String::isNotBlank)?.let { token ->
                header("Authorization", if (':' in token) {
                    Credentials.basic(token.substringBefore(':'), token.substringAfter(':'))
                } else "Bearer $token")
            }
        }.build()
        val call = client.newCall(request)
        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) scheduleReconnect(currentGeneration)
            }

            override fun onResponse(call: Call, response: Response) = response.use {
                if (!it.isSuccessful) {
                    if (activeCall === call) activeCall = null
                    scheduleReconnect(currentGeneration, if (it.code == 401 || it.code == 403) 60_000L else null)
                    return
                }
                reconnectAttempt = 0
                val source = it.body?.source() ?: return
                while (currentGeneration == generation && !call.isCanceled() && !source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    handleLine(line)
                }
                if (activeCall === call) activeCall = null
                if (!call.isCanceled()) scheduleReconnect(currentGeneration)
            }
        })
    }

    private fun subscriptionUrl(settings: HermesPushSettings): String? {
        val base = settings.serverUrl.toHttpUrlOrNull() ?: return null
        if (base.scheme != "https" && !(BuildConfig.DEBUG && base.scheme == "http" && isPrivateHost(base.host))) return null
        return base.newBuilder()
            .addPathSegment(settings.topic)
            .addPathSegment("json")
            .apply { preferences.loadHermesPushLastId()?.takeIf(String::isNotBlank)?.let { addQueryParameter("since", it) } }
            .build()
            .toString()
    }

    private fun handleLine(line: String) {
        val event = runCatching { JSONObject(line) }.getOrNull() ?: return
        if (event.optString("event") != "message") return
        val id = event.optString("id").takeIf(String::isNotBlank) ?: return
        if (id == preferences.loadHermesPushLastId()) return
        preferences.saveHermesPushLastId(id)
        val body = event.optString("message").trim()
        if (body.isBlank()) return
        val title = event.optString("title").trim().ifBlank { "Hermes" }
        val urgentAllowed = preferences.loadNotifications().urgentEnabled
        val priority = if (urgentAllowed && event.optInt("priority", 3) >= 5) "timeSensitive" else null
        AlertNotifier(appContext).show(title, body, priority, null)
    }

    private fun scheduleReconnect(currentGeneration: Long, fixedDelay: Long? = null) {
        if (currentGeneration != generation || desired == null) return
        val delay = fixedDelay ?: (2_000L shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(60_000L)
        reconnectAttempt += 1
        handler.postDelayed({
            if (currentGeneration == generation) desired?.let { open(it, currentGeneration) }
        }, delay)
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
}
