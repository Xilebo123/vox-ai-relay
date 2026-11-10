package pl.marcin.openclaw.ui

import android.app.AlertDialog
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.net.ConnectivityManager
import android.net.Uri
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import pl.marcin.openclaw.BuildConfig
import pl.marcin.openclaw.R
import pl.marcin.openclaw.storage.*
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    private var tts: TextToSpeech? = null
    private val languageTags = mutableListOf<String>()
    private lateinit var languageSpinner: Spinner
    private lateinit var manualLanguage: EditText
    private val notificationStatusHandler = Handler(Looper.getMainLooper())
    private val notificationStatusUpdater = object : Runnable {
        override fun run() {
            refreshNotificationNodeStatus()
            notificationStatusHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_settings)
        val preferences = AppPreferences(this); val existing = preferences.loadGateway(); val hermes = preferences.loadHermes(); val hermesPush = preferences.loadHermesPush(); val voice = preferences.loadVoice(); val appearance = preferences.loadAppearance(); val conversation = preferences.loadConversationSettings(); val notifications = preferences.loadNotifications()
        val url = findViewById<EditText>(R.id.gatewayUrlInput).apply { setText(existing.url) }
        val credential = findViewById<EditText>(R.id.bootstrapCredentialInput)
        val authType = findViewById<Spinner>(R.id.authTypeSpinner).apply { adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, listOf(getString(R.string.auth_token), getString(R.string.auth_password))); setSelection(if (existing.authType == BootstrapAuthType.TOKEN) 0 else 1) }
        val allowTls = findViewById<Switch>(R.id.allowUntrustedTlsSwitch).apply { isChecked = BuildConfig.DEBUG && existing.allowUntrustedTls; isEnabled = BuildConfig.DEBUG }
        val hermesUrl = findViewById<EditText>(R.id.hermesUrlInput).apply { setText(hermes.url) }
        val hermesApiKey = findViewById<EditText>(R.id.hermesApiKeyInput)
        val hermesNtfyServer = findViewById<EditText>(R.id.hermesNtfyServerInput).apply { setText(hermesPush.serverUrl) }
        val hermesNtfyTopic = findViewById<EditText>(R.id.hermesNtfyTopicInput).apply { setText(hermesPush.topic) }
        val hermesNtfyToken = findViewById<EditText>(R.id.hermesNtfyTokenInput)
        findViewById<TextView>(R.id.untrustedTlsWarning).visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        val automaticTts = findViewById<Switch>(R.id.automaticTtsSwitch).apply { isChecked = voice.automaticTts }
        val preferLocal = findViewById<Switch>(R.id.preferLocalSttSwitch).apply { isChecked = voice.preferLocalStt }
        val allowNetwork = findViewById<Switch>(R.id.allowNetworkSttSwitch).apply { isChecked = voice.allowNetworkStt }
        val notificationSwitch = findViewById<Switch>(R.id.notificationSwitch).apply { isChecked = notifications.enabled }
        val urgentSwitch = findViewById<Switch>(R.id.urgentNotificationSwitch).apply { isChecked = notifications.urgentEnabled }
        notificationSwitch.setOnCheckedChangeListener { _, enabled ->
            preferences.saveNotifications(NotificationSettings(enabled, urgentSwitch.isChecked))
        }
        urgentSwitch.setOnCheckedChangeListener { _, enabled ->
            preferences.saveNotifications(NotificationSettings(notificationSwitch.isChecked, enabled))
            if (enabled && android.os.Build.VERSION.SDK_INT >= 34 &&
                !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.urgent_permission_title)
                    .setMessage(R.string.urgent_permission_help)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        safeStart(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        val timeoutValues = listOf(30, 60, 120, 360, 0)
        val timeoutSpinner = findViewById<Spinner>(R.id.conversationTimeoutSpinner).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, listOf(
                getString(R.string.timeout_30_minutes), getString(R.string.timeout_1_hour),
                getString(R.string.timeout_2_hours), getString(R.string.timeout_6_hours), getString(R.string.timeout_never),
            ))
            setSelection(timeoutValues.indexOf(conversation.inactivityMinutes).coerceAtLeast(0))
        }
        languageSpinner = findViewById(R.id.voiceLanguageSpinner)
        manualLanguage = findViewById<EditText>(R.id.manualLanguageTagInput).apply { if (voice.localeTag != "system") setText(voice.localeTag) }
        choice(findViewById(R.id.appLanguageSpinner), listOf(R.string.follow_system, R.string.language_english, R.string.language_polish), listOf("system", "en", "pl"), appearance.appLanguage)
        choice(findViewById(R.id.themeSpinner), listOf(R.string.follow_system, R.string.theme_light, R.string.theme_dark), listOf("system", "light", "dark"), appearance.theme)
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) populateLanguages(voice.localeTag) }
        findViewById<Button>(R.id.testVoiceButton).setOnClickListener { testVoice() }
        findViewById<Button>(R.id.voiceDiagnosticsButton).setOnClickListener { showDiagnostics() }
        findViewById<Button>(R.id.installVoiceButton).setOnClickListener { safeStart(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
        findViewById<Button>(R.id.ttsSettingsButton).setOnClickListener { safeStart(Intent("com.android.settings.TTS_SETTINGS")) }
        findViewById<Button>(R.id.helpGatewayButton).setOnClickListener { openHelp("gateway") }
        findViewById<Button>(R.id.helpVoiceButton).setOnClickListener { openHelp("voice") }
        findViewById<Button>(R.id.helpBcp47Button).setOnClickListener { openHelp("bcp47") }
        findViewById<Button>(R.id.helpNotificationsButton).setOnClickListener { openHelp("notifications") }
        findViewById<Button>(R.id.fullHelpButton).setOnClickListener { openHelp("top") }
        findViewById<Button>(R.id.pressAndTalkSettingsButton).setOnClickListener { requestAssistantRole() }
        findViewById<Button>(R.id.openTailscaleButton).setOnClickListener {
            safeStart(packageManager.getLaunchIntentForPackage("com.tailscale.ipn") ?: Intent(Settings.ACTION_VPN_SETTINGS))
        }
        findViewById<Button>(R.id.openVpnSettingsButton).setOnClickListener { safeStart(Intent(Settings.ACTION_VPN_SETTINGS)) }
        findViewById<Button>(R.id.openBatterySettingsButton).setOnClickListener { safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        val vpnActive = pl.marcin.openclaw.service.GatewayForegroundService.isVpnActive(getSystemService(ConnectivityManager::class.java))
        findViewById<TextView>(R.id.vpnStatusText).setText(if (vpnActive) R.string.vpn_active else R.string.vpn_inactive)
        refreshNotificationNodeStatus()
        findViewById<Button>(R.id.reconnectNotificationNodeButton).setOnClickListener {
            (application as pl.marcin.openclaw.OpenClawApplication).reconnectNotificationNode()
            findViewById<TextView>(R.id.notificationNodeStatus).setText(R.string.notification_node_reconnecting)
            Toast.makeText(this, R.string.notification_pairing_check_server, Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.saveButton).setOnClickListener {
            val selectedTag = manualLanguage.text.toString().trim().takeIf(String::isNotEmpty) ?: languageTags.getOrNull(languageSpinner.selectedItemPosition) ?: "system"
            if (selectedTag != "system" && Locale.forLanguageTag(selectedTag).language.isBlank()) { Toast.makeText(this, R.string.invalid_bcp47, Toast.LENGTH_LONG).show(); return@setOnClickListener }
            preferences.saveGateway(GatewaySettings(url.text.toString(), if (authType.selectedItemPosition == 0) BootstrapAuthType.TOKEN else BootstrapAuthType.PASSWORD, credential.text.toString().takeIf(String::isNotBlank), BuildConfig.DEBUG && allowTls.isChecked))
            preferences.saveHermes(HermesSettings(hermesUrl.text.toString(), hermesApiKey.text.toString().takeIf(String::isNotBlank)))
            preferences.saveHermesPush(HermesPushSettings(
                serverUrl = hermesNtfyServer.text.toString(),
                topic = hermesNtfyTopic.text.toString(),
                token = hermesNtfyToken.text.toString().takeIf(String::isNotBlank),
            ))
            preferences.saveVoice(VoiceSettings(automaticTts.isChecked, preferLocal.isChecked, allowNetwork.isChecked, voice.muted, selectedTag))
            preferences.saveConversationSettings(ConversationSettings(timeoutValues[timeoutSpinner.selectedItemPosition]))
            preferences.saveNotifications(NotificationSettings(notificationSwitch.isChecked, urgentSwitch.isChecked))
            val language = listOf("system", "en", "pl")[findViewById<Spinner>(R.id.appLanguageSpinner).selectedItemPosition]
            val theme = listOf("system", "light", "dark")[findViewById<Spinner>(R.id.themeSpinner).selectedItemPosition]
            preferences.saveAppearance(AppearanceSettings(language, theme))
            AppCompatDelegate.setDefaultNightMode(when (theme) { "light" -> AppCompatDelegate.MODE_NIGHT_NO; "dark" -> AppCompatDelegate.MODE_NIGHT_YES; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM })
            AppCompatDelegate.setApplicationLocales(if (language == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(language))
            pl.marcin.openclaw.service.GatewayForegroundService.start(this)
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show(); finish()
        }
    }
    private fun refreshNotificationNodeStatus() {
        val nodeState = (application as pl.marcin.openclaw.OpenClawApplication).notificationNodeEvent
        findViewById<TextView>(R.id.notificationNodeStatus).text = when (nodeState) {
            is pl.marcin.openclaw.gateway.NotificationNodeEvent.Connected -> getString(R.string.notification_node_connected_current)
            is pl.marcin.openclaw.gateway.NotificationNodeEvent.PairingRequired -> getString(R.string.notification_node_pairing, nodeState.requestId ?: "?")
            is pl.marcin.openclaw.gateway.NotificationNodeEvent.CapabilityPairingRequired -> getString(R.string.notification_capability_pairing, nodeState.requestId ?: "?")
            pl.marcin.openclaw.gateway.NotificationNodeEvent.CapabilityApproved -> getString(R.string.notification_capability_approved)
            is pl.marcin.openclaw.gateway.NotificationNodeEvent.Error -> getString(R.string.notification_node_error, nodeState.message)
            null -> getString(R.string.notification_node_waiting)
        }
    }
    private fun populateLanguages(selected: String) {
        val locales = (tts?.availableLanguages.orEmpty() + Locale.getAvailableLocales().filter { it.toLanguageTag() == selected }).distinctBy { it.toLanguageTag() }.sortedBy { it.getDisplayName(it).lowercase() }
        languageTags.clear(); languageTags += "system"; languageTags += locales.map(Locale::toLanguageTag)
        languageSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(getString(R.string.follow_system)) + locales.map { "${it.getDisplayName(it)} — ${it.toLanguageTag()}" })
        languageSpinner.setSelection(languageTags.indexOf(selected).coerceAtLeast(0))
    }
    private fun testVoice() { val tag = manualLanguage.text.toString().trim().ifBlank { languageTags.getOrNull(languageSpinner.selectedItemPosition) ?: "system" }; val result = tts?.setLanguage(if (tag == "system") Locale.getDefault() else Locale.forLanguageTag(tag)) ?: TextToSpeech.ERROR; if (result < 0) Toast.makeText(this, R.string.voice_not_available, Toast.LENGTH_LONG).show() else tts?.speak(getString(R.string.voice_test_sample), TextToSpeech.QUEUE_FLUSH, null, "test") }
    private fun showDiagnostics() { val speech = SpeechRecognizer.isRecognitionAvailable(this); val local = android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this); AlertDialog.Builder(this).setTitle(R.string.voice_diagnostics).setMessage(getString(R.string.voice_diagnostics_result, "✓", if (speech) "✓" else "✗", if (local) "✓" else "✗")).setPositiveButton(R.string.voice_input_settings) { _, _ -> safeStart(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }.setNegativeButton(android.R.string.ok, null).show() }
    private fun choice(spinner: Spinner, labels: List<Int>, values: List<String>, selected: String) { spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels.map(::getString)); spinner.setSelection(values.indexOf(selected).coerceAtLeast(0)) }
    private fun openHelp(topic: String) { startActivity(Intent(this, HelpActivity::class.java).putExtra("topic", topic)) }
    private fun safeStart(intent: Intent) { runCatching { startActivity(intent) }.onFailure { Toast.makeText(this, R.string.settings_screen_unavailable, Toast.LENGTH_LONG).show() } }
    private fun requestAssistantRole() { val manager = getSystemService(RoleManager::class.java); try { if (manager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true && !manager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) startActivity(manager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)) else safeStart(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) } catch (_: ActivityNotFoundException) { safeStart(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } }
    override fun onResume() { super.onResume(); notificationStatusHandler.post(notificationStatusUpdater) }
    override fun onPause() { notificationStatusHandler.removeCallbacks(notificationStatusUpdater); super.onPause() }
    override fun onDestroy() { tts?.stop(); tts?.shutdown(); super.onDestroy() }
}
