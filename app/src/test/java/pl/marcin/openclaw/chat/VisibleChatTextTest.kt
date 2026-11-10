package pl.marcin.openclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleChatTextTest {
    @Test fun hidesThinkingAndToolCalls() {
        val wire = """[{"type":"thinking","thinking":"secret"},{"type":"toolCall","name":"exec"},{"type":"text","text":"Visible answer"}]"""
        assertEquals("Visible answer", VisibleChatText.fromWire(wire))
    }

    @Test fun hidesInternalOnlyPayload() {
        assertEquals("", VisibleChatText.fromWire("""[{"type":"thinking","thinking":"secret"}]"""))
    }

    @Test fun preservesOrdinaryDelta() {
        assertEquals("Normal text", VisibleChatText.fromWire("Normal text"))
    }

    @Test fun extractsTextFromIncompleteLegacyJson() {
        val partial = "[{\"type\":\"thinking\",\"thinking\":\"secret\"},{\"type\":\"text\",\"text\":\"Once upon a ti"
        assertEquals("Once upon a ti", VisibleChatText.fromPossiblyPartialWire(partial))
    }

    @Test fun incrementallyDecodesEscapedNewlines() {
        val partial = "[{\"type\":\"text\",\"text\":\"First.\\n\\nSecond"
        assertEquals("First.\n\nSecond", VisibleChatText.fromPossiblyPartialWire(partial))
    }
}
