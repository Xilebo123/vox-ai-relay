# AGENTS.md — OpenClaw Voice Terminal Android

This file is an execution contract for coding agents working on this repository.

Read `SPEC.md` completely before modifying the project.

The goal is a small, reliable Android voice/text client for OpenClaw Gateway.

---

## 1. Prime directive

Implement the product described in `SPEC.md`.

Do not redesign the product unless a requirement is impossible or conflicts with the actual current Android/OpenClaw APIs.

If you discover such a conflict:

1. verify it,
2. document it clearly,
3. implement the smallest safe alternative,
4. preserve the user's intended UX.

Do not silently replace requirements with easier ones.

---

## 2. Product priorities

In descending order:

1. correct OpenClaw Gateway communication,
2. low perceived latency,
3. reliable interruption/barge-in,
4. local/private STT and TTS where supported,
5. simple UI,
6. low dependency/APK/runtime overhead,
7. maintainability,
8. optional polish.

Never sacrifice protocol correctness for UI polish.

---

## 3. Hard constraints

Do not introduce any of the following without explicit justification:

- WebView,
- React Native,
- Flutter,
- embedded browser UI,
- bundled Whisper,
- bundled Piper,
- local HTTP server,
- MCP as the main chat path,
- AccessibilityService for hardware-key interception,
- a custom OpenClaw server-side proxy solely to simplify Android code,
- cloud STT silently enabled by default,
- unnecessary analytics/telemetry SDK,
- advertising SDK,
- broad operator/admin scopes.

---

## 4. Required architecture boundaries

Keep these concerns separate:

```text
UI
Conversation orchestration
Voice/STT/TTS
Gateway protocol/transport
Secure storage
Android assistant integration
```

The Activity/Fragment/Composable must not directly parse Gateway frames.

The WebSocket class must not directly control TextToSpeech.

SpeechRecognizer callbacks must not directly send arbitrary JSON.

Use interfaces or small adapters at subsystem boundaries.

---

## 5. OpenClaw protocol rule — CRITICAL

Never invent OpenClaw protocol fields.

Before implementing protocol behavior:

1. identify the OpenClaw version used by the target Gateway if available,
2. inspect matching official protocol/schema,
3. compare with current official docs,
4. encode/decode only verified fields.

Current official references from the specification date:

- `https://docs.openclaw.ai/gateway/protocol`
- `https://docs.openclaw.ai/gateway/clients`
- `https://docs.openclaw.ai/web/webchat`
- `https://docs.openclaw.ai/gateway/embedding`

Modern Gateway expectations include:

```text
WebSocket
-> connect.challenge
-> challenge-bound signed connect
-> pairing when required
-> hello-ok
-> authenticated RPC
```

A remote Android client must not bypass this process.

---

## 6. Authentication and pairing

Implement persistent device identity.

Current expected model:

- Ed25519 device identity,
- challenge-bound proof,
- shared Gateway bootstrap credential for first connection,
- pairing approval,
- reusable device token.

Security requirements:

- private key stays on device,
- tokens are never logged,
- secrets are never committed,
- secrets are stored using Android Keystore-backed protection,
- pairing failure is a normal UI state, not an exception that crashes the app.

Do not edit OpenClaw server config to weaken authentication.

---

## 7. Gateway scopes

Use least privilege.

Initial target:

```text
operator.read
operator.write
```

Do not request `operator.admin`.

Do not add `operator.approvals`, `operator.questions`, or pairing/admin scopes merely because they exist.

If a method returns missing-scope:

- verify whether the feature is actually required,
- add the minimum scope only if necessary,
- document why.

---

## 8. Chat behavior

Normal user messages go through `chat.send`.

History comes from `chat.history`.

Cancellation uses `chat.abort`.

Do not use `chat.inject` to simulate user messages.

Every logical side-effecting send must have stable idempotency handling per the current schema.

Retries must not duplicate a user message.

---

## 9. Stream assembly

Do not assume a WebSocket `delta` is always a simple append-only token fragment.

Inspect current OpenClaw chat event semantics.

The assembler must produce one authoritative visible assistant message for the active run.

The assembler must:

- correlate session,
- correlate run,
- ignore stale run events,
- ignore events from other sessions,
- handle final,
- handle abort,
- handle error,
- tolerate duplicate/replayed events after reconnect where the protocol allows them.

Add unit tests for these cases.

---

## 10. TTS behavior — CRITICAL UX

The user explicitly wants TTS to start before the complete answer exists.

Required:

```text
stream -> sentence buffer -> TTS queue
```

Do NOT:

```text
wait for final -> speak full response
```

Do NOT:

```text
speak each token/delta
```

Start TTS after the first complete sentence.

Queue later complete sentences while generation continues.

On final, flush the last non-empty fragment.

