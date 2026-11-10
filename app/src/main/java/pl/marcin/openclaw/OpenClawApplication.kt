package pl.marcin.openclaw

import android.app.Application
import pl.marcin.openclaw.gateway.GatewayClient
import pl.marcin.openclaw.gateway.GatewayConnectionEvent
import pl.marcin.openclaw.gateway.NotificationNodeClient
import pl.marcin.openclaw.gateway.NotificationNodeEvent
import pl.marcin.openclaw.storage.AppPreferences
import pl.marcin.openclaw.storage.GatewaySettings
import java.util.concurrent.CopyOnWriteArraySet
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class OpenClawApplication : Application() {
    private val listeners = CopyOnWriteArraySet<(GatewayConnectionEvent) -> Unit>()
    private var latestConnectionEvent: GatewayConnectionEvent? = null
    private var latestHistory: GatewayConnectionEvent.History? = null
    @Volatile private var connectionStarted = false

    lateinit var gatewayClient: GatewayClient
        private set
    lateinit var notificationNodeClient: NotificationNodeClient
        private set
    @Volatile var notificationNodeEvent: NotificationNodeEvent? = null
        private set

    override fun onCreate() {
        super.onCreate()
        val appearance = AppPreferences(this).loadAppearance()
        AppCompatDelegate.setDefaultNightMode(when (appearance.theme) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
        AppCompatDelegate.setApplicationLocales(
            if (appearance.appLanguage == "system") LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(appearance.appLanguage)
        )
        gatewayClient = GatewayClient(this, ::onGatewayEvent)
        notificationNodeClient = NotificationNodeClient(this) { notificationNodeEvent = it }
    }

    fun addGatewayListener(listener: (GatewayConnectionEvent) -> Unit) {
        listeners += listener
        latestConnectionEvent?.let(listener)
        latestHistory?.let(listener)
    }

    fun removeGatewayListener(listener: (GatewayConnectionEvent) -> Unit) {
        listeners -= listener
    }

    @Synchronized
    fun connectConfiguredGateway() {
        if (connectionStarted) return
        val settings = AppPreferences(this).loadGateway()
        if (settings.url.isBlank()) return
        connectionStarted = true
        gatewayClient.connect(settings)
        notificationNodeClient.connect(settings)
    }

    @Synchronized
    fun connectGateway(settings: GatewaySettings, force: Boolean) {
        if (connectionStarted && !force) return
        connectionStarted = true
        gatewayClient.connect(settings)
        notificationNodeClient.connect(settings)
    }

    fun reconnectNotificationNode() {
        val settings = AppPreferences(this).loadGateway()
        if (settings.url.isNotBlank()) notificationNodeClient.reconnectForCapabilityPairing(settings)
    }

    private fun onGatewayEvent(event: GatewayConnectionEvent) {
        when (event) {
            is GatewayConnectionEvent.History -> latestHistory = event
            is GatewayConnectionEvent.Ready,
            GatewayConnectionEvent.Connecting,
            is GatewayConnectionEvent.PairingRequired,
            is GatewayConnectionEvent.Error,
            GatewayConnectionEvent.Disconnected -> latestConnectionEvent = event
            GatewayConnectionEvent.SessionReset -> latestHistory = GatewayConnectionEvent.History(emptyList())
            else -> Unit
        }
        listeners.forEach { it(event) }
    }
}
