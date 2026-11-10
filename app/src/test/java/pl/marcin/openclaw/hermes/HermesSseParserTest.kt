package pl.marcin.openclaw.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HermesSseParserTest {
    @Test fun declaresSeparateTranscriptAndMemoryHeaders() {
        assertEquals("X-Hermes-Session-Id", HermesClient.HERMES_SESSION_ID_HEADER)
        assertEquals("X-Hermes-Session-Key", HermesClient.HERMES_SESSION_KEY_HEADER)
    }

    @Test fun parsesOpenAiDelta() {
        val data = HermesSseParser.dataFromLine("data: {\"choices\":[{\"delta\":{\"content\":\"Cześć\"}}]}")
        assertEquals("Cześć", HermesSseParser.contentFromData(data!!))
    }

    @Test fun ignoresKeepaliveAndComments() {
        assertNull(HermesSseParser.dataFromLine(": keepalive"))
        assertNull(HermesSseParser.dataFromLine(""))
    }

    @Test fun parsesNamedToolProgressEvent() {
        assertEquals("hermes.tool.progress", HermesSseParser.eventNameFromLine("event: hermes.tool.progress"))
        val progress = HermesSseParser.toolProgressFromData(
            """{"tool":"execute_code","toolCallId":"call-7","status":"running"}""",
        )!!
        assertEquals("execute_code", progress.tool)
        assertEquals("call-7", progress.callId)
        assertEquals("running", progress.status)
    }

    @Test fun parsesSnakeCaseToolProgressCompatibilityFields() {
        val progress = HermesSseParser.toolProgressFromData(
            """{"tool_name":"web_search","tool_call_id":"call-8","event_type":"tool.completed"}""",
        )!!
        assertEquals("web_search", progress.tool)
        assertEquals("call-8", progress.callId)
        assertEquals("tool.completed", progress.status)
    }

    @Test fun parsesReasoningSeparatelyFromVisibleContent() {
        val data = """{"choices":[{"delta":{"reasoning_content":"analiza"}}]}"""
        assertEquals("analiza", HermesSseParser.reasoningFromData(data))
        assertNull(HermesSseParser.contentFromData(data))
    }

    @Test fun parsesVisibleHermesSessionHistoryAndDropsInternalRows() {
        val history = HermesHistoryParser.fromPayload(
            """{"messages":[
                {"id":"1","role":"system","content":"hidden"},
                {"id":"2","role":"user","content":"Co było wcześniej?"},
                {"id":"3","role":"assistant","content":[{"type":"output_text","text":"Pamiętam rozmowę."}]},
                {"id":"4","role":"assistant","display_kind":"hidden","content":"diagnostic"},
                {"id":"5","role":"tool","content":"raw result"}
            ]}""",
        )

        assertEquals(2, history.size)
        assertEquals("user", history[0].role)
        assertEquals("Co było wcześniej?", history[0].text)
        assertEquals("assistant", history[1].role)
        assertEquals("Pamiętam rozmowę.", history[1].text)
    }

    @Test fun historyParserAcceptsDataEnvelope() {
        val history = HermesHistoryParser.fromPayload(
            """{"data":[{"role":"assistant","content":"Gotowe."}]}""",
        )
        assertEquals("Gotowe.", history.single().text)
    }
}
