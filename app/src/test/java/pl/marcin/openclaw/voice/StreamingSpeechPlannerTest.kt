package pl.marcin.openclaw.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingSpeechPlannerTest {
    @Test fun growingSnapshotsNeverRepeatEarlierSentence() {
        val planner = StreamingSpeechPlanner()
        assertEquals(listOf("Pierwsze."), planner.accept("Pierwsze. Dru"))
        assertEquals(listOf("Drugie."), planner.accept("Pierwsze. Drugie."))
    }

    @Test fun shorterReplacementDoesNotRestartSpeech() {
        val planner = StreamingSpeechPlanner()
        assertEquals(listOf("Pierwsze."), planner.accept("Pierwsze. Fragment"))
        assertEquals(emptyList<String>(), planner.accept("Pierwsze."))
        assertEquals(emptyList<String>(), planner.accept("Pierwsze. Nowe"))
    }

    @Test fun pauseFlushesLongUnpunctuatedFragmentOnce() {
        val planner = StreamingSpeechPlanner()
        planner.accept("tekst ".repeat(15))
        assertEquals(1, planner.flushAfterPause().size)
        assertEquals(emptyList<String>(), planner.flushAfterPause())
    }

    @Test fun finalFlushesShortRemainder() {
        val planner = StreamingSpeechPlanner()
        assertEquals(listOf("Bez kropki"), planner.accept("Bez kropki", final = true))
    }
}
