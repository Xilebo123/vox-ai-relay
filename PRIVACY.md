# Privacy and data flow

Vox AI Relay does not include advertising or analytics SDKs.

## Voice input

The application requests microphone access only for speech recognition. It prefers Android's on-device recognizer when available. If the user explicitly enables network/system fallback, the installed Android recognition service may send audio to its provider under that provider's privacy terms.

Vox AI Relay sends the recognized text to the selected backend. It does not intentionally upload a separate microphone recording to OpenClaw or Hermes.

## Voice output

Responses are passed to the Android Text-to-Speech engine selected on the device. Whether synthesis is local or network-assisted depends on that engine and its installed voice.

## Backend traffic

- OpenClaw traffic is sent to the Gateway URL configured by the user.
- Hermes traffic is sent to the API server URL configured by the user.
- Hermes push notifications are received from the configured `ntfy` server and topic.
- Attachments selected by the user are sent to the selected backend. For Hermes PDF Vision, pages are rendered on the phone before the generated images are sent.

## Stored data

The application stores settings, backend selection, non-secret session identifiers, and protected credentials. Gateway bootstrap credentials, device tokens, Hermes API keys, and `ntfy` secrets use Android Keystore-backed encrypted storage.

Conversation history is expected to remain authoritative on OpenClaw or Hermes. The application does not contain analytics telemetry or an independent cloud account service.

