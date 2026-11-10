package pl.marcin.openclaw.hermes

import org.junit.Assert.assertEquals
import org.junit.Test

class HermesVisibleTextTest {
    @Test fun preservesRepeatedCharactersAndEmailAddresses() {
        val raw = StringBuilder()
        listOf("Raport wysłano na marcin@2", "2", "4.pl.").forEach(raw::append)
        assertEquals("Raport wysłano na marcin@224.pl.", HermesVisibleText.fromAccumulated(raw.toString()))
    }

    @Test fun removesCompleteToolCallsButPreservesAnswerAroundThem() {
        val raw = "Sprawdzam. <tool_call><function=session_search><parameter=query>sekret</parameter></function></tool_call>Gotowe."
        assertEquals("Sprawdzam. Gotowe.", HermesVisibleText.fromAccumulated(raw))
    }

    @Test fun hidesToolCallSplitAcrossChunksUntilItCloses() {
        val first = "Odpowiedź. <tool_"
        assertEquals("Odpowiedź. ", HermesVisibleText.fromAccumulated(first))
        val second = first + "call><function=x>wewnętrzne"
        assertEquals("Odpowiedź. ", HermesVisibleText.fromAccumulated(second))
        val third = second + "</function></tool_call>Dalszy tekst."
        assertEquals("Odpowiedź. Dalszy tekst.", HermesVisibleText.fromAccumulated(third))
    }

    @Test fun removesAdjacentRepeatedToolCalls() {
        val call = "<tool_call><function=session_search></function></tool_call>"
        assertEquals("", HermesVisibleText.fromAccumulated(call.repeat(7)))
    }
}
