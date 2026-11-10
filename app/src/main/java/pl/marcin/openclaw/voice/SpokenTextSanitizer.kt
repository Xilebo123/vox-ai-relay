package pl.marcin.openclaw.voice

/** Converts displayed Markdown into natural speech and omits visual-only content. */
class SpokenTextSanitizer {
    fun sanitize(source: String): String {
        var inCodeBlock = false
        val spokenLines = mutableListOf<String>()
        for (rawLine in source.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inCodeBlock = !inCodeBlock
                continue
            }
            if (inCodeBlock || isVisualOnlyLine(trimmed)) continue
            val cleaned = cleanInline(rawLine).trim()
            if (cleaned.isNotEmpty()) spokenLines += cleaned
        }
        return spokenLines.joinToString("\n")
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun cleanInline(line: String): String = line
        .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)")) { it.groupValues[1] }
        .replace(Regex("<https?://[^>]+>"), "")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("^\\s{0,3}#{1,6}\\s+"), "")
        .replace(Regex("(?<![\\p{L}\\p{N}])#{1,6}(?![\\p{L}\\p{N}])"), " ")
        .replace(Regex("^\\s*>+\\s?"), "")
        .replace(Regex("^\\s*(?:[-+*]|\\d+[.)])\\s+"), "")
        .replace(Regex("[*_~`]+"), "")
        .replace(Regex("[\u2500-\u257F]+"), " ")

    private fun isVisualOnlyLine(line: String): Boolean {
        if (line.isBlank()) return false
        if (Regex("^[-*_~=]{3,}$").matches(line)) return true
        if (line.count { it == '|' } >= 2) return true
        val visible = line.filterNot(Char::isWhitespace)
        if (visible.length < 4) return false
        val graphic = visible.count { !it.isLetterOrDigit() }
        return graphic.toDouble() / visible.length >= 0.65
    }
}
