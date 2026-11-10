package pl.marcin.openclaw.voice

interface SentenceSegmenter {
    fun extractCompleteSentences(text: String): SegmentationResult
}

data class SegmentationResult(val complete: List<String>, val remainder: String)

class DeterministicSentenceSegmenter : SentenceSegmenter {
    private val abbreviations = setOf("np.", "itp.", "itd.")

    override fun extractCompleteSentences(text: String): SegmentationResult {
        val sentences = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < text.length) {
            val boundaryEnd = when {
                text[index] == '\n' && index + 1 < text.length && text[index + 1] == '\n' -> index + 2
                text[index] == '\n' -> index + 1
                text[index] == '?' || text[index] == '!' -> index + 1
                text[index] == ';' || text[index] == ':' -> index + 1
                text[index] == '.' && isSentencePeriod(text, index) -> index + 1
                else -> null
            }
            if (boundaryEnd != null) {
                text.substring(start, boundaryEnd).trim().takeIf(String::isNotEmpty)?.let(sentences::add)
                start = boundaryEnd
                while (start < text.length && text[start].isWhitespace()) start++
                index = start
            } else index++
        }
        var remainder = text.substring(start).trimStart()
        while (remainder.length >= EMERGENCY_LIMIT) {
            val end = emergencyBoundary(remainder)
            remainder.substring(0, end).trim().takeIf(String::isNotEmpty)?.let(sentences::add)
            remainder = remainder.substring(end).trimStart()
        }
        return SegmentationResult(sentences, remainder)
    }

    private fun emergencyBoundary(text: String): Int {
        val upper = minOf(EMERGENCY_LIMIT, text.length)
        for (index in upper - 1 downTo EMERGENCY_MIN) {
            if (text[index] in charArrayOf(',', ';', ':', '—', '–') ||
                (text[index] == '-' && text.getOrNull(index - 1)?.isWhitespace() == true)) return index + 1
        }
        for (index in upper - 1 downTo EMERGENCY_MIN) if (text[index].isWhitespace()) return index
        return upper
    }

    private fun isSentencePeriod(text: String, index: Int): Boolean {
        if (index > 0 && index + 1 < text.length && text[index - 1].isDigit() && text[index + 1].isDigit()) return false
        if (index + 1 < text.length && text[index + 1] == '.') return false
        if (index > 0 && text[index - 1] == '.') return false
        val wordStart = text.lastIndexOfAny(charArrayOf(' ', '\n', '\t'), index - 1) + 1
        val word = text.substring(wordStart, index + 1).lowercase()
        if (word in abbreviations) return false
        if (word.length == 2 && word[0].isLetter()) return false
        return index + 1 == text.length || text[index + 1].isWhitespace()
    }

    companion object {
        const val EMERGENCY_MIN = 100
        const val EMERGENCY_LIMIT = 180
    }
}
