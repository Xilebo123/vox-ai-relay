# OpenClaw Voice Terminal for Android — SPEC.md

Version: 0.1 specification
Status: implementation target
Updated: 2026-08-12
Primary language: Kotlin
Platform: Android
Backend: OpenClaw Gateway WebSocket
Product goal: a small, low-latency Android voice/text terminal for an OpenClaw conversation.

---

## 1. Product objective

Build a native Android application that behaves like a lightweight personal OpenClaw assistant.

The user must be able to:

1. launch the assistant using the Android system assistant role / physical assistant trigger where the device/OEM supports it,
2. speak a request,
3. convert speech to text locally on the phone when on-device recognition is available,
4. send only text to OpenClaw,
5. receive the OpenClaw reply as a live stream,
6. display the reply progressively in a simple chat,
7. start Android TTS after the first complete sentence is available,
8. continue speaking subsequent sentences while the rest of the answer is still being generated,
9. interrupt speech/output and immediately ask another question,
10. continue the same conversation by voice or keyboard.

The application is intentionally NOT a full OpenClaw Android replacement.

---

## 2. Core UX

### 2.1 Main interaction

Expected flow:

```text
physical assistant trigger / app microphone button
        |
        v
LISTENING
"Listening..."
        |
        v
Android on-device STT
        |
        v
recognized text appears as user message
        |
        v
chat.send -> OpenClaw Gateway
        |
        v
WAITING
soft visual pulse + elapsed timer
"Waiting... 4.8 s"
        |
        v
first assistant text arrives
        |
        +----> progressive text rendered in chat
        |
        v
first complete sentence detected
        |
        v
Android TTS starts speaking
        |
        +----> later complete sentences enter TTS queue
        |
        v
chat final
        |
        v
IDLE
```

### 2.2 UI states

The application shall have an explicit state machine.

Required states:

```text
DISCONNECTED
CONNECTING
PAIRING_REQUIRED
READY
LISTENING
TRANSCRIBING
SENDING
WAITING
RECEIVING
SPEAKING
ABORTING
ERROR
```

The UI may collapse some states visually, but internal state must remain explicit.

### 2.3 Waiting indicator

While a request has been sent and no visible assistant content has arrived:

- show a subtle pulse,
- show elapsed time from successful send until first visible assistant content,
- update timer approximately every 100 ms to 250 ms,
- do not use a visually aggressive spinner,
- do not play repetitive sounds.

Example:

```text
●  Waiting... 7.3 s
```

When the first visible assistant text arrives:

- stop the waiting timer,
- retain the measured value internally as `responseStartLatencyMs`,
- change status to `Receiving...` or `Speaking...`.

This is user-perceived response-start latency, not necessarily model TTFT.

### 2.4 Text chat

The same session must support both voice and keyboard input.

Requirements:

- user messages visible in the timeline,
- assistant text appears progressively,
- current assistant message is updated in place during streaming,
- final message remains in conversation history,
- follow-up messages use the same OpenClaw session,
- history is loaded from OpenClaw Gateway, not treated as authoritative local history,
- local UI may cache transient data for rendering/reconnect only.

### 2.5 Voice interruption / barge-in

If the user invokes microphone/assistant input while TTS is speaking or an OpenClaw run is active:

1. stop local TTS immediately,
2. clear queued TTS utterances,
3. abort the current OpenClaw run when a run is active,
4. start speech recognition,
5. new recognized text is sent as the next user turn.

The user must not need to wait for the previous answer to finish.

---

## 3. Android technology constraints

### 3.1 General

Use:

- Kotlin,
- native Android SDK,
- AndroidX only where useful,
- a lightweight WebSocket implementation suitable for Android,
- Android `SpeechRecognizer`,
- Android `TextToSpeech`.

Avoid unless there is a demonstrated requirement:

- Flutter,
- React Native,
- embedded WebView,
- Chromium,
- Whisper packaged inside the APK,
- Piper packaged inside the APK,
- local HTTP server,
- MCP as the chat transport,
- a second backend proxy created only for this application.

