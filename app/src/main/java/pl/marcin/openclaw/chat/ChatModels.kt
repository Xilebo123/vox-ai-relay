package pl.marcin.openclaw.chat

data class ChatMessage(val role: String, val text: String, val id: String? = null, val attachmentNames: List<String> = emptyList())

data class ActiveRun(
    val sessionKey: String,
    val runId: String,
    val lastChatSeq: Long = -1,
    val lastAgentSeq: Long = -1,
    val text: String = "",
    val rawWireText: String = "",
)

class MessageAssembler {
    fun apply(current: ActiveRun, event: ChatStreamEvent): ActiveRun? {
        if (!sameSession(event.sessionKey, current.sessionKey) || event.runId != current.runId) return null
        val lastSeq = if (event.source == ChatStreamSource.AGENT) current.lastAgentSeq else current.lastChatSeq
        if (event.seq <= lastSeq) return null
        val raw = when {
            event.snapshotText != null -> ""
            event.replace -> event.deltaText
            current.rawWireText.isNotEmpty() -> current.rawWireText + event.deltaText
            event.deltaText.trimStart().startsWith("[") || event.deltaText.trimStart().startsWith("{") -> event.deltaText
            else -> ""
        }
        val nextText = when {
            event.snapshotText != null -> event.snapshotText
            raw.isNotEmpty() -> VisibleChatText.fromPossiblyPartialWire(raw)
            event.replace -> event.deltaText
            event.source == ChatStreamSource.CHAT && current.text.isNotEmpty() &&
                event.deltaText.length > current.text.length &&
                event.deltaText.startsWith(current.text) -> event.deltaText
            event.state in setOf("final", "aborted", "error") && event.deltaText == current.text -> current.text
            else -> current.text + event.deltaText
        }
        return current.copy(
            lastChatSeq = if (event.source == ChatStreamSource.CHAT) event.seq else current.lastChatSeq,
            lastAgentSeq = if (event.source == ChatStreamSource.AGENT) event.seq else current.lastAgentSeq,
            text = nextText,
            rawWireText = raw,
        )
    }

    private fun sameSession(left: String, right: String): Boolean =
        left == right || (setOf(left, right) == setOf("main", "agent:main:main"))
}

enum class ChatStreamSource { CHAT, AGENT }

data class ChatStreamEvent(
    val sessionKey: String,
    val runId: String,
    val seq: Long,
    val state: String,
    val deltaText: String = "",
    val snapshotText: String? = null,
    val replace: Boolean = false,
    val errorMessage: String? = null,
    val source: ChatStreamSource = ChatStreamSource.CHAT,
)