---

## 11. Sentence segmentation

Implement a small deterministic segmenter with tests.

At minimum avoid splitting:

```text
3.14
192.168.1.1
np.
itp.
itd.
```

Handle:

```text
.
?
!
paragraph break
```

Keep segmentation logic independent from Android TTS so it can be unit-tested on JVM.

---

## 12. TTS cancellation

Barge-in must be immediate.

On new voice invocation while output is active:

```text
stop TTS
clear TTS queue
invalidate old TTS generation
abort active OpenClaw run if needed
start STT
```

Late `UtteranceProgressListener` callbacks from the old response must not enqueue/restart anything.

Use a monotonically increasing local speech generation identifier or equivalent.

---

## 13. STT privacy behavior

Prefer:

```kotlin
SpeechRecognizer.createOnDeviceSpeechRecognizer(...)
```

only after checking on-device recognition availability.

If it is unavailable:

- report that fact,
- do not call the fallback "local",
- do not silently switch to a recognizer that may use network,
- allow network/system fallback only behind an explicit user setting.

Do not hide lack of data/capability.

---

## 14. Waiting pulse and timer

The user explicitly requested this feature based on practical experience.

Implement it.

Start timer at the point the outgoing request is accepted/sent according to the chosen transport semantics.

Stop it at first visible assistant response content.

UI behavior:

```text
● Waiting... 6.7 s
```

Pulse is subtle.

Timer should not trigger expensive full-screen recomposition/layout work.

Store `responseStartLatencyMs` for local diagnostics.

---

## 15. System assistant behavior

Goal:

```text
physical assistant trigger
-> app invoked as selected Android assistant
-> microphone listening starts
```

Implement only platform-supported mechanisms.

Use current Android assistant APIs, expected to include:

- `VoiceInteractionService`,
- `VoiceInteractionSessionService`,
- `VoiceInteractionSession`,
- assistant/default-role setup where available.

Do not guarantee hardware-button routing on every OEM.

Do not use accessibility hacks simply to capture power/home keys.

When OEM routing does not support the desired trigger, surface a diagnostic instead.

---

## 16. UI behavior

Keep the UI simple.

Primary view:

- message timeline,
- text composer,
- microphone button,
- unobtrusive connection state,
- waiting/receiving/speaking indicator.

Do not add:

- dashboards,
- cards unrelated to chat,
- animated mascots,
- large settings surface on the main screen,
- tool debug JSON in normal chat.

The answer must remain readable as text even when TTS is enabled.

Voice and text input use the same conversation.

---

## 17. Lifecycle rules

Android lifecycle must not cause:

- duplicate sends,
- TTS restart from the beginning after rotation,
- leaked SpeechRecognizer,
- leaked TextToSpeech,
- duplicate WebSocket connections,
- lost active run identifiers.

Prefer lifecycle-aware ownership.

If configuration changes are avoided by a fixed orientation, document why; otherwise handle them correctly.

Do not solve lifecycle problems by making every object a global singleton.

---

## 18. Background behavior

v0.1 is not an always-listening assistant.

Do not keep microphone capture running in background.

Do not add a permanent foreground service unless required by the selected assistant/voice interaction behavior and Android rules.

If a foreground service becomes necessary:

- use the correct service type,
- declare only required permissions,
- explain the requirement in code/docs.

---

## 19. Logging

Debug logs may include:

```text
connection state
request IDs
run IDs
session identifiers where safe
latency values
event type
error codes
```

Never log:

```text
Gateway password/token
device token
private key
full authorization frames
raw microphone audio
sensitive user text by default
```

Allow verbose protocol body logging only in an explicitly unsafe local development mode, disabled by default.

---

## 20. Dependencies

Before adding a dependency ask:

1. Can Android SDK do it?
2. Is the dependency maintained?
3. What APK/runtime cost does it add?
4. Does it add networking/telemetry behavior?
5. Is it actually needed for v0.1?

A WebSocket dependency such as OkHttp is reasonable.

Do not add an entire framework to avoid writing a few small classes.

Pin versions using the project's normal version catalog or Gradle convention.

---

## 21. Build discipline

After each meaningful milestone:

```text
compile
run tests
fix warnings/errors relevant to changed code
```

Do not accumulate many uncompiled changes.

Keep commits/changes conceptually small when possible.

Do not mass-reformat unrelated source files.

---

## 22. Implementation milestones

Work in this order unless the repository already contains later working pieces.

### M1: buildable shell

- Android app builds
- minimal main screen
- settings placeholder
- tests run

### M2: Gateway authentication proof

- WebSocket open
- `connect.challenge`
- device identity
- signed connect
- pairing-required state
- `hello-ok`

### M3: text Gateway proof

