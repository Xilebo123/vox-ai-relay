package pl.marcin.openclaw.voice

/**
 * Converts growing, occasionally rewritten text snapshots into a strictly forward-only speech stream.
 * Text at or below the high-water mark is never emitted twice.
 */
class StreamingSpeechPlanner(
    private val segmenter: SentenceSegmenter = DeterministicSentenceSegmenter(),
) {
    private var highWaterMark = 0
    private var pending = ""

    fun reset() {
        highWaterMark = 0
        pending = ""
    }

    fun accept(snapshot: String, final: Boolean = false): List<String> {
        if (snapshot.length > highWaterMark) {
            pending += snapshot.substring(highWaterMark)
            highWaterMark = snapshot.length
        }
        val split = segmenter.extractCompleteSentences(pending)
        pending = split.remainder
        return buildList {
            addAll(split.complete)
            if (final && pending.isNotBlank()) {
                add(pending.trim())
                pending = ""
            }
        }
    }

    fun flushAfterPause(minimumCharacters: Int = 80): List<String> {
        if (pending.trim().length < minimumCharacters) return emptyList()
        return listOf(pending.trim()).also { pending = "" }
    }

    fun pendingLength(): Int = pending.trim().length
}
