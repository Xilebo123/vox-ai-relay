package pl.marcin.openclaw.notification

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import pl.marcin.openclaw.R
import pl.marcin.openclaw.storage.AppPreferences
import pl.marcin.openclaw.service.GatewayForegroundService
import pl.marcin.openclaw.ui.MainActivity

class AlertNotifier(private val context: Context) {
    fun show(title: String, body: String, priority: String?, sound: String?): Result<Unit> = runCatching {
        val settings = AppPreferences(context).loadNotifications()
        if (!settings.enabled) error("notifications disabled in OpenClaw")
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("notification permission missing")
        }
        val urgent = priority.equals("timeSensitive", true)
        if (urgent && !settings.urgentEnabled) error("urgent alerts disabled in OpenClaw")
        ensureChannels()
        val notificationId = if (urgent) URGENT_ID else nextId()
        AlertMessageStore(context).add(notificationId, title.take(120), body.take(4000), urgent)
        val open = PendingIntent.getActivity(context, notificationId,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ALERT_ID, notificationId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(context, 7002, Intent(context, StopAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val urgentScreen = PendingIntent.getActivity(context, 7003,
            Intent(context, UrgentAlertActivity::class.java).putExtra(UrgentAlertActivity.EXTRA_TITLE, title.take(120))
                .putExtra(UrgentAlertActivity.EXTRA_BODY, body.take(4000)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, if (urgent) URGENT_CHANNEL else NORMAL_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title.take(120)).setContentText(body.take(500))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(4000))).setContentIntent(open).setAutoCancel(!urgent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(if (urgent) 0 else NotificationCompat.DEFAULT_ALL)
        if (urgent) {
            builder.setOngoing(true).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(urgentScreen).setTimeoutAfter(60_000)
                .addAction(android.R.drawable.ic_media_pause, context.getString(R.string.stop_alarm), stop)
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()) {
                builder.setFullScreenIntent(urgentScreen, true)
            }
            saveAlarmState(context, true, title.take(120), body.take(4000))
            GatewayForegroundService.refreshAlarmControls(context)
            context.sendBroadcast(Intent(ACTION_ALARM_STATE_CHANGED).setPackage(context.packageName))
        }
        context.getSystemService(NotificationManager::class.java).notify(notificationId, builder.build())
        context.sendBroadcast(Intent(ACTION_INBOX_CHANGED).setPackage(context.packageName))
        if (urgent && !isSilent(sound)) startAlarm(context)
    }

    private fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(NORMAL_CHANNEL, context.getString(R.string.alert_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.alert_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setShowBadge(true)
        })
        manager.createNotificationChannel(NotificationChannel(URGENT_CHANNEL, context.getString(R.string.urgent_alert_channel), NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
    }

    private fun isSilent(sound: String?) = sound?.trim()?.lowercase() in setOf("none", "silent", "off", "false", "0")

    companion object {
        private const val NORMAL_CHANNEL = "openclaw_visible_alerts_v2"
        private const val URGENT_CHANNEL = "openclaw_urgent_alerts"
        private const val URGENT_ID = 7200
        private const val STATE_PREFS = "urgent_alarm_state"
        const val ACTION_ALARM_STATE_CHANGED = "pl.marcin.openclaw.ALARM_STATE_CHANGED"
        const val ACTION_INBOX_CHANGED = "pl.marcin.openclaw.INBOX_CHANGED"
        const val EXTRA_ALERT_ID = "openclaw.alert.id"
        private var ringtone: Ringtone? = null
        private var alarmGeneration = 0L
        private var serial = 7300
        private fun nextId() = synchronized(this) { ++serial }

        fun startAlarm(context: Context) {
            ringtone?.stop()
            val generation = ++alarmGeneration
            ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)).apply {
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
            android.os.Handler(context.mainLooper).postDelayed({
                if (generation == alarmGeneration) stopAlarm(context)
            }, 60_000)
        }

        fun stopAlarm(context: Context) {
            alarmGeneration += 1
            ringtone?.stop(); ringtone = null
            saveAlarmState(context, false, "", "")
            context.getSystemService(NotificationManager::class.java).cancel(URGENT_ID)
            GatewayForegroundService.refreshAlarmControls(context)
            context.sendBroadcast(Intent(ACTION_ALARM_STATE_CHANGED).setPackage(context.packageName))
        }

        fun isAlarmActive(context: Context): Boolean =
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getBoolean("active", false)

        fun alarmTitle(context: Context): String =
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getString("title", null)
                ?: context.getString(R.string.urgent_alert_title)

        fun alarmBody(context: Context): String =
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getString("body", "").orEmpty()

        private fun saveAlarmState(context: Context, active: Boolean, title: String, body: String) {
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("active", active).putString("title", title).putString("body", body).apply()
        }
    }
}
