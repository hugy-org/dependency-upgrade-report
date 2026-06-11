package hugy.dependencyreport.core.fetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentContextSelectorTest {
    private val selector = DocumentContextSelector(maxDocumentContentChars = 180)

    @Test
    fun `keeps small documents unchanged`() {
        val result = selector.select(
            content = "Short notes for 2.2.21",
            previousVersion = "2.2.20",
            currentVersion = "2.2.21",
        )

        assertFalse(result.applied)
        assertEquals("whole-document", result.strategy)
        assertEquals("Short notes for 2.2.21", result.content)
    }

    @Test
    fun `selects target version section from markdown headings`() {
        val content = """
            # Overview
            General release index and navigation text that should not win.

            ## 2.1.0
            Minor fixes for an older release.

            ## 2.2.21
            Breaking change: update Gradle plugin handling.
            Migration note for Kotlin users.

            ## 2.3.0
            Future release content that is outside the window.
        """.trimIndent()

        val result = selector.select(
            content = content,
            previousVersion = "2.2.20",
            currentVersion = "2.2.21",
        )

        assertTrue(result.applied)
        assertEquals("version-aware-sections", result.strategy)
        assertTrue(result.content.contains("## 2.2.21"))
        assertFalse(result.content.contains("## 2.3.0"))
        assertEquals(listOf("2.2.21"), result.selectedHeadings)
    }

    @Test
    fun `keeps nested subsection content under matching version heading`() {
        val content = """
            ## 1.17.0 (2025-08-01)
            Older release notes.

            ## 1.18.0 (2025-09-16)
            ### Features Added
            Added claims challenge support to AzureDeveloperCliCredential.
            Added AzureIdentityEnvVars expandable string enum.

            ### Bugs Fixed
            Fixed AzurePowerShellCredential handling of XML header responses.

            ### Dependency Updates
            Upgraded azure-core from 1.56.0 to version 1.56.1.

            ## 1.18.1 (2025-10-13)
            ### Other Changes
            Small follow-up release.
        """.trimIndent()

        val result = selector.select(
            content = List(4) { content }.joinToString("\n\n"),
            previousVersion = "1.17.0",
            currentVersion = "1.18.0",
        )

        assertTrue(result.content.contains("## 1.18.0 (2025-09-16)"))
        assertTrue(result.content.contains("### Features Added"))
        assertTrue(result.content.contains("### Bugs Fixed"))
        assertTrue(result.content.contains("### Dependency Updates"))
        assertFalse(result.content.contains("## 1.18.1 (2025-10-13)"))
    }

    @Test
    fun `exact target heading keeps nearby in-window versions and excludes unrelated sections`() {
        val content = """
            ## 2.2.20
            Compatibility notes for the previous stable release.

            ## 2.2.21
            Security fix and plugin adjustment for the target release.

            ## appendix
            Extra material that should not be selected.
        """.trimIndent()

        val result = selector.select(
            content = content,
            previousVersion = "2.2.19",
            currentVersion = "2.2.21",
        )

        assertTrue(result.content.contains("## 2.2.20"))
        assertTrue(result.content.contains("## 2.2.21"))
        assertFalse(result.content.contains("## appendix"))
    }

    @Test
    fun `exact target heading still allows intermediate version headings in the window`() {
        val selector = DocumentContextSelector(maxDocumentContentChars = 600)
        val content = """
            ## 1.18.3 (2026-04-30)
            Current release notes.

            ## 1.18.2 (2026-04-10)
            Intermediate release notes.

            ## 1.18.1 (2026-03-20)
            Earlier release notes.

            ## 1.18.0 (2026-03-01)
            Previous baseline release notes.

            ## 1.17.9 (2026-02-01)
            Outside the window.
        """.trimIndent()

        val result = selector.select(
            content = List(4) { content }.joinToString("\n\n"),
            previousVersion = "1.18.0",
            currentVersion = "1.18.3",
        )

        assertTrue(result.content.contains("## 1.18.3"))
        assertTrue(result.content.contains("## 1.18.2"))
        assertTrue(result.content.contains("## 1.18.1"))
        assertFalse(result.content.contains("## 1.18.0"))
        assertFalse(result.content.contains("## 1.17.9"))
    }

    @Test
    fun `preserves original order when multiple fallback sections are selected`() {
        val content = """
            ## release notes
            Older release 2.2.20 migration.

            ## implementation notes
            Current release 2.2.21 security.
        """.trimIndent()

        val result = selector.select(
            content = content,
            previousVersion = "2.2.19",
            currentVersion = "2.2.21",
        )

        val olderIndex = result.content.indexOf("2.2.20")
        val currentIndex = result.content.indexOf("2.2.21")
        assertTrue(olderIndex >= 0)
        assertTrue(currentIndex > olderIndex)
    }

    @Test
    fun `uses keywords only as tie breaker`() {
        val content = """
            ## 2.2.21
            Ordinary notes for the target release.

            ## 2.2.21 migration
            Migration and compatibility guidance for the target release.
        """.trimIndent()

        val result = selector.select(
            content = List(4) { content }.joinToString("\n\n"),
            previousVersion = "2.2.20",
            currentVersion = "2.2.21",
        )

        assertTrue(result.content.contains("## 2.2.21 migration"))
    }

    @Test
    fun `falls back to first meaningful section when no version signal is found`() {
        val content = """
            # Changelog
            This document is very large but does not reference versions directly.

            ## Overview
            General migration guidance without version mentions.
        """.trimIndent()

        val result = selector.select(
            content = List(6) { content }.joinToString("\n\n"),
            previousVersion = "1.0.0",
            currentVersion = "1.1.0",
        )

        assertTrue(result.applied)
        assertEquals("fallback-first-section", result.strategy)
        assertTrue(result.warnings.isNotEmpty())
    }
}