### 3.2 Minimum Android version

Codex must choose a practical `minSdk` after checking the implementation constraints.

Target modern Android first.

Recommended initial target:

```text
minSdk: 29 or higher
targetSdk: current stable SDK available in the build environment
```

Do not raise `minSdk` unnecessarily.

### 3.3 UI toolkit

Prefer the smallest maintainable native solution.

If starting from an empty project:

- XML Views + RecyclerView is acceptable and may be preferred for minimum dependency weight.

Jetpack Compose is also acceptable if it materially simplifies implementation and the APK size difference is judged insignificant for the target device.

Do not mix two UI stacks without reason.

---

## 4. Speech-to-text (STT)

### 4.1 Primary implementation

Use Android `SpeechRecognizer`.

Before creating the recognizer:

```kotlin
SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
```

When available, prefer:

```kotlin
SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
```

Goal:

> Audio should remain on the phone and only recognized text should be sent to OpenClaw whenever the platform provides a true on-device recognizer.

### 4.2 Fallback

If on-device recognition is not available:

- clearly report this in Settings/status,
- do NOT silently claim recognition is local,
- optional v0.1 fallback: permit the system recognizer only after user enables an explicit setting,
- default behavior should favor privacy over hidden network STT.

### 4.3 Recognition lifecycle

Required handling:

- microphone permission,
- start listening,
- partial recognition may be shown as draft text,
- final recognition becomes the outgoing user message,
- no speech,
- recognizer busy,
- permission denied,
- network-related error from a non-local recognizer,
- recognizer unavailable.

Do not automatically loop listening forever in v0.1.

Primary mode is push-to-talk / assistant-trigger-to-talk.

---

## 5. Text-to-speech (TTS)

### 5.1 Primary implementation

Use Android `TextToSpeech`.

TTS must run on the phone.

The application must support:

- initialization status,
- language/voice availability,
- queue management,
- `UtteranceProgressListener`,
- immediate stop,
- optional enable/disable auto-read setting.

### 5.2 Streaming sentence queue

Do NOT call TTS for each token or each network delta.

Maintain:

```text
received assistant text
        |
        v
unspokenTextBuffer
        |
        v
sentence boundary detector
        |
        v
TtsQueue
```

As soon as one complete speakable sentence is available:

- enqueue it,
- begin speaking if TTS is idle,
- keep receiving OpenClaw text in parallel,
- enqueue later sentences as they become complete.

On `final`:

- flush a non-empty final fragment even when it has no terminal punctuation.

### 5.3 Sentence boundary rules

At minimum recognize boundaries at:

- `.`
- `?`
- `!`
- paragraph break

Avoid obvious false splits including:

- decimal numbers such as `3.14`,
- IP addresses,
- common abbreviations such as `np.`, `itp.`, `itd.`,
- initials where feasible,
- ellipsis.

The boundary detector should be isolated behind an interface and unit-tested.

Do not overengineer NLP sentence segmentation in v0.1.

### 5.4 Markdown normalization for speech

Visible chat text may contain Markdown.

TTS input must be normalized separately.

At minimum:

- do not read Markdown control characters unnecessarily,
- avoid speaking fenced code blocks by default,
- normalize URLs to a short phrase or skip them,
- do not read hidden/system/tool metadata,
- retain normal punctuation needed for prosody.

The original text displayed in chat must not be modified by TTS normalization.

### 5.5 Barge-in

Calling `stop()` must:

- stop the active utterance,
- clear all queued utterances,
- invalidate old utterance callbacks,
- ensure late callbacks cannot restart speech from the aborted answer.

Use a generation/run-specific TTS generation ID or equivalent.

---

## 6. OpenClaw Gateway transport

### 6.1 Source of truth

The OpenClaw Gateway protocol changes over time.

Before implementing or modifying protocol code, inspect the current official OpenClaw Gateway protocol/schema matching the user's installed OpenClaw version.

