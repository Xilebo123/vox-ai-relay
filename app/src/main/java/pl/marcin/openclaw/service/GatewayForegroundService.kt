package pl.marcin.openclaw.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import pl.marcin.openclaw.OpenClawApplication
import pl.marcin.openclaw.R
import pl.marcin.openclaw.ui.MainActivity
import pl.marcin.openclaw.gateway.GatewayConnectionEvent
import pl.marcin.openclaw.notification.AlertNotifier
import pl.marcin.openclaw.notification.StopAlarmReceiver
import pl.marcin.openclaw.hermes.HermesNtfySubscriber
import pl.marcin.openclaw.storage.AppPreferences

class GatewayForegroundService : Service() {
    private lateinit var connectivity: ConnectivityManager
    private var vpnAvailable = false
    private var lastStatusText: String = ""
    private lateinit var hermesPush: HermesNtfySubscriber
    private val gatewayListener: (GatewayConnectionEvent) -> Unit = { event ->
        when (event) {
            is GatewayConnectionEvent.Ready -> updateNotification(getString(R.string.background_connected))
            GatewayConnectionEvent.Connecting -> updateNotification(getString(R.string.background_connecting))
            GatewayConnectionEvent.Disconnected, is GatewayConnectionEvent.Error -> updateNotification(
                getString(if (vpnAvailable) R.string.background_reconnecting else R.string.background_vpn_required),
            )
            else -> Unit
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshVpnState()
            (application as OpenClawApplication).connectConfiguredGateway()
            hermesPush.start(AppPreferences(this@GatewayForegroundService).loadHermesPush())
        }
        override fun onLost(network: Network) = refreshVpnState()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refreshVpnState()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        connectivity = getSystemService(ConnectivityManager::class.java)
        hermesPush = HermesNtfySubscriber(this)
        vpnAvailable = isVpnActive(connectivity)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        lastStatusText = getString(R.string.gateway_service_text)
        val notification = buildNotification(lastStatusText, openApp)
        startForeground(NOTIFICATION_ID, notification)
        (application as OpenClawApplication).apply {
            addGatewayListener(gatewayListener)
            connectConfiguredGateway()
        }
        connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback)
        hermesPush.start(AppPreferences(this).loadHermesPush())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_ALARM) updateNotification(lastStatusText)
        (application as OpenClawApplication).connectConfiguredGateway()
        hermesPush.start(AppPreferences(this).loadHermesPush())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        (application as OpenClawApplication).removeGatewayListener(gatewayListener)
        hermesPush.stop()
        super.onDestroy()
    }

    private fun refreshVpnState() {
        vpnAvailable = isVpnActive(connectivity)
        if (!vpnAvailable) updateNotification(getString(R.string.background_vpn_required))
    }

    private fun updateNotification(text: String) {
        lastStatusText = text
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, openApp))
    }

    private fun buildNotification(text: String, openApp: PendingIntent): android.app.Notification {
        val alarmActive = AlertNotifier.isAlarmActive(this)
        val builder = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(if (alarmActive) AlertNotifier.alarmTitle(this) else getString(R.string.gateway_service_title))
            .setContentText(if (alarmActive) AlertNotifier.alarmBody(this).ifBlank { getString(R.string.urgent_alarm_active) } else text)
            .setContentIntent(openApp)
            .setOngoing(true)
        if (alarmActive) {
            builder.setStyle(android.app.Notification.BigTextStyle().bigText(AlertNotifier.alarmBody(this)))
            val stop = PendingIntent.getBroadcast(this, 7002, Intent(this, StopAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.app.Notification.Action.Builder(android.R.drawable.ic_media_pause, getString(R.string.stop_alarm), stop).build())
        }
        return builder.build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.gateway_service_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "openclaw_gateway"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_REFRESH_ALARM = "pl.marcin.openclaw.REFRESH_ALARM_CONTROLS"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GatewayForegroundService::class.java))
        }

        fun refreshAlarmControls(context: Context) {
            context.startForegroundService(Intent(context, GatewayForegroundService::class.java).setAction(ACTION_REFRESH_ALARM))
        }

        fun isVpnActive(connectivity: ConnectivityManager): Boolean = connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }
}
