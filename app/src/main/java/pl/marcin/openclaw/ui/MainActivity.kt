package pl.marcin.openclaw.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.Manifest
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Bundle
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.FileProvider
import pl.marcin.openclaw.BuildConfig
import pl.marcin.openclaw.OpenClawApplication
import pl.marcin.openclaw.R
import pl.marcin.openclaw.chat.ActiveRun
import pl.marcin.openclaw.chat.ChatMessage
import pl.marcin.openclaw.chat.MessageAssembler
import pl.marcin.openclaw.chat.ConversationHistory
import pl.marcin.openclaw.gateway.GatewayClient
import pl.marcin.openclaw.gateway.GatewayConnectionEvent
import pl.marcin.openclaw.gateway.AgentActivityKind
import pl.marcin.openclaw.backend.BackendTarget
import pl.marcin.openclaw.hermes.HermesClient
import pl.marcin.openclaw.storage.AppPreferences
import pl.marcin.openclaw.voice.DeterministicSentenceSegmenter
import pl.marcin.openclaw.voice.SpokenTextSanitizer
import pl.marcin.openclaw.voice.StreamingSpeechPlanner
import java.util.Locale
import java.text.DateFormat
import java.util.Date
import pl.marcin.openclaw.service.GatewayForegroundService
import pl.marcin.openclaw.notification.AlertNotifier
import pl.marcin.openclaw.notification.AlertMessageStore
import pl.marcin.openclaw.notification.StoredAlert
import pl.marcin.openclaw.attachment.AttachmentLoader
import pl.marcin.openclaw.attachment.AttachmentPolicy
import pl.marcin.openclaw.attachment.AttachmentRules
import pl.marcin.openclaw.attachment.ChatAttachment
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var gatewayClient: GatewayClient
    private lateinit var openClawApplication: OpenClawApplication
    private val gatewayListener: (GatewayConnectionEvent) -> Unit = { event ->
        runOnUiThread { handleBackendEvent(BackendTarget.OPENCLAW, event) }
    }
    private val hermesListener: (GatewayConnectionEvent) -> Unit = { event ->
        runOnUiThread { handleBackendEvent(BackendTarget.HERMES, event) }
    }
    private lateinit var hermesClient: HermesClient
    private lateinit var connectionStatus: TextView
    private lateinit var pairingInstructions: TextView
    private lateinit var conversationText: TextView
    private lateinit var conversationScroll: ScrollView
    private lateinit var waitingStatus: LinearLayout
    private lateinit var waitingTime: TextView
    private lateinit var waitingIcon: ImageView
    private lateinit var activityIcons: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var microphoneButton: ImageButton
    private lateinit var muteButton: ImageButton
    private lateinit var repeatButton: ImageButton
    private lateinit var stopSpeechButton: ImageButton
    private lateinit var attachmentButton: ImageButton
    private lateinit var connectButton: Button
    private lateinit var stopAlarmButton: Button
    private lateinit var notificationInboxButton: TextView
    private lateinit var attachmentList: LinearLayout
    private lateinit var backendSelector: RadioGroup
    private val attachments = mutableListOf<ChatAttachment>()
    private val attachmentExecutor = Executors.newSingleThreadExecutor()
    private var attachmentJobs = 0
    private var cameraUri: Uri? = null
    private var waitingAnimator: ObjectAnimator? = null
    private var sentAttachmentNames: Set<String> = emptySet()
    private val choosePhotos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AttachmentRules.MAX_FILES)) { uris -> addUris(uris) }
    private val chooseFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> addUris(uris) }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraUri
        if (success && uri != null) addUris(listOf(uri))
    }
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false
    private var retriedWithNetwork = false
    private val sentenceSegmenter = DeterministicSentenceSegmenter()
    private val spokenTextSanitizer = SpokenTextSanitizer()
    private val speechPlanner = StreamingSpeechPlanner(sentenceSegmenter)
    private var speechGeneration = 0L
    private var utteranceSequence = 0L
    private var speechStoppedForRun = false
    private var ttsMuted = false
    private val backendMessages = mutableMapOf(
        BackendTarget.OPENCLAW to mutableListOf<ChatMessage>(),
        BackendTarget.HERMES to mutableListOf<ChatMessage>(),
    )
    private var messages = backendMessages.getValue(BackendTarget.OPENCLAW)
    private val assembler = MessageAssembler()
    private val handler = Handler(Looper.getMainLooper())
    private var activeRun: ActiveRun? = null
    private var ready = false
    private var selectedBackend = BackendTarget.OPENCLAW
    private var hasResumed = false
    private var waitingStartedAt = 0L
    private var streamEventCount = 0
    private var visibleUpdateCount = 0
    private val gatewayTrace = StringBuilder()
    private val visibleActivities = LinkedHashMap<String, AgentActivityKind>()
    private val seenActivityPhases = LinkedHashSet<String>()
    private val scrollToLatest = Runnable {
        val maximum = (conversationText.height - conversationScroll.height).coerceAtLeast(0)
        conversationScroll.scrollTo(0, maximum)
    }
    private val alarmStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateAlarmControls()
    }
    private val inboxReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateInboxButton()
    }

    private val waitingTicker = object : Runnable {
        override fun run() {
            if (waitingStartedAt == 0L) return
            val seconds = (SystemClock.elapsedRealtime() - waitingStartedAt) / 1000.0
            waitingTime.text = getString(R.string.elapsed_time, seconds)
            handler.postDelayed(this, 150)
        }
    }
    private val pausedSpeechFlush = Runnable {
        if (activeRun != null && !speechStoppedForRun) speakChunks(speechPlanner.flushAfterPause())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applySystemBarAndKeyboardInsets()
        bindViews()
        selectedBackend = AppPreferences(this).loadBackendTarget()
        messages = backendMessages.getValue(selectedBackend)
        configureBackends()
        configureBackendSelector()
        GatewayForegroundService.start(this)
        if (selectedBackend == BackendTarget.HERMES) connect()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        configureTextToSpeech()
        ttsMuted = AppPreferences(this).loadVoice().muted
        updateMuteButton()
        if (intent?.action == Intent.ACTION_ASSIST) handler.post { startVoiceInputWithBargeIn() }

        findViewById<TextView>(R.id.versionLabel).text = getString(
            R.string.version_format,
            BuildConfig.VERSION_NAME,
            if (BuildConfig.DEBUG) " • DEBUG" else "",
        )
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        notificationInboxButton.setOnClickListener { showNotificationInbox() }
        connectButton.setOnClickListener { connect() }
        findViewById<ImageButton>(R.id.moreButton).setOnClickListener { showMainMenu(it) }
        sendButton.setOnClickListener { sendMessage() }
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }
        messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateControls()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        conversationScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scheduleScrollToLatest()
        }
        microphoneButton.setOnClickListener {
            if (listening) {
                speechRecognizer?.stopListening()
                listening = false
                microphoneButton.setImageResource(R.drawable.ic_mic)
                microphoneButton.alpha = 1f
            } else {
                startVoiceInputWithBargeIn()
            }
        }
        muteButton.setOnClickListener { toggleTtsMute() }
        stopSpeechButton.setOnClickListener {
            val responseInterrupted = interruptActiveResponse()
            stopCurrentSpeech(silent = responseInterrupted)
        }
        repeatButton.setOnClickListener { repeatLastAssistantMessage() }
        attachmentButton.setOnClickListener { showAttachmentMenu() }
        stopAlarmButton.setOnClickListener { AlertNotifier.stopAlarm(this) }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            alarmStateReceiver,
            IntentFilter(AlertNotifier.ACTION_ALARM_STATE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            inboxReceiver,
            IntentFilter(AlertNotifier.ACTION_INBOX_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        updateAlarmControls()
        updateInboxButton()
        handler.post { showAlertFromIntent(intent) }
        updateControls()
    }

    private fun bindViews() {
        connectionStatus = findViewById(R.id.connectionStatus)
        pairingInstructions = findViewById(R.id.pairingInstructions)
        conversationText = findViewById(R.id.conversationText)
        conversationScroll = findViewById(R.id.conversationScroll)
        waitingStatus = findViewById(R.id.waitingStatus)
        waitingTime = findViewById(R.id.waitingTime)
        waitingIcon = findViewById(R.id.waitingIcon)
        activityIcons = findViewById(R.id.activityIcons)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        microphoneButton = findViewById(R.id.microphoneButton)
        muteButton = findViewById(R.id.muteButton)
        repeatButton = findViewById(R.id.repeatButton)
        stopSpeechButton = findViewById(R.id.stopSpeechButton)
        attachmentButton = findViewById(R.id.attachmentButton)
        attachmentList = findViewById(R.id.attachmentList)
        backendSelector = findViewById(R.id.backendSelector)
        connectButton = findViewById(R.id.connectButton)
        stopAlarmButton = findViewById(R.id.stopAlarmButton)
        notificationInboxButton = findViewById(R.id.notificationInboxButton)
        listOf(attachmentButton, microphoneButton, stopSpeechButton, muteButton, repeatButton, sendButton).forEach {
            it.tooltipText = it.contentDescription
        }
    }

    private fun updateAlarmControls() {
        stopAlarmButton.visibility = if (AlertNotifier.isAlarmActive(this)) View.VISIBLE else View.GONE
        if (stopAlarmButton.visibility == View.VISIBLE) {
            stopAlarmButton.text = getString(R.string.stop_alarm_with_title, AlertNotifier.alarmTitle(this))
        }
    }

    private fun updateInboxButton() {
        val unread = AlertMessageStore(this).unreadCount()
        notificationInboxButton.text = if (unread > 0) "🔔\n$unread" else "🔔"
        notificationInboxButton.contentDescription = resources.getQuantityString(R.plurals.notification_unread_count, unread, unread)
    }

    private fun showNotificationInbox() {
        val store = AlertMessageStore(this)
        val alerts = store.load()
        if (alerts.isEmpty()) {
            Toast.makeText(this, R.string.notification_inbox_empty, Toast.LENGTH_SHORT).show()
            return
        }
        store.markAllRead()
        updateInboxButton()
        val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = alerts.map { alert ->
            buildString {
                if (alert.unread) append("● ")
                if (alert.urgent) append("⚠ ")
                append(alert.title.ifBlank { getString(R.string.notification_without_title) })
                append("\n").append(formatter.format(Date(alert.receivedAt)))
                if (alert.body.isNotBlank()) append(" — ").append(alert.body.replace('\n', ' ').take(90))
            }
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.notification_inbox)
            .setItems(labels) { _, index -> showStoredAlert(alerts[index]) }
            .setNegativeButton(R.string.close, null).show()
    }

    private fun showStoredAlert(alert: StoredAlert) {
        AlertDialog.Builder(this)
            .setTitle(alert.title.ifBlank { getString(R.string.notification_without_title) })
            .setMessage(alert.body.ifBlank { getString(R.string.notification_without_body) })
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun showAlertFromIntent(source: Intent?) {
        val id = source?.getIntExtra(AlertNotifier.EXTRA_ALERT_ID, -1) ?: -1
        if (id < 0) return
        source?.removeExtra(AlertNotifier.EXTRA_ALERT_ID)
        AlertMessageStore(this).load().firstOrNull { it.id == id }?.let(::showStoredAlert)
        AlertMessageStore(this).markAllRead()
        updateInboxButton()
    }

    private fun configureBackends() {
        openClawApplication = application as OpenClawApplication
        gatewayClient = openClawApplication.gatewayClient
        hermesClient = HermesClient(this, hermesListener)
        openClawApplication.addGatewayListener(gatewayListener)
    }

    private fun configureBackendSelector() {
        backendSelector.check(if (selectedBackend == BackendTarget.OPENCLAW) R.id.openClawBackendButton else R.id.hermesBackendButton)
        backendSelector.setOnCheckedChangeListener { _, checkedId ->
            val target = if (checkedId == R.id.hermesBackendButton) BackendTarget.HERMES else BackendTarget.OPENCLAW
            if (target != selectedBackend) switchBackend(target)
        }
    }

    private fun switchBackend(target: BackendTarget) {
        if (activeRun != null) return
        stopCurrentSpeech(silent = true)
        selectedBackend = target
        AppPreferences(this).saveBackendTarget(target)
        messages = backendMessages.getValue(target)
        ready = false
        pairingInstructions.visibility = View.GONE
        connectionStatus.text = getString(R.string.connection_disconnected)
        renderConversation()
        updateControls()
        connect()
    }

    private fun handleBackendEvent(backend: BackendTarget, event: GatewayConnectionEvent) {
        if (backend != selectedBackend) return
        handleGatewayEvent(event)
    }

    private fun handleGatewayEvent(event: GatewayConnectionEvent) {
        pairingInstructions.visibility = View.GONE
        when (event) {
            GatewayConnectionEvent.Connecting -> {
                ready = false
                connectButton.visibility = View.GONE
                connectionStatus.text = "Connecting…"
            }
            is GatewayConnectionEvent.PairingRequired -> {
                ready = false
                connectButton.visibility = View.VISIBLE
                connectionStatus.text = buildString {
                    append("Pairing approval required")
                    event.requestId?.let { append("\nRequest ID: $it") }
                }
                pairingInstructions.visibility = View.VISIBLE
            }
            is GatewayConnectionEvent.Ready -> {
                ready = true
                connectButton.visibility = View.GONE
                connectionStatus.text = buildString {
                    append("Ready")
                    if (selectedBackend == BackendTarget.HERMES) append(" — Hermes Agent")
                    else event.serverVersion?.let { append(" — OpenClaw $it") }
                    if (event.untrustedTls) append("\n⚠ Test TLS: server certificate not verified")
                }
            }
            is GatewayConnectionEvent.History -> {
                if (activeRun == null && ConversationHistory.shouldReplace(messages, event.messages)) {
                    messages.clear()
                    messages.addAll(event.messages)
                    renderConversation()
                }
            }
            is GatewayConnectionEvent.SendAccepted -> {
                activeRun = activeRun?.copy(runId = event.runId, sessionKey = event.sessionKey)
                if (sentAttachmentNames.isNotEmpty()) {
                    attachments.removeAll { it.fileName in sentAttachmentNames }
                    sentAttachmentNames = emptySet()
                    renderAttachments()
                }
                connectionStatus.text = "Waiting for ${backendName()}…"
            }
            is GatewayConnectionEvent.AgentActivity -> handleAgentActivity(event)
            is GatewayConnectionEvent.ChatEvent -> handleChatEvent(event)
            is GatewayConnectionEvent.DebugFrame -> appendGatewayTrace(event.text)
            GatewayConnectionEvent.SessionReset -> {
                messages.clear()
                activeRun = null
                resetSpeechForNewRun(stopEngine = true)
                gatewayTrace.clear()
                connectionStatus.text = "Ready — new ${backendName()} session"
                renderConversation()
            }
            is GatewayConnectionEvent.Error -> {
                connectionStatus.text = friendlyError(event)
                if (activeRun != null) finishRun("Request failed")
            }
            GatewayConnectionEvent.Disconnected -> {
                ready = false
                connectButton.visibility = View.VISIBLE
                connectionStatus.text = "Disconnected"
            }
        }
        updateControls()
    }

    private fun handleChatEvent(wrapper: GatewayConnectionEvent.ChatEvent) {
        val event = wrapper.event
        val current = activeRun ?: return
        streamEventCount += 1
        val terminal = event.state == "final" || event.state == "aborted" || event.state == "error"
        val next = assembler.apply(current, event)
        if (next != null) activeRun = next
        val visibleText = next?.text ?: current.text
        val visibleChanged = next != null && next.text != current.text && next.text.isNotBlank()
        if (visibleChanged) {
            visibleUpdateCount += 1
            speakNewSentences(visibleText, final = false)
        }
        if (visibleChanged && waitingStartedAt != 0L) {
            stopWaiting()
            connectionStatus.text = "Receiving…"
        }
        if (visibleChanged) renderConversation()
        if (!terminal && next == null) return
        when (event.state) {
            "final" -> {
                speakNewSentences(visibleText, final = true)
                if (visibleText.isNotBlank()) messages.add(ChatMessage("assistant", visibleText))
                val status = if (visibleUpdateCount <= 1) {
                    getString(R.string.stream_final_only)
                } else {
                    getString(R.string.stream_progressive, visibleUpdateCount)
                }
                finishRun(status)
                markSelectedSessionActivity()
            }
            "aborted" -> { finishRun("Response stopped"); markSelectedSessionActivity() }
            "error" -> { finishRun(event.errorMessage ?: "Chat failed"); markSelectedSessionActivity() }
        }
    }

    private fun markSelectedSessionActivity() {
        if (selectedBackend == BackendTarget.OPENCLAW) gatewayClient.markSessionActivity()
        else hermesClient.markSessionActivity()
    }

    private fun handleAgentActivity(event: GatewayConnectionEvent.AgentActivity) {
        val run = activeRun ?: return
        if (!sameSession(run.sessionKey, event.sessionKey) || run.runId != event.runId) return
        val phase = event.phase.lowercase()
        val activityKey = event.toolCallId?.let { "tool:$it" } ?: "${event.kind}:${event.toolName.orEmpty()}"
        val dedupeKey = "${event.runId}|$activityKey|$phase"
        if (!seenActivityPhases.add(dedupeKey)) return
        while (seenActivityPhases.size > 100) seenActivityPhases.remove(seenActivityPhases.first())

        if (event.kind == AgentActivityKind.WORKING && phase in setOf("end", "finishing", "completed", "complete")) {
            if (phase == "end" || phase == "completed" || phase == "complete") clearAgentActivities()
            return
        }
        if (phase in setOf("result", "end", "completed", "complete", "error")) {
            visibleActivities.remove(activityKey)
        } else {
            visibleActivities[activityKey] = event.kind
        }
        while (visibleActivities.size > 3) visibleActivities.remove(visibleActivities.keys.first())
        renderAgentActivities()
    }

    private fun sameSession(left: String, right: String): Boolean =
        left == right || setOf(left, right) == setOf("main", "agent:main:main")

    private fun renderAgentActivities() {
        activityIcons.removeAllViews()
        visibleActivities.values.forEach { kind ->
            activityIcons.addView(TextView(this).apply {
                text = when (kind) {
                    AgentActivityKind.WORKING -> "●"
                    AgentActivityKind.THINKING -> "◌"
                    AgentActivityKind.SEARCH -> "⌕"
                    AgentActivityKind.WEB -> "◎"
                    AgentActivityKind.FILE -> "▤"
                    AgentActivityKind.COMMAND -> "›_"
                    AgentActivityKind.SKILL -> "✦"
                    AgentActivityKind.TOOL -> "⚙"
                }
                textSize = 16f
                alpha = 0.78f
                setPadding(5, 0, 5, 0)
            })
        }
    }

    private fun clearAgentActivities() {
        visibleActivities.clear()
        seenActivityPhases.clear()
        activityIcons.removeAllViews()
    }

    private fun connect() {
        val preferences = AppPreferences(this)
        if (selectedBackend == BackendTarget.HERMES) {
            val settings = preferences.loadHermes()
            if (settings.url.isBlank() || settings.apiKey.isNullOrBlank()) {
                Toast.makeText(this, R.string.hermes_url_required, Toast.LENGTH_SHORT).show()
            } else hermesClient.connect(settings)
        } else {
            val settings = preferences.loadGateway()
            if (settings.url.isBlank()) {
                Toast.makeText(this, R.string.gateway_url_required, Toast.LENGTH_SHORT).show()
            } else openClawApplication.connectGateway(settings, force = true)
        }
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (!ready || (text.isEmpty() && attachments.isEmpty()) || activeRun != null || attachmentJobs > 0) return
        if (selectedBackend == BackendTarget.HERMES && attachments.any { it.visionImages.isEmpty() }) {
            Toast.makeText(this, R.string.hermes_attachment_type_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        gatewayTrace.clear()
        val sentAttachments = attachments.toList()
        val visibleText = text.ifBlank { "Attached files" }
        val wireText = when {
            selectedBackend == BackendTarget.OPENCLAW && sentAttachments.any { !it.mimeType.startsWith("image/") } ->
                "$visibleText\n\n${AttachmentPolicy.prompt}"
            selectedBackend == BackendTarget.HERMES && sentAttachments.any { it.mimeType == "application/pdf" } ->
                "$visibleText\n\nPDF pages are attached as ordered page images rendered locally on the phone. Analyze every page in order and preserve page boundaries."
            else -> visibleText
        }
        if (selectedBackend == BackendTarget.OPENCLAW && gatewayClient.prepareSessionForSend(visibleText)) {
            messages.clear()
            resetSpeechForNewRun(stopEngine = true)
        }
        if (selectedBackend == BackendTarget.HERMES && hermesClient.prepareSessionForSend(visibleText)) {
            messages.clear()
            resetSpeechForNewRun(stopEngine = true)
        }
        val sessionKey = if (selectedBackend == BackendTarget.OPENCLAW) gatewayClient.currentSessionKey() else hermesClient.currentSessionKey()
        val provisionalRunId = if (selectedBackend == BackendTarget.OPENCLAW) {
            gatewayClient.sendChat(wireText, sentAttachments)
        } else {
            hermesClient.sendChat(wireText, AppPreferences(this).loadHermes(), sentAttachments)
        } ?: return
        messages.add(ChatMessage("user", visibleText, attachmentNames = sentAttachments.map { it.fileName }))
        sentAttachmentNames = sentAttachments.map { it.fileName }.toSet()
        activeRun = ActiveRun(sessionKey, provisionalRunId)
        streamEventCount = 0
        visibleUpdateCount = 0
        resetSpeechForNewRun(stopEngine = true)
        clearAgentActivities()
        messageInput.text.clear()
        waitingStartedAt = SystemClock.elapsedRealtime()
        waitingStatus.visibility = View.VISIBLE
        waitingAnimator?.cancel()
        waitingAnimator = ObjectAnimator.ofFloat(waitingIcon, View.ALPHA, 0.22f, 1f).apply {
            duration = 650L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        handler.removeCallbacks(waitingTicker)
        handler.post(waitingTicker)
        renderConversation()
        updateControls()
    }

    private fun appendGatewayTrace(frame: String) {
        if (gatewayTrace.isNotEmpty()) gatewayTrace.append("\n\n")
        gatewayTrace.append(frame)
        val excess = gatewayTrace.length - 500_000
        if (excess > 0) gatewayTrace.delete(0, excess)
    }

    private fun configureTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tag = AppPreferences(this).loadVoice().localeTag
                val result = textToSpeech?.setLanguage(if (tag == "system") Locale.getDefault() else Locale.forLanguageTag(tag))
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = Unit
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = Unit
                })
            }
        }
    }

    private fun speakNewSentences(text: String, final: Boolean) {
        val voiceSettings = AppPreferences(this).loadVoice()
        val spokenText = spokenTextSanitizer.sanitize(text)
        val sentences = speechPlanner.accept(spokenText, final)
        handler.removeCallbacks(pausedSpeechFlush)
        if (!final && speechPlanner.pendingLength() >= PAUSE_FLUSH_MIN_CHARS) {
            handler.postDelayed(pausedSpeechFlush, PAUSE_FLUSH_DELAY_MS)
        }
        if (!ttsReady || ttsMuted || !voiceSettings.automaticTts) {
            return
        }
        if (!speechStoppedForRun) speakChunks(sentences)
    }

    private fun speakChunks(sentences: List<String>) {
        if (!ttsReady || ttsMuted || speechStoppedForRun) return
        val generation = speechGeneration
        sentences.filter(String::isNotBlank).forEach { sentence ->
            val id = "run-$generation-${utteranceSequence++}"
            textToSpeech?.speak(sentence, TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    private fun resetSpeechForNewRun(stopEngine: Boolean) {
        speechGeneration += 1
        utteranceSequence = 0
        speechStoppedForRun = false
        speechPlanner.reset()
        handler.removeCallbacks(pausedSpeechFlush)
        if (stopEngine) textToSpeech?.stop()
    }

    private fun stopCurrentSpeech(silent: Boolean = false) {
        speechGeneration += 1
        speechStoppedForRun = true
        handler.removeCallbacks(pausedSpeechFlush)
        textToSpeech?.stop()
        if (!silent) Toast.makeText(this, R.string.speech_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun toggleTtsMute() {
        ttsMuted = !ttsMuted
        val preferences = AppPreferences(this)
        preferences.saveVoice(preferences.loadVoice().copy(muted = ttsMuted))
        if (ttsMuted) {
            speechGeneration += 1
            textToSpeech?.stop()
            handler.removeCallbacks(pausedSpeechFlush)
        }
        updateMuteButton()
        Toast.makeText(this, if (ttsMuted) R.string.tts_muted else R.string.tts_unmuted, Toast.LENGTH_SHORT).show()
    }

    private fun updateMuteButton() {
        muteButton.setImageResource(if (ttsMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
        muteButton.contentDescription = getString(if (ttsMuted) R.string.unmute else R.string.mute)
    }

    private fun repeatLastAssistantMessage() {
        if (ttsMuted) {
            Toast.makeText(this, R.string.unmute_to_repeat, Toast.LENGTH_SHORT).show()
            return
        }
        if (!ttsReady) {
            Toast.makeText(this, R.string.tts_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val lastMessage = messages.lastOrNull { it.role == "assistant" }
        if (lastMessage == null) {
            Toast.makeText(this, R.string.nothing_to_repeat, Toast.LENGTH_SHORT).show()
            return
        }
        val spokenText = spokenTextSanitizer.sanitize(lastMessage.text)
        val split = sentenceSegmenter.extractCompleteSentences(spokenText)
        val sentences = split.complete + listOfNotNull(split.remainder.takeIf { it.isNotBlank() })
        if (sentences.isEmpty()) {
            Toast.makeText(this, R.string.nothing_speakable, Toast.LENGTH_SHORT).show()
            return
        }
        textToSpeech?.stop()
        speechGeneration += 1
        sentences.forEachIndexed { index, sentence ->
            textToSpeech?.speak(
                sentence,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "repeat-$speechGeneration-$index",
            )
        }
    }

    private fun startVoiceInput() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition is unavailable", Toast.LENGTH_LONG).show()
            return
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(speechListener)
            }
        }
        retriedWithNetwork = false
        beginListening(AppPreferences(this).loadVoice().preferLocalStt)
    }

    /** Stops generation and speech before opening STT, so one invocation is a real barge-in. */
    private fun startVoiceInputWithBargeIn() {
        if (listening) return
        interruptActiveResponse()
        stopCurrentSpeech(silent = true)
        startVoiceInput()
    }

    /** Stops the active backend turn while keeping the already displayed partial answer. */
    private fun interruptActiveResponse(): Boolean {
        val run = activeRun ?: return false
        run.text.takeIf(String::isNotBlank)?.let { partial ->
            messages.add(ChatMessage("assistant", partial))
        }
        abortActiveRun()
        finishRun("Response stopped")
        markSelectedSessionActivity()
        return true
    }

    private fun beginListening(preferOffline: Boolean) {
        listening = true
        microphoneButton.alpha = 0.4f
        textToSpeech?.stop()
        speechRecognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            val tag = AppPreferences(this@MainActivity).loadVoice().localeTag
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (tag == "system") Locale.getDefault().toLanguageTag() else tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        })
    }

    private val speechListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                messageInput.setText(it)
                messageInput.setSelection(it.length)
            }
        }
        override fun onResults(results: Bundle?) {
            listening = false
            microphoneButton.alpha = 1f
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                messageInput.setText(it)
                messageInput.setSelection(it.length)
            }
        }
        override fun onError(error: Int) {
            val voice = AppPreferences(this@MainActivity).loadVoice()
            if (!retriedWithNetwork && voice.preferLocalStt && voice.allowNetworkStt) {
                retriedWithNetwork = true
                beginListening(false)
                return
            }
            listening = false
            microphoneButton.alpha = 1f
            Toast.makeText(this@MainActivity, "Speech recognition error: $error", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (activeRun == null) connect()
            startVoiceInput()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.action == Intent.ACTION_ASSIST) handler.post { startVoiceInputWithBargeIn() }
        handler.post { showAlertFromIntent(intent) }
    }

    private fun showMainMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(R.string.new_conversation).setOnMenuItemClickListener {
                if (activeRun == null && ready) {
                    textToSpeech?.stop()
                    if (selectedBackend == BackendTarget.OPENCLAW) gatewayClient.resetSession()
                    else hermesClient.resetSession()
                }
                true
            }
            menu.add(R.string.recent_conversations).setOnMenuItemClickListener {
                showRecentConversations()
                true
            }
            menu.add(R.string.notification_inbox).setOnMenuItemClickListener {
                showNotificationInbox()
                true
            }
            if (BuildConfig.DEBUG) menu.add(R.string.trace).setOnMenuItemClickListener {
                showGatewayTrace()
                true
            }
            show()
        }
    }

    private fun showRecentConversations() {
        if (activeRun != null) {
            Toast.makeText(this, R.string.wait_until_response_finishes, Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedBackend == BackendTarget.HERMES) {
            Toast.makeText(this, R.string.hermes_recent_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val sessions = gatewayClient.recentSessions()
        if (sessions.isEmpty()) {
            Toast.makeText(this, R.string.no_recent_conversations, Toast.LENGTH_SHORT).show()
            return
        }
        val current = gatewayClient.currentSessionKey()
        val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = sessions.map { session ->
            buildString {
                if (session.key == current) append("● ")
                append(formatter.format(Date(session.updatedAtMs)))
                append("\n")
                append(session.preview.ifBlank { getString(R.string.conversation_without_preview) })
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.recent_conversations)
            .setItems(labels) { _, index ->
                val selected = sessions[index]
                if (selected.key != current) {
                    stopCurrentSpeech(silent = true)
                    gatewayClient.resumeSession(selected.key)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showGatewayTrace() {
        val trace = gatewayTrace.toString().ifBlank { getString(R.string.trace_empty) }
        val field = EditText(this).apply {
            setText(trace)
            isSingleLine = false
            minLines = 12
            setTextIsSelectable(true)
            setPadding(32, 16, 32, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.trace_title)
            .setView(field)
            .setPositiveButton(R.string.copy) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OpenClaw Gateway trace", trace))
                Toast.makeText(this, "Trace copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun finishRun(status: String) {
        stopWaiting()
        clearAgentActivities()
        activeRun = null
        connectionStatus.text = status
        renderConversation()
        updateControls()
    }

    private fun stopWaiting() {
        waitingStartedAt = 0L
        handler.removeCallbacks(waitingTicker)
        waitingStatus.visibility = View.GONE
        waitingAnimator?.cancel()
        waitingAnimator = null
        waitingIcon.alpha = 1f
    }

    private fun renderConversation() {
        val rendered = buildString {
            for (message in messages) {
                append(if (message.role == "user") "You\n" else "${backendName()}\n")
                append(AttachmentPolicy.strip(message.text).trim()).append('\n')
                message.attachmentNames.forEach { append("📎 ").append(it).append('\n') }
                append('\n')
            }
            activeRun?.text?.takeIf { it.isNotBlank() }?.let {
                append("${backendName()}\n").append(it.trim()).append("\n")
            }
        }.trim()
        conversationText.text = rendered.ifEmpty { getString(R.string.empty_conversation) }
        scheduleScrollToLatest()
    }

    private fun scheduleScrollToLatest() {
        conversationScroll.removeCallbacks(scrollToLatest)
        conversationScroll.post(scrollToLatest)
        conversationScroll.postDelayed(scrollToLatest, 80)
        conversationScroll.postDelayed(scrollToLatest, 250)
    }

    private fun updateControls() {
        sendButton.isEnabled = ready && activeRun == null && attachmentJobs == 0 && (messageInput.text?.isNotBlank() == true || attachments.isNotEmpty())
        messageInput.isEnabled = ready && activeRun == null
        attachmentButton.isEnabled = activeRun == null && attachmentJobs == 0 && attachments.size < AttachmentRules.MAX_FILES
        backendSelector.isEnabled = activeRun == null
        for (index in 0 until backendSelector.childCount) backendSelector.getChildAt(index).isEnabled = activeRun == null
    }

    private fun backendName(): String = getString(
        if (selectedBackend == BackendTarget.HERMES) R.string.backend_hermes else R.string.backend_openclaw,
    )

    private fun abortActiveRun() {
        if (selectedBackend == BackendTarget.HERMES) hermesClient.abort()
        else gatewayClient.abort(activeRun?.runId)
    }

    private fun showAttachmentMenu() {
        PopupMenu(this, attachmentButton).apply {
            menu.add(getString(R.string.take_photo)).setOnMenuItemClickListener { launchCamera(); true }
            menu.add(getString(R.string.choose_photos)).setOnMenuItemClickListener {
                choosePhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)); true
            }
            menu.add(getString(R.string.choose_files)).setOnMenuItemClickListener {
                chooseFiles.launch(arrayOf("*/*")); true
            }
            show()
        }
    }

    private fun launchCamera() {
        val directory = File(cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("openclaw-", ".jpg", directory)
        cameraUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        takePhoto.launch(cameraUri!!)
    }

    private fun addUris(uris: List<Uri>) {
        val selected = uris.take((AttachmentRules.MAX_FILES - attachments.size).coerceAtLeast(0))
        if (selected.isEmpty()) return
        attachmentJobs++
        updateControls()
        val existing = attachments.toList()
        val preparePdfVision = selectedBackend == BackendTarget.HERMES
        attachmentExecutor.execute {
            val loaded = mutableListOf<ChatAttachment>()
            var error: Throwable? = null
            for (uri in selected) runCatching { AttachmentLoader(contentResolver).load(uri, existing + loaded, preparePdfVision) }
                .onSuccess { loaded += it }.onFailure { error = it }
            runOnUiThread {
                attachmentJobs--
                attachments += loaded
                error?.let { Toast.makeText(this, it.message ?: "Could not read attachment", Toast.LENGTH_LONG).show() }
                renderAttachments()
                updateControls()
            }
        }
    }

    private fun renderAttachments() {
        attachmentList.removeAllViews()
        attachmentList.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
        attachments.toList().forEach { attachment ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = "📎 ${attachment.fileName} (${formatSize(attachment.sizeBytes)})"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "×"
                contentDescription = getString(R.string.remove_attachment)
                setOnClickListener { attachments.remove(attachment); renderAttachments(); updateControls() }
            })
            attachmentList.addView(row)
        }
    }

    private fun formatSize(bytes: Long): String = if (bytes < 1024 * 1024) "${(bytes + 1023) / 1024} KB" else String.format(Locale.US, "%.1f MB", bytes / 1048576.0)

    private fun friendlyError(event: GatewayConnectionEvent.Error): String = when {
        event.message.contains("Trust anchor", ignoreCase = true) -> "Gateway certificate is not trusted. Enable test TLS in Settings."
        else -> "Error${event.code?.let { " [$it]" } ?: ""}: ${event.message}"
    }

    override fun onResume() {
        super.onResume()
        updateAlarmControls()
        if (hasResumed && ::hermesClient.isInitialized && selectedBackend == BackendTarget.HERMES) connect()
        hasResumed = true
    }

    override fun onDestroy() {
        stopWaiting()
        handler.removeCallbacks(pausedSpeechFlush)
        openClawApplication.removeGatewayListener(gatewayListener)
        hermesClient.abort()
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        attachmentExecutor.shutdownNow()
        runCatching { unregisterReceiver(alarmStateReceiver) }
        runCatching { unregisterReceiver(inboxReceiver) }
        super.onDestroy()
    }

    private fun applySystemBarAndKeyboardInsets() {
        val root = findViewById<View>(R.id.mainRoot)
        val initialBottomPadding = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bottomInset = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, initialBottomPadding + bottomInset)
            insets
        }
        root.requestApplyInsets()
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1001
        private const val REQUEST_NOTIFICATIONS = 1002
        private const val PAUSE_FLUSH_DELAY_MS = 1_500L
        private const val PAUSE_FLUSH_MIN_CHARS = 80
    }
}