Do not invent field names from memory.

Official references as of 2026-08-12:

- https://docs.openclaw.ai/gateway/protocol
- https://docs.openclaw.ai/gateway/clients
- https://docs.openclaw.ai/web/webchat
- https://docs.openclaw.ai/gateway/embedding

The current protocol is WebSocket + JSON text frames.

Generic frame shapes:

```json
{"type":"req","id":"...","method":"...","params":{}}
```

```json
{"type":"res","id":"...","ok":true,"payload":{}}
```

```json
{"type":"event","event":"...","payload":{}}
```

### 6.2 Connection handshake

IMPORTANT:

A modern remote client must NOT assume that sending a shared token alone is the complete protocol.

Expected lifecycle:

```text
open WebSocket
    |
    v
receive connect.challenge
    |
    v
construct challenge-bound device proof
    |
    v
send connect request
    |
    v
pairing may be required
    |
    v
receive hello-ok
    |
    v
authenticated RPC ready
```

The client must maintain a persistent device identity.

Current official guidance uses:

- Ed25519 device identity,
- challenge-bound device signature,
- bootstrap Gateway token/password for initial pairing,
- device pairing approval,
- reusable `deviceToken` after pairing.

A successful client should persist the issued device token and use it on future connections.

Never disable or bypass Gateway authentication just to make development easier.

### 6.3 Requested role/scopes

For v0.1 chat functionality request the minimal operator scopes needed.

Expected minimum:

```text
role: operator
operator.read
operator.write
```

These support:

- `chat.history`,
- session read operations,
- `chat.send`,
- ordinary chat/session write operations.

Do not request:

```text
operator.admin
operator.approvals
operator.questions
operator.pairing
```

unless a feature genuinely requires them.

Least privilege is required.

### 6.4 Chat RPCs

Primary RPCs:

```text
chat.history
chat.send
chat.abort
```

Optional later:

```text
chat.message.get
```

Do not use `chat.inject` for normal user turns.

Use `chat.history` after connect/reconnect to reconcile the visible transcript.

### 6.5 Session continuity

The application must continue one OpenClaw session.

Persist enough non-secret session identity to reconnect to the same conversation.

After loading history, retain the backing session ID/key values returned by the Gateway as required by the current protocol.

Do not infer or rewrite private OpenClaw state files.

Do not inspect `~/.openclaw` directly from the app.

OpenClaw Gateway is the source of truth.

### 6.6 `chat.send`

Every side-effecting send must use the idempotency mechanism required by the current Gateway schema.

Generate a stable request/run id for one logical user send.

Retrying the same logical send after a transient connection failure must NOT create duplicate user turns.

Before coding the exact params object:

- inspect current `chat.send` schema,
- match installed Gateway version,
- add only fields understood by that version.

### 6.7 Streaming events

Process live chat events and correlate them to the active session/run.

Expected assistant run states include the current equivalent of:

```text
delta
final
aborted
error
```

Do not append every `delta` blindly.

The client must determine whether a delta is:

- an incremental fragment,
- a replacement/aggregate snapshot,
- or another current protocol representation.

Build the visible assistant message from the actual protocol semantics.

### 6.8 Event isolation

Do not assume all incoming `chat` events belong to this client.

Filter/correlate using current protocol fields such as session/run identifiers.

This matters especially when multiple OpenClaw clients or sessions are active.

A response from another session must never be spoken by this application.

### 6.9 Abort

When a run is active and the user interrupts:

```text
TTS stop
+
chat.abort
+
new STT
```

Abort should target the active run/session according to the current Gateway schema.

If abort fails because the run already completed, continue normally.

### 6.10 Reconnection

Implement bounded reconnect with exponential backoff and jitter.

Example policy:

```text
0.5 s
1 s
2 s
4 s
8 s
max 15 s
```

On reconnect:

1. authenticate,
2. restore the intended session,
3. call `chat.history`,
4. reconcile any partially displayed assistant response,
5. resume READY state.

