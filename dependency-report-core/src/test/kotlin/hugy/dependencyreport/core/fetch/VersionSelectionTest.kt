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

    @Test
    fun `selection prefers stable releases and excludes prerelease noise`() {
        val selected = VersionSelection.selectUpgradeWindowDocuments(
            candidates = listOf(
                VersionedValue(VersionSelection.parse("1.9.25")!!, "1.9.25"),
                VersionedValue(VersionSelection.parse("2.0.0-RC1")!!, "2.0.0-RC1"),
                VersionedValue(VersionSelection.parse("2.0.0-RC2")!!, "2.0.0-RC2"),
                VersionedValue(VersionSelection.parse("2.0.0")!!, "2.0.0"),
            ),
            targetVersion = VersionSelection.parse("2.0.0")!!,
            maxReleases = 5,
            includePrereleases = false,
        )

        assertEquals(listOf("1.9.25", "2.0.0"), selected.map { it.value })
    }

    @Test
    fun `scan is not considered sufficient until target and enough stable releases are present`() {
        val targetVersion = VersionSelection.parse("2.2.0")!!
        val prereleaseHeavyCandidates = listOf(
            VersionedValue(VersionSelection.parse("2.0.0-RC1")!!, "2.0.0-RC1"),
            VersionedValue(VersionSelection.parse("2.0.0-RC2")!!, "2.0.0-RC2"),
            VersionedValue(VersionSelection.parse("2.1.0-RC1")!!, "2.1.0-RC1"),
            VersionedValue(VersionSelection.parse("2.1.0")!!, "2.1.0"),
            VersionedValue(VersionSelection.parse("2.1.10")!!, "2.1.10"),
            VersionedValue(VersionSelection.parse("2.2.0-RC1")!!, "2.2.0-RC1"),
            VersionedValue(VersionSelection.parse("2.2.0")!!, "2.2.0"),
        )

        assertFalse(
            VersionSelection.hasEnoughUsefulReleaseCandidates(
                candidates = prereleaseHeavyCandidates,
                targetVersion = targetVersion,
                maxReleases = 5,
                includePrereleases = false,
            ),
        )

        val enoughStableCandidates = prereleaseHeavyCandidates + listOf(
            VersionedValue(VersionSelection.parse("1.9.20")!!, "1.9.20"),
            VersionedValue(VersionSelection.parse("2.0.0")!!, "2.0.0"),
            VersionedValue(VersionSelection.parse("2.1.20")!!, "2.1.20"),
        )

        assertTrue(
            VersionSelection.hasEnoughUsefulReleaseCandidates(
                candidates = enoughStableCandidates,
                targetVersion = targetVersion,
                maxReleases = 5,
                includePrereleases = false,
            ),
        )
    }

    @Test
    fun `scan can stop early when only the target release is needed`() {
        val targetVersion = VersionSelection.parse("2.2.0")!!
        val candidates = listOf(
            VersionedValue(VersionSelection.parse("2.2.0-RC1")!!, "2.2.0-RC1"),
            VersionedValue(VersionSelection.parse("2.2.0")!!, "2.2.0"),
        )

        assertTrue(
            VersionSelection.hasEnoughUsefulReleaseCandidates(
                candidates = candidates,
                targetVersion = targetVersion,
                maxReleases = 1,
                includePrereleases = false,
            ),
        )
    }

    @Test
    fun `selection fills with prereleases only when enabled and stable releases are insufficient`() {
        val selected = VersionSelection.selectUpgradeWindowDocuments(
            candidates = listOf(
                VersionedValue(VersionSelection.parse("2.2.20-RC2")!!, "2.2.20-RC2"),
                VersionedValue(VersionSelection.parse("2.2.20")!!, "2.2.20"),
                VersionedValue(VersionSelection.parse("2.2.21-RC")!!, "2.2.21-RC"),
                VersionedValue(VersionSelection.parse("2.2.21-RC2")!!, "2.2.21-RC2"),
                VersionedValue(VersionSelection.parse("2.2.21")!!, "2.2.21"),
            ),
            targetVersion = VersionSelection.parse("2.2.21")!!,
            maxReleases = 5,
            includePrereleases = true,
        )

        assertEquals(
            listOf("2.2.20-RC2", "2.2.20", "2.2.21-RC", "2.2.21-RC2", "2.2.21"),
            selected.map { it.value },
        )
    }

    @Test
    fun `selection keeps only stable releases when prereleases are disabled`() {
        val selected = VersionSelection.selectUpgradeWindowDocuments(
            candidates = listOf(
                VersionedValue(VersionSelection.parse("2.2.20-RC2")!!, "2.2.20-RC2"),
                VersionedValue(VersionSelection.parse("2.2.20")!!, "2.2.20"),
                VersionedValue(VersionSelection.parse("2.2.21-RC")!!, "2.2.21-RC"),
                VersionedValue(VersionSelection.parse("2.2.21-RC2")!!, "2.2.21-RC2"),
                VersionedValue(VersionSelection.parse("2.2.21")!!, "2.2.21"),
            ),
            targetVersion = VersionSelection.parse("2.2.21")!!,
            maxReleases = 5,
            includePrereleases = false,
        )

        assertEquals(listOf("2.2.20", "2.2.21"), selected.map { it.value })
    }
}
