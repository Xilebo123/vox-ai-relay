# OpenClaw Android — working baseline

- Version: `0.3.8` (`versionCode 20`)
- Confirmed working by the user: 2026-08-14
- Baseline APK: `baselines/OpenClaw-0.3.8-baseline-debug.apk`
- SHA-256: `86D6416029EC05E28FD6157CCB1800B5D62513CC976D4061FA463E4AB0C030A9`

Confirmed functionality:

- OpenClaw Gateway connection with test/untrusted TLS
- persistent foreground WebSocket connection
- device pairing and authentication
- new independent chat sessions
- progressive visible-text streaming without duplicated final text
- hidden reasoning/tool traffic
- automatic Polish TTS, sentence by sentence
- immediate, persistent app-only TTS mute/unmute
- Markdown, table, code-block, image, and ASCII-art filtering for TTS
- microphone dictation
- Android assistant / press-and-talk launch
- stable connection across screen rotation and background use

Treat this version as the regression baseline for subsequent changes.
