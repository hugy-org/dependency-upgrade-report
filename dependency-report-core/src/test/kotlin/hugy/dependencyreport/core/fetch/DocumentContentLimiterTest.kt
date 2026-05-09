package hugy.dependencyreport.core.fetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentContentLimiterTest {
    @Test
    fun `keeps content when it fits within limit`() {
        val limiter = DocumentContentLimiter(maxDocumentContentChars = 20)

        val document = limiter.limit(
            title = "Example",
            sourceUrl = "https://example.test/release",
            content = "short release notes",
            version = "1.2.3",
        )

        assertEquals("short release notes", document.content)
        assertFalse(document.contentTruncated)
        assertEquals(19, document.originalContentLength)
    }

    @Test
    fun `replaces oversized content with url marker`() {
        val limiter = DocumentContentLimiter(maxDocumentContentChars = 10)

        val document = limiter.limit(
            title = "Example",
            sourceUrl = "https://example.test/release",
            content = "0123456789ABCDEF",
            version = "1.2.3",
        )

        assertTrue(document.contentTruncated)
        assertEquals(16, document.originalContentLength)
        assertEquals(
            "Document content omitted because it exceeded 10 characters. See release notes: https://example.test/release",
            document.content,
        )
    }
}
