package hugy.dependencyreport.core.catalog

import hugy.dependencyreport.core.model.VersionChangeClassification

class VersionComparator {
    private val preReleaseRegex = Regex("""(?i)(?:^|[.\-])(?:alpha|beta|rc|m\d*|milestone|preview|snapshot)(?:$|[.\-])""")

    fun classify(previousVersion: String, currentVersion: String): VersionChangeClassification {
        val previous = parse(previousVersion) ?: return VersionChangeClassification.UNKNOWN
        val current = parse(currentVersion) ?: return VersionChangeClassification.UNKNOWN

        val numericComparison = compareNumeric(previous.numericComponents, current.numericComponents)
        if (numericComparison < 0) return VersionChangeClassification.UPGRADE
        if (numericComparison > 0) return VersionChangeClassification.DOWNGRADE

        return when {
            previous.isPreRelease && !current.isPreRelease -> VersionChangeClassification.UPGRADE
            !previous.isPreRelease && current.isPreRelease -> VersionChangeClassification.DOWNGRADE
            else -> VersionChangeClassification.SAME
        }
    }

    private fun parse(version: String): ParsedVersion? {
        val normalized = version
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .replace(Regex("""(?i)([.-]release)$"""), "")

        val preReleaseMatch = preReleaseRegex.find(normalized)
        val numericSource = if (preReleaseMatch != null) {
            normalized.substring(0, preReleaseMatch.range.first)
        } else {
            normalized
        }

        val numericComponents = Regex("""\d+""").findAll(numericSource)
            .map { it.value.toInt() }
            .toList()
        if (numericComponents.isEmpty()) {
            return null
        }

        val isPreRelease = preReleaseMatch != null
        return ParsedVersion(numericComponents = numericComponents, isPreRelease = isPreRelease)
    }

    private fun compareNumeric(previous: List<Int>, current: List<Int>): Int {
        val maxSize = maxOf(previous.size, current.size)
        for (index in 0 until maxSize) {
            val left = previous.getOrElse(index) { 0 }
            val right = current.getOrElse(index) { 0 }
            if (left != right) {
                return left.compareTo(right)
            }
        }
        return 0
    }

    private data class ParsedVersion(
        val numericComponents: List<Int>,
        val isPreRelease: Boolean,
    )
}
