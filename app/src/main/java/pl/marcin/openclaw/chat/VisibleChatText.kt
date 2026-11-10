package pl.marcin.openclaw.chat

import org.json.JSONArray
import org.json.JSONObject

object VisibleChatText {
    /** Returns only user-visible text, excluding thinking, tool calls and metadata. */
    fun fromWire(value: String): String {
        val cleaned = pl.marcin.openclaw.attachment.AttachmentPolicy.strip(value)
        val trimmed = cleaned.trim()
        if (trimmed.isEmpty()) return ""
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) return cleaned
        return runCatching {
            when (val parsed = org.json.JSONTokener(trimmed).nextValue()) {
                is JSONArray -> textBlocks(parsed)
                is JSONObject -> textFromObject(parsed)
                else -> ""
            }
        }.getOrDefault("")
    }

    /** Extracts completed or still-growing text blocks from legacy streamed JSON. */
    fun fromPossiblyPartialWire(value: String): String {
        val complete = fromWire(value)
        if (complete.isNotEmpty()) return complete
        if (!value.trimStart().startsWith("[") && !value.trimStart().startsWith("{")) return value

        val results = mutableListOf<String>()
        var cursor = 0
        val marker = Regex("\\\"type\\\"\\s*:\\s*\\\"text\\\"\\s*,\\s*\\\"text\\\"\\s*:\\s*\\\"")
        while (true) {
            val match = marker.find(value, cursor) ?: break
            val decoded = decodeJsonStringPrefix(value, match.range.last + 1)
            if (decoded.text.isNotEmpty()) results += decoded.text
            cursor = decoded.nextIndex.coerceAtLeast(match.range.last + 1)
            if (!decoded.closed) break
        }
        return results.joinToString("\n")
    }

    private data class DecodedPrefix(val text: String, val nextIndex: Int, val closed: Boolean)

    private fun decodeJsonStringPrefix(source: String, start: Int): DecodedPrefix {
        val out = StringBuilder()
        var index = start
        while (index < source.length) {
            val char = source[index++]
            if (char == '"') return DecodedPrefix(out.toString(), index, true)
            if (char != '\\') {
                out.append(char)
                continue
            }
            if (index >= source.length) break
            when (val escaped = source[index++]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                '"', '\\', '/' -> out.append(escaped)
                'u' -> {
                    if (index + 4 > source.length) break
                    source.substring(index, index + 4).toIntOrNull(16)?.let { out.append(it.toChar()) }
                    index += 4
                }
                else -> out.append(escaped)
            }
        }
        return DecodedPrefix(out.toString(), index, false)
    }

    fun fromMessage(message: JSONObject): String {
        val content = message.opt("content") ?: return ""
        return when (content) {
            is JSONArray -> textBlocks(content)
            is String -> fromWire(content)
            else -> ""
        }
    }

    private fun textBlocks(array: JSONArray): String = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("type") != "text") continue
            item.optString("text").takeIf { it.isNotBlank() }?.let(::add)
        }
    }.joinToString("\n")

    private fun textFromObject(value: JSONObject): String =
        if (value.optString("type") == "text") value.optString("text") else ""
}
