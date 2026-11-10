package pl.marcin.openclaw.hermes

/**
 * Projects the accumulated Hermes assistant stream into user-visible text.
 * Hermes may put its internal XML tool invocation blocks in delta.content;
 * those blocks must never reach the chat bubble or TTS.
 */
object HermesVisibleText {
    private const val OPEN = "<tool_call"
    private const val CLOSE = "</tool_call>"

    fun fromAccumulated(raw: String): String {
        if (raw.isEmpty()) return ""
        val visible = StringBuilder(raw.length)
        var cursor = 0
        while (cursor < raw.length) {
            val openAt = raw.indexOf(OPEN, cursor, ignoreCase = true)
            if (openAt < 0) {
                val safeEnd = raw.length - partialOpenTagSuffixLength(raw, cursor)
                if (safeEnd > cursor) visible.append(raw, cursor, safeEnd)
                break
            }
            if (openAt > cursor) visible.append(raw, cursor, openAt)
            val openEnd = raw.indexOf('>', openAt + OPEN.length)
            if (openEnd < 0) break
            val closeAt = raw.indexOf(CLOSE, openEnd + 1, ignoreCase = true)
            if (closeAt < 0) break
            cursor = closeAt + CLOSE.length
        }
        return visible.toString()
    }

    fun containsToolCall(raw: String): Boolean = raw.indexOf(OPEN, ignoreCase = true) >= 0

    /** Holds a split opening tag (for example "<tool_") until the next SSE chunk. */
    private fun partialOpenTagSuffixLength(raw: String, from: Int): Int {
        val available = raw.length - from
        val maximum = minOf(OPEN.length - 1, available)
        for (length in maximum downTo 1) {
            if (raw.regionMatches(raw.length - length, OPEN, 0, length, ignoreCase = true)) return length
        }
        return 0
    }
}
