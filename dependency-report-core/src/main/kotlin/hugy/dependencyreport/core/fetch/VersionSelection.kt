package hugy.dependencyreport.core.fetch

private val versionRegex = Regex("""(?i)\b(v?\d+(?:\.\d+)+(?:[-.][0-9a-z]+)*)\b""")

data class ParsedVersion(
    val raw: String,
    val normalized: String,
    val coreParts: List<Int>,
    val suffix: String?,
) : Comparable<ParsedVersion> {
    fun isStable(): Boolean = suffix == null

    override fun compareTo(other: ParsedVersion): Int {
        val maxSize = maxOf(coreParts.size, other.coreParts.size)
        for (index in 0 until maxSize) {
            val left = coreParts.getOrElse(index) { 0 }
            val right = other.coreParts.getOrElse(index) { 0 }
            if (left != right) {
                return left.compareTo(right)
            }
        }

        return when {
            suffix == null && other.suffix == null -> 0
            suffix == null -> 1
            other.suffix == null -> -1
            else -> suffix.compareTo(other.suffix)
        }
    }
}

object VersionSelection {
    fun parse(version: String): ParsedVersion? {
        val match = versionRegex.find(version) ?: return null
        val raw = match.groupValues[1]
        val normalized = raw.removePrefix("v").lowercase()
        val parts = normalized.split('-', limit = 2)
        val coreParts = parts.first().split('.').mapNotNull { it.toIntOrNull() }
        if (coreParts.isEmpty()) {
            return null
        }
        return ParsedVersion(
            raw = raw,
            normalized = normalized,
            coreParts = coreParts,
            suffix = parts.getOrNull(1),
        )
    }

    fun extractCandidates(vararg values: String?): List<ParsedVersion> {
        return values.filterNotNull()
            .flatMap { text ->
                versionRegex.findAll(text).mapNotNull { parse(it.value) }.toList()
            }
            .distinctBy { it.normalized }
    }

    fun isWithinUpgradeWindow(
        candidate: ParsedVersion,
        previous: ParsedVersion,
        current: ParsedVersion,
    ): Boolean {
        return candidate > previous && candidate <= current
    }

    fun <T> selectUpgradeWindowDocuments(
        candidates: List<VersionedValue<T>>,
        targetVersion: ParsedVersion,
        maxReleases: Int,
        includePrereleases: Boolean,
    ): List<VersionedValue<T>> {
        if (candidates.isEmpty()) return emptyList()
        val releaseLimit = maxReleases.coerceAtLeast(1)
        val sorted = candidates.distinctBy { it.version.normalized }.sortedBy { it.version }
        val stable = sorted.filter { it.version.isStable() }
        if (stable.isEmpty()) {
            return sorted.takeLast(releaseLimit)
        }

        val selected = stable.takeLast(releaseLimit).toMutableList()
        val targetCandidate = sorted.firstOrNull { it.version.normalized == targetVersion.normalized }

        if (includePrereleases && selected.size < releaseLimit) {
            val selectedVersions = selected.map { it.version.normalized }.toSet()
            val prereleases = sorted.filter { !it.version.isStable() && it.version.normalized !in selectedVersions }
            selected += prereleases.takeLast(releaseLimit - selected.size)
        }

        if (targetCandidate != null && targetCandidate.version.normalized !in selected.map { it.version.normalized }.toSet()) {
            selected += targetCandidate
        }

        return selected.distinctBy { it.version.normalized }.sortedBy { it.version }
    }

    fun <T> hasEnoughUsefulReleaseCandidates(
        candidates: List<VersionedValue<T>>,
        targetVersion: ParsedVersion,
        maxReleases: Int,
        includePrereleases: Boolean,
    ): Boolean {
        val requiredCount = maxReleases.coerceAtLeast(1)
        val distinctCandidates = candidates.distinctBy { it.version.normalized }
        val hasTargetVersion = distinctCandidates.any { it.version.normalized == targetVersion.normalized }
        val stableCount = distinctCandidates.count { it.version.isStable() }
        val usefulCount = if (stableCount > 0) {
            stableCount
        } else if (includePrereleases) {
            distinctCandidates.size
        } else {
            0
        }
        return (hasTargetVersion && usefulCount >= requiredCount).also { println("Candidates: ${distinctCandidates.map { it.version.normalized }}") }
    }
}

data class VersionedValue<T>(
    val version: ParsedVersion,
    val value: T,
)
