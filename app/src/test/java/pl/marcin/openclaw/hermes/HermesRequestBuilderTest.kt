package pl.marcin.openclaw.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.marcin.openclaw.attachment.VisionImage

class HermesRequestBuilderTest {
    @Test fun keepsPlainTextMessageWithoutImages() {
        val message = HermesRequestBuilder.userMessage("Cześć", emptyList())
        assertEquals("user", message.getString("role"))
        assertEquals("Cześć", message.getString("content"))
    }

    @Test fun createsOrderedOpenAiVisionContent() {
        val message = HermesRequestBuilder.userMessage("Przetłumacz", listOf(
            VisionImage("image/jpeg", "AAA", "raport.pdf — page 1 of 2"),
            VisionImage("image/jpeg", "BBB", "raport.pdf — page 2 of 2"),
        ))
        val content = message.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("Przetłumacz", content.getJSONObject(0).getString("text"))
        assertEquals("Attachment: raport.pdf — page 1 of 2", content.getJSONObject(1).getString("text"))
        assertTrue(content.getJSONObject(2).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,AAA"))
        assertEquals("Attachment: raport.pdf — page 2 of 2", content.getJSONObject(3).getString("text"))
        assertEquals("high", content.getJSONObject(4).getJSONObject("image_url").getString("detail"))
    }
}
