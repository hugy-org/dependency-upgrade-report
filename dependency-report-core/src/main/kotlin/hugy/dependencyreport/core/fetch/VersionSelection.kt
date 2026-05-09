package hugy.dependencyreport.core.fetch

private val versionRegex = Regex("""(?i)\b(v?\d+(?:\.\d+)+(?:[-.][0-9a-z]+)*)\b""")

data class ParsedVersion(
    val raw: String,
    val normalized: String,
    val coreParts: List<Int>,
    val suffix: String?,
) : Comparable<ParsedVersion> {
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
}
