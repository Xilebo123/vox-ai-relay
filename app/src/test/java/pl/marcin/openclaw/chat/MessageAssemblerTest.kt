package pl.marcin.openclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageAssemblerTest {
    private val assembler = MessageAssembler()
    private val active = ActiveRun("main", "run-1")

    @Test fun appendsIncrementalDelta() {
        val next = assembler.apply(active.copy(text = "A"), ChatStreamEvent("main", "run-1", 1, "delta", "B"))
        assertEquals("AB", next?.text)
    }

    @Test fun doesNotDropRepeatedIncrementalCharacters() {
        val first = assembler.apply(active, ChatStreamEvent("main", "run-1", 1, "delta", "2"))!!
        val second = assembler.apply(first, ChatStreamEvent("main", "run-1", 2, "delta", "2"))!!
        val third = assembler.apply(second, ChatStreamEvent("main", "run-1", 3, "delta", "4.pl"))!!
        assertEquals("224.pl", third.text)
    }

    @Test fun snapshotDoesNotPolluteLegacyRawWireBuffer() {
        val next = assembler.apply(active, ChatStreamEvent(
            "main", "run-1", 1, "delta", deltaText = "ignored", snapshotText = "marcin@224.pl",
        ))!!
        assertEquals("marcin@224.pl", next.text)
        assertEquals("", next.rawWireText)
    }

    @Test fun usesAuthoritativeSnapshotWhenPresent() {
        val next = assembler.apply(active.copy(text = "wrong"), ChatStreamEvent("main", "run-1", 2, "delta", "x", "Correct"))
        assertEquals("Correct", next?.text)
    }

    @Test fun ignoresWrongSessionRunAndReplay() {
        assertNull(assembler.apply(active, ChatStreamEvent("other", "run-1", 1, "delta", "x")))
        assertNull(assembler.apply(active, ChatStreamEvent("main", "other", 1, "delta", "x")))
        assertNull(assembler.apply(active.copy(lastChatSeq = 2), ChatStreamEvent("main", "run-1", 2, "delta", "x")))
    }

    @Test fun terminalReplayCanBeHandledByCoordinatorEvenWhenTextIsIgnored() {
        val terminal = ChatStreamEvent("main", "run-1", 2, "final", snapshotText = "Done")
        assertNull(assembler.apply(active.copy(lastChatSeq = 2, text = "Done"), terminal))
        assertEquals("final", terminal.state)
    }

    @Test fun assemblesVisibleTextFromChunkedLegacyJsonWithoutThinking() {
        val first = assembler.apply(active, ChatStreamEvent(
            "main", "run-1", 1, "delta",
            "[{\"type\":\"thinking\",\"thinking\":\"secret\"},{\"type\":\"text\",\"text\":\"Once up",
        ))!!
        assertEquals("Once up", first.text)
        val second = assembler.apply(first, ChatStreamEvent("main", "run-1", 2, "delta", "on a time"))!!
        assertEquals("Once upon a time", second.text)
    }


    @Test fun tracksAgentAndChatSequencesIndependently() {
        val agent = assembler.apply(active, ChatStreamEvent(
            "main", "run-1", 7, "delta", "Hello",
            source = ChatStreamSource.AGENT,
        ))!!
        val finalChat = assembler.apply(agent, ChatStreamEvent(
            "main", "run-1", 1, "final", snapshotText = "Hello world",
        ))!!
        assertEquals("Hello world", finalChat.text)
        assertEquals(7, finalChat.lastAgentSeq)
        assertEquals(1, finalChat.lastChatSeq)
    }

    @Test fun agentTextIsAnAuthoritativeGrowingSnapshot() {
        val first = assembler.apply(active, ChatStreamEvent(
            "main", "run-1", 1, "delta", snapshotText = "First",
            source = ChatStreamSource.AGENT,
        ))!!
        val second = assembler.apply(first, ChatStreamEvent(
            "main", "run-1", 2, "delta", snapshotText = "First second",
            source = ChatStreamSource.AGENT,
        ))!!
        assertEquals("First second", second.text)
    }

    @Test fun acceptsCanonicalGatewayAliasForMainSession() {
        val next = assembler.apply(active, ChatStreamEvent(
            "agent:main:main", "run-1", 1, "delta", snapshotText = "Streaming",
            source = ChatStreamSource.AGENT,
        ))
        assertEquals("Streaming", next?.text)
    }

    @Test fun finalChatDoesNotDuplicateTextAlreadyStreamedByAgent() {
        val streamed = active.copy(text = "Temperatura wynosi 18 stopni.", lastAgentSeq = 4)
        val final = assembler.apply(streamed, ChatStreamEvent(
            "main", "run-1", 1, "final", deltaText = "Temperatura wynosi 18 stopni.",
        ))
        assertEquals("Temperatura wynosi 18 stopni.", final?.text)
    }

    @Test fun fullChatUpdateExtendsAgentPrefixInsteadOfAppendingIt() {
        val streamed = active.copy(text = "Temperatura wynosi", lastAgentSeq = 4)
        val update = assembler.apply(streamed, ChatStreamEvent(
            "main", "run-1", 1, "delta", deltaText = "Temperatura wynosi 18 stopni.",
        ))
        assertEquals("Temperatura wynosi 18 stopni.", update?.text)
    }
}
