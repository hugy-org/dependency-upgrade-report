package hugy.dependencyreport.core.fetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionSelectionTest {
    @Test
    fun `extracts version tokens from tag and title text`() {
        val candidates = VersionSelection.extractCandidates(
            "v0.51.0",
            "Gradle Versions 0.51.0",
            "https://github.com/example/releases/tag/v0.51.0",
        )

        assertEquals(listOf("0.51.0"), candidates.map { it.normalized })
    }

    @Test
    fun `matches only versions within the upgrade window`() {
        val previous = VersionSelection.parse("1.9.24")!!
        val current = VersionSelection.parse("2.0.0")!!

        assertFalse(VersionSelection.isWithinUpgradeWindow(VersionSelection.parse("1.9.24")!!, previous, current))
        assertTrue(VersionSelection.isWithinUpgradeWindow(VersionSelection.parse("2.0.0")!!, previous, current))
        assertFalse(VersionSelection.isWithinUpgradeWindow(VersionSelection.parse("2.3.21")!!, previous, current))
    }

    @Test
    fun `orders prereleases before final release`() {
        val rc = VersionSelection.parse("2.3.21-RC")!!
        val final = VersionSelection.parse("2.3.21")!!

        assertTrue(rc < final)
    }
}
