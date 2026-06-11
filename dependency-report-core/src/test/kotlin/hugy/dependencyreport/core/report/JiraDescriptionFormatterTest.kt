package hugy.dependencyreport.core.report

import hugy.dependencyreport.core.json.ObjectMappers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JiraDescriptionFormatterTest {
    private val formatter = JiraDescriptionFormatter()

    @Test
    fun `formats plain text dependency report into jira adf content nodes`() {
        val input = """
            Dependency update review

            dependency-analysis 3.14.0 -> 3.14.1
            Summary: Resolves a performance issue in computeTypeUsage.
            Risk: MEDIUM - Bug fixes present but no breaking changes.
            Release notes: https://github.com/autonomousapps/dependency-analysis-gradle-plugin/blob/main/CHANGELOG.md

            spring-boot 4.0.3 -> 4.0.6
            Summary: Patch upgrade containing security fixes.
            Risk: HIGH - Contains security patches for CVE-2026-XXXX.
            Release notes: https://github.com/spring-projects/spring-boot/releases/tag/v4.0.6

            arrow 2.2.2.1 -> 2.2.3
            Summary: Adds Set-specific Every optics.
            Risk: LOW - Maintains ABI compatibility.
        """.trimIndent()

        val nodes = formatter.format(input)
        val json = ObjectMappers.json.writeValueAsString(nodes)

        assertTrue(json.contains(""""type" : "heading""""))
        assertTrue(json.contains(""""level" : 3"""))
        assertTrue(json.contains(""""text" : "Dependency update review""""))
        assertTrue(json.contains(""""text" : "dependency-analysis """"))
        assertTrue(json.contains(""""text" : "3.14.0 → 3.14.1""""))
        assertTrue(json.contains(""""color" : "#ff991f""""))
        assertTrue(json.contains(""""color" : "#ff5630""""))
        assertTrue(json.contains(""""color" : "#36b37e""""))
        assertTrue(json.contains(""""type" : "inlineCard""""))
        assertTrue(json.contains("https://github.com/spring-projects/spring-boot/releases/tag/v4.0.6"))
        assertEquals(3, Regex(""""type"\s*:\s*"rule"""").findAll(json).count())
        assertFalse(json.contains("arrow 2.2.2.1 -> 2.2.3"))
    }

    @Test
    fun `omits inline card when release notes are missing`() {
        val input = """
            Dependency update review

            arrow 2.2.2.1 -> 2.2.3
            Summary: Adds Set-specific Every optics.
            Risk: LOW - Maintains ABI compatibility.
        """.trimIndent()

        val nodes = formatter.format(input)
        val json = ObjectMappers.json.writeValueAsString(nodes)

        assertTrue(json.contains(""""color" : "#36b37e""""))
        assertFalse(json.contains("inlineCard"))
    }

    @Test
    fun `skips unavailable release notes`() {
        val input = """
            Dependency update review

            sample 1.0.0 -> 1.0.1
            Summary: Patch release.
            Risk: HIGH - Security fix included.
            Release notes: unavailable
        """.trimIndent()

        val nodes = formatter.format(input)
        val json = ObjectMappers.json.writeValueAsString(nodes)

        assertTrue(json.contains(""""color" : "#ff5630""""))
        assertFalse(json.contains("inlineCard"))
    }
}