Never resend an uncertain side-effecting request with a new idempotency key.

### 6.11 Shutdown/restart

Handle Gateway restart explicitly.

If a `shutdown` event is received, preserve relevant reconnect timing information.

A reconnect after Gateway restart should be invisible to the user except for connection status.

---

## 7. Gateway configuration

Settings screen must provide:

```text
Gateway URL
Bootstrap token/password (for initial pairing only)
Current pairing/device status
Session / agent selection if required
Automatic TTS on/off
Prefer on-device STT on/off (default ON)
Allow network/system STT fallback (default OFF)
```

Example Gateway URL:

```text
wss://openclaw.example.net/
```

Development may support:

```text
ws://192.168.x.x:18789/
```

but plaintext remote WebSocket must not be encouraged.

---

## 8. Credential storage

Secrets include:

- bootstrap Gateway token/password,
- reusable device token,
- device private key.

Requirements:

- never hard-code them in source,
- never commit them,
- never print them in logs,
- protect persistent secrets with Android Keystore-backed encryption,
- bootstrap shared secret may be removed after successful device pairing if no longer needed.

The Ed25519 device private key must remain device-local.

---

## 9. System assistant integration

### 9.1 Goal

The app should be eligible to act as the Android system assistant where the device allows it.

Primary desired UX:

```text
physical assistant trigger
        |
        v
OpenClaw Voice Terminal
        |
        v
start listening immediately
```

No screen gesture should be required for the normal flow.

### 9.2 Android implementation

Investigate and implement the platform-supported assistant path using the current Android APIs, including as appropriate:

- `VoiceInteractionService`,
- `VoiceInteractionSessionService`,
- `VoiceInteractionSession`,
- assistant role/default assistant selection.

The app should provide an in-app action that directs the user to the appropriate system role/default-assistant selection flow when supported.

### 9.3 OEM limitation

IMPORTANT:

Do not claim that obtaining the assistant role guarantees a particular physical button behavior on every phone.

Samsung/Pixel/other OEM firmware may route long-press power or a dedicated assistant button differently.

Acceptance is therefore:

> When the target device routes its physical assistant trigger to the currently selected assistant, OpenClaw Voice Terminal is invoked and starts listening.

If a device does not expose that mapping, provide a clear diagnostic message rather than hacks such as AccessibilityService key interception.

Do not use AccessibilityService merely to steal hardware keys.

---

## 10. App architecture

Suggested logical components:

```text
ui/
    MainActivity / chat UI
    Settings UI
    state rendering

voice/
    SpeechRecognizerController
    TtsController
    SentenceSegmenter
    SpeechTextNormalizer

gateway/
    GatewayWebSocket
    GatewayProtocol
    GatewayAuthenticator
    DeviceIdentityStore
    ChatRepository
    SessionRepository

conversation/
    ConversationController
    ConversationState
    ActiveRun
    MessageAssembler

assistant/
    VoiceInteractionService implementation
    VoiceInteractionSession implementation

storage/
    SecureCredentialStore
    AppPreferences
```

The exact package layout may differ, but responsibilities must remain separated.

Do not place Gateway JSON parsing, TTS management and Activity UI logic in one class.

---

## 11. Conversation state machine

A single coordinator should own conversation transitions.

Example:

```text
READY
  |
  | assistant trigger
  v
LISTENING
  |
  | final STT
  v
SENDING
  |
  | chat.send accepted
  v
WAITING
  |
  | first visible assistant text
  v
RECEIVING
  | \
  |  \ first complete sentence
  |   v
  | SPEAKING
  |   |
  |   | more stream
  |   +----------------+
  |                    |
  +---- final ----------+
                       v
                      READY
```

`SPEAKING` and `RECEIVING` may coexist logically.

Model them as orthogonal properties if that results in cleaner code:

```kotlin
data class ConversationState(
    val connection: ConnectionState,
    val input: InputState,
    val generation: GenerationState,
    val speech: SpeechState
)
```

