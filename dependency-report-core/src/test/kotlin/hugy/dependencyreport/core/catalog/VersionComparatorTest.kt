package hugy.dependencyreport.core.catalog

import hugy.dependencyreport.core.model.VersionChangeClassification
import kotlin.test.Test
import kotlin.test.assertEquals

class VersionComparatorTest {
    private val comparator = VersionComparator()

    @Test
    fun `classifies requested version examples`() {
        assertEquals(VersionChangeClassification.UPGRADE, comparator.classify("2.2.21", "2.3.0"))
        assertEquals(VersionChangeClassification.DOWNGRADE, comparator.classify("2.2.21", "2.0.21"))
        assertEquals(VersionChangeClassification.UPGRADE, comparator.classify("2024.0.0", "2025.1.1"))
        assertEquals(VersionChangeClassification.UPGRADE, comparator.classify("26.80.0", "26.81.0"))
        assertEquals(VersionChangeClassification.UPGRADE, comparator.classify("1.1.0.RELEASE", "1.1.1.RELEASE"))
        assertEquals(VersionChangeClassification.UPGRADE, comparator.classify("v1-rev20260413-2.0.0", "v1-rev20260427-2.0.0"))
        assertEquals(VersionChangeClassification.UPGRADE, comparator.classify("2.0.0-alpha.3", "2.0.0"))
        assertEquals(VersionChangeClassification.DOWNGRADE, comparator.classify("2.0.0-alpha.3", "1.9.0"))
    }

    @Test
    fun `returns same when numeric versions are equivalent`() {
        assertEquals(VersionChangeClassification.SAME, comparator.classify("1.1.0.RELEASE", "1.1.0"))
    }

    @Test
    fun `compares prerelease identifiers`() {
        assertEquals(VersionChangeClassification.UPGRADE, comparator.classify("2.0.0-alpha.5", "2.0.0-alpha.6"))
        assertEquals(VersionChangeClassification.DOWNGRADE, comparator.classify("2.0.0-rc.2", "2.0.0-rc.1"))
        assertEquals(VersionChangeClassification.UPGRADE, comparator.classify("2.0.0-alpha.9", "2.0.0-beta.1"))
        assertEquals(VersionChangeClassification.SAME, comparator.classify("2.0.0-alpha.5", "2.0.0-alpha.5"))
    }

    @Test
    fun `returns unknown when numeric components cannot be extracted`() {
        assertEquals(VersionChangeClassification.UNKNOWN, comparator.classify("main", "latest"))
    }
}
