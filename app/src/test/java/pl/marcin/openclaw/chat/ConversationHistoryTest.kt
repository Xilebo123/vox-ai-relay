package pl.marcin.openclaw.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryTest {
    private val old = ChatMessage("assistant", "Old")
    private val latest = ChatMessage("assistant", "Latest")

    @Test fun initialHistoryPopulatesAnEmptyScreen() {
        assertTrue(ConversationHistory.shouldReplace(emptyList(), listOf(old)))
    }

    @Test fun identicalHistoryDoesNotCauseARepaint() {
        assertFalse(ConversationHistory.shouldReplace(listOf(old), listOf(old)))
    }

    @Test fun shorterStaleHistoryCannotRollBackTheScreen() {
        assertFalse(ConversationHistory.shouldReplace(listOf(old, latest), listOf(old)))
    }

    @Test fun genuinelyNewerHistoryCanBeApplied() {
        assertTrue(ConversationHistory.shouldReplace(listOf(old), listOf(old, latest)))
    }
}