Avoid a giant enum if it produces invalid/unmanageable transitions.

---

## 12. Latency instrumentation

Collect locally for diagnostics:

```text
sttDurationMs
sendToAcceptedMs
responseStartLatencyMs
firstSentenceReadyMs
ttsStartLatencyMs
totalRunMs
```

Do not send telemetry anywhere in v0.1.

Provide optional debug display/logging.

This instrumentation is valuable because OpenClaw may call tools before producing visible text.

---

## 13. Tool calls

The app does NOT execute OpenClaw tools locally in v0.1.

OpenClaw handles agent tool calls on the Gateway/server side.

During tool execution:

- keep waiting/receiving status meaningful,
- continue elapsed waiting indication until visible answer content arrives,
- do not expose raw tool JSON as assistant speech,
- do not speak internal reasoning,
- do not speak hidden metadata.

Future support for approval prompts is outside v0.1.

---

## 14. History and persistence

### v0.1

Use Gateway `chat.history` as authoritative conversation history.

Local persistence is limited to:

- settings,
- secure credentials,
- selected session identity,
- transient UI recovery if necessary.

Do not build a second independent chat database unless a concrete offline requirement appears.

### Reconnect

After reconnect:

- fetch history,
- reconcile current UI,
- avoid duplicating already persisted messages.

---

## 15. Error UX

Errors must be understandable, short and actionable.

Examples:

```text
Disconnected — reconnecting…
Pairing approval required
Microphone permission required
On-device speech recognition unavailable
Gateway authentication failed
OpenClaw returned an error
TTS voice unavailable
```

Do not expose stack traces in normal UI.

Provide a diagnostics screen/log export later if needed.

---

## 16. Visual design

Goal: minimal, calm and functional.

Main screen:

```text
+----------------------------------+
| OpenClaw                     ●   |
+----------------------------------+
|                                  |
| You                              |
| Turn on the garage light.        |
|                                  |
| OpenClaw                         |
| Done. The garage light is on.    |
|                                  |
|           ● Waiting... 4.2 s     |
|                                  |
+----------------------------------+
|  Message...                  Mic |
+----------------------------------+
```

Requirements:

- large enough microphone control,
- connection state visible but unobtrusive,
- chat is the primary surface,
- no dashboard-style clutter,
- pulse animation should be subtle and low-cost,
- system dark/light theme support is desirable.

---

## 17. Non-goals for v0.1

Do NOT implement unless required to make the core path work:

- wake word,
- always-listening microphone,
- full-duplex audio,
- realtime voice API,
- server-side STT,
- server-side TTS,
- voice notes,
- image upload,
- camera,
- location,
- notification mirroring,
- tool approval UI,
- Markdown editor,
- multiple simultaneous conversations,
- rich widgets,
- browser rendering,
- Android AccessibilityService hacks,
- custom speech model bundled in APK.

---

## 18. Testing requirements

### 18.1 Unit tests

At minimum:

- sentence segmentation,
- streamed message assembly,
- TTS queue generation invalidation,
- idempotent send bookkeeping,
- state transitions,
- reconnect reconciliation helpers.

### 18.2 Integration tests / fakes

Provide a fake Gateway transport capable of emitting:

```text
connect.challenge
hello-ok
chat delta
chat final
chat aborted
chat error
disconnect/reconnect
```

Test:

- first sentence starts TTS before final,
- second/third sentence queues correctly,
- final unpunctuated fragment is spoken,
- abort cancels old TTS,
- old run events are ignored after a new run starts,
- events from another session are ignored,
- reconnect does not duplicate send,
- history reconciles partial output.

### 18.3 Manual device test

Required sequence:

1. install APK,
2. configure Gateway,
3. pair device,
4. select app as assistant if supported,
5. invoke using physical assistant trigger,
6. speak a question,
7. verify transcript,
8. verify waiting pulse/timer,
9. verify first sentence starts speaking before full answer,
10. verify complete streamed text remains visible,
11. interrupt TTS,
12. ask a follow-up,
13. verify same OpenClaw conversation context,
14. disable network STT fallback and verify local-recognition behavior,
15. disconnect Wi-Fi/mobile route temporarily and verify reconnect.

