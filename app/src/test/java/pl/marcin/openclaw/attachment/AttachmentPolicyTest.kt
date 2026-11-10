package pl.marcin.openclaw.attachment

import org.junit.Assert.*
import org.junit.Test

class AttachmentPolicyTest {
    @Test fun policyIsRemoved() = assertEquals("Please inspect report.pdf", AttachmentPolicy.strip("Please inspect report.pdf\n\n${AttachmentPolicy.prompt}"))
    @Test fun ordinaryTextIsUnchanged() = assertEquals("hello", AttachmentPolicy.strip("hello"))
    @Test fun limitsAndHybridPolicyAreDefined() {
        assertEquals(5, AttachmentRules.MAX_FILES)
        assertEquals(20L * 1024 * 1024, AttachmentRules.MAX_FILE_BYTES)
        assertEquals(30L * 1024 * 1024, AttachmentRules.MAX_TOTAL_BYTES)
        assertTrue(AttachmentPolicy.prompt.contains("OCR"))
        assertFalse(AttachmentPolicy.strip(AttachmentPolicy.prompt).contains("OCR"))
    }
}
