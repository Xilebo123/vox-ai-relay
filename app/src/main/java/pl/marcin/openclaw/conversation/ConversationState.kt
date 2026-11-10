package pl.marcin.openclaw.conversation

data class ConversationState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val input: InputState = InputState.Idle,
    val generation: GenerationState = GenerationState.Idle,
    val speech: SpeechState = SpeechState.Idle,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object PairingRequired : ConnectionState
    data object Ready : ConnectionState
    data class Error(val message: String) : ConnectionState
}

sealed interface InputState {
    data object Idle : InputState
    data object Listening : InputState
    data object Transcribing : InputState
}

sealed interface GenerationState {
    data object Idle : GenerationState
    data object Sending : GenerationState
    data class Waiting(val startedAtMs: Long) : GenerationState
    data class Receiving(val runId: String, val responseStartLatencyMs: Long) : GenerationState
    data object Aborting : GenerationState
    data class Error(val message: String) : GenerationState
}

sealed interface SpeechState {
    data object Idle : SpeechState
    data class Speaking(val generationId: Long) : SpeechState
    data class Error(val message: String) : SpeechState
}