---

## 19. Definition of Done — v0.1

v0.1 is complete only when all of the following work on a real Android phone:

- [ ] Gateway connects through the current authenticated WebSocket protocol.
- [ ] Device pairing works without manually disabling Gateway security.
- [ ] Connection survives/recover from normal temporary disconnect.
- [ ] Existing conversation history loads.
- [ ] Text input sends a message and receives streamed output.
- [ ] Voice input produces a user message.
- [ ] On-device STT is used when available.
- [ ] Waiting pulse and elapsed timer are visible before first response text.
- [ ] Assistant text appears progressively.
- [ ] TTS begins after the first complete sentence before the final response.
- [ ] Later sentences are spoken continuously from a queue.
- [ ] Text remains available in chat after speech completes.
- [ ] User can continue the same session by voice or text.
- [ ] Barge-in stops speech and aborts the previous run.
- [ ] Another session's events are never displayed/spoken.
- [ ] Credentials are not exposed in logs/source.
- [ ] App can request/be configured as system assistant where Android/OEM supports it.
- [ ] Physical assistant trigger starts listening on the target phone when OEM routing supports the selected assistant.
- [ ] No WebView, bundled Whisper/Piper, or unnecessary background server is required.

---

## 20. Implementation order

Implement in this order.

### Phase A — Gateway proof

1. empty native app,
2. settings for Gateway URL/bootstrap credentials,
3. persistent device identity,
4. challenge/connect/pairing,
5. `hello-ok`,
6. `chat.history`,
7. `chat.send`,
8. stream assembly,
9. `chat.abort`,
10. reconnect.

Do not implement polished voice UI before Gateway transport is proven.

### Phase B — Text chat

1. message list,
2. input field,
3. progressive assistant rendering,
4. waiting timer/pulse,
5. session continuity.

### Phase C — Voice

1. on-device STT,
2. system TTS,
3. sentence queue,
4. Markdown-to-speech normalization,
5. barge-in.

### Phase D — Android assistant role

1. VoiceInteraction components,
2. system role/default-assistant setup,
3. assistant invocation,
4. immediate start-listening behavior,
5. test physical trigger on target device.

### Phase E — hardening

1. reconnect edge cases,
2. lifecycle/background behavior,
3. permissions,
4. tests,
5. release build,
6. APK.

---

## 21. Protocol compatibility rule

OpenClaw is actively developed.

Therefore:

> The implementation must isolate OpenClaw wire-protocol structures behind the `gateway/` layer and must never spread raw protocol JSON field handling across the UI/voice code.

If an OpenClaw update changes the protocol, ideally only the Gateway layer and its tests should require modification.

When the installed OpenClaw version differs from the documentation used here:

1. inspect that version's protocol/schema,
2. record the mismatch,
3. adapt minimally,
4. do not silently guess.

---

## 22. Privacy rule

Preferred privacy boundary:

```text
microphone audio
      |
      v
Android local STT
      |
      v
TEXT ONLY
      |
      v
OpenClaw Gateway

OpenClaw TEXT stream
      |
      v
Android local TTS
      |
      v
speaker
```

If Android cannot provide true on-device recognition, the app must say so explicitly.

Absence of local STT is important information; do not hide it by automatically using a cloud recognizer.

---

## 23. Notes for future versions

Potential future features, explicitly outside v0.1:

- hands-free VAD,
- wake word,
- Bluetooth-headset optimized mode,
- Android Auto style UI if platform policies permit,
- notifications initiated by OpenClaw,
- optional approval/question cards,
- multiple session selector,
- local offline message queue,
- richer speech normalization,
- selectable TTS voice/rate,
- optional OpenClaw Talk integration.

Do not implement these early unless the user explicitly changes scope.