- `chat.history`
- `chat.send`
- progressive assistant response
- `chat.abort`
- event isolation
- reconnect

### M4: usable text chat

- history rendering
- input composer
- active message assembly
- waiting pulse/timer
- same-session continuation

### M5: STT

- permission
- on-device availability detection
- local recognizer
- final transcript -> send

### M6: TTS streaming

- sentence segmentation
- first sentence starts early
- queue
- final fragment
- stop/invalidations

### M7: barge-in

- interrupt active TTS
- abort run
- start listening
- follow-up in same session

### M8: Android assistant integration

- VoiceInteraction service/session
- assistant selection flow
- invocation starts STT
- physical trigger test on target hardware

### M9: hardening

- lifecycle
- reconnect race conditions
- secure credential storage
- tests
- release APK

---

## 23. Before declaring a milestone complete

For every milestone:

1. run the build,
2. run relevant tests,
3. report what was actually tested,
4. report what was NOT tested,
5. distinguish emulator validation from physical-device validation,
6. do not claim success for untested OEM assistant-button behavior.

Example status:

```text
PASS: JVM sentence segmentation tests
PASS: Android debug build
PASS: fake Gateway streaming test
NOT TESTED: real Gateway pairing
NOT TESTED: Samsung physical assistant trigger
```

Unknown/untested is valuable information. Never convert it into a guess.

---

## 24. Error handling policy

Protocol and Android API errors must become typed/domain errors where practical.

Do not branch on human-readable OpenClaw error text if structured error codes/details are available.

For Gateway errors:

- prefer `code`,
- prefer structured `details`,
- treat text `message` as display/debug information.

For retryable errors:

- respect `retryable` / `retryAfterMs` when supplied by the protocol.

---

## 25. Reconnect policy

Use bounded exponential backoff + jitter.

Do not create a tight reconnect loop.

After reconnect:

```text
authenticate
restore session
load history
reconcile active UI
```

Do not resend uncertain side effects with a fresh idempotency key.

If request outcome is unknown:

- reconcile through Gateway state/history first.

---

## 26. Source of truth

For conversation state:

```text
OpenClaw Gateway = durable source of truth
Android UI = projection
```

For active audio state:

```text
Android app = source of truth
```

Do not create a competing conversation database in v0.1.

---

## 27. Protocol compatibility layer

All raw OpenClaw protocol DTOs and field names belong in the Gateway layer.

Business code should work with app-domain objects such as:

```kotlin
data class ChatMessage(...)
data class ChatDelta(...)
data class ActiveRun(...)
sealed interface GatewayConnectionState
```

Do not let UI code depend on raw JSON object traversal.

This is mandatory because OpenClaw evolves rapidly.

---

## 28. Security review before release

Before release verify:

- no secrets in Git,
- no secrets in `BuildConfig`,
- no secrets in logs,
- no exported Android component without a reason,
- correct WebSocket TLS behavior,
- cleartext traffic restricted to explicit development configuration,
- minimal permissions,
- microphone permission requested only when needed,
- device private key protected,
- pairing token lifecycle correct.

---

## 29. Performance review before release

Verify:

- no audio is uploaded by this app in local-STT mode,
- no busy loops,
- waiting timer is lightweight,
- pulse animation is lightweight,
- no per-token TTS calls,
- no per-token database writes,
- no unnecessary full-list redraw on each tiny stream fragment,
- WebSocket stays single/controlled,
- TTS queue does not grow from duplicate text.

---

## 30. Acceptance scenarios

### Scenario A — normal voice question

```text
invoke assistant
speak
STT final
send
waiting pulse/timer
first response sentence arrives
TTS begins
text keeps streaming
TTS continues
final
```

PASS only if TTS begins before final for a multi-sentence streamed answer.

### Scenario B — text continuation

```text
type follow-up
send
same OpenClaw session
context preserved
```

### Scenario C — interrupt

```text
AI speaking
invoke microphone
speech stops immediately
active run aborted
STT starts
new question sent
```

### Scenario D — reconnect

```text
disconnect network
restore network
Gateway reconnect
history reconciliation
no duplicated user turn
```

### Scenario E — wrong-session event

Inject/receive a valid chat event for another session.

Expected:

```text
not displayed
not queued for TTS
not allowed to alter active run
```

### Scenario F — local STT unavailable

Expected:

```text
clear UI indication
no false "local" claim
network recognizer not used unless user explicitly enabled fallback
```

---

## 31. Definition of agent success

You have succeeded when the real app behaves like a fast voice terminal, not when code merely compiles.

The core experiential requirement is:

> Press assistant trigger, speak naturally, see that the system is working, hear the first sentence as soon as it exists, continue seeing/ hearing the rest of the streamed answer, and interrupt at any moment to continue the same conversation.

Everything else is secondary.
