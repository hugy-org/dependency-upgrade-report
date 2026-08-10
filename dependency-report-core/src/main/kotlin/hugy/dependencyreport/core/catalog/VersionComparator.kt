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
            previous.isPreRelease && current.isPreRelease -> {
                val preReleaseComparison = comparePreRelease(previous.preReleaseComponents, current.preReleaseComponents)
                when {
                    preReleaseComparison < 0 -> VersionChangeClassification.UPGRADE
                    preReleaseComparison > 0 -> VersionChangeClassification.DOWNGRADE
                    else -> VersionChangeClassification.SAME
                }
            }
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
        val preReleaseComponents = preReleaseMatch
            ?.let { normalized.substring(it.range.first).trimStart('.', '-') }
            ?.split('.', '-')
            .orEmpty()
        return ParsedVersion(
            numericComponents = numericComponents,
            isPreRelease = isPreRelease,
            preReleaseComponents = preReleaseComponents,
        )
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

    private fun comparePreRelease(previous: List<String>, current: List<String>): Int {
        val maxSize = maxOf(previous.size, current.size)
        for (index in 0 until maxSize) {
            val left = previous.getOrNull(index) ?: return -1
            val right = current.getOrNull(index) ?: return 1
            val leftNumber = left.toIntOrNull()
            val rightNumber = right.toIntOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right, ignoreCase = true)
            }
            if (comparison != 0) return comparison
        }
        return 0
    }

    private data class ParsedVersion(
        val numericComponents: List<Int>,
        val isPreRelease: Boolean,
        val preReleaseComponents: List<String>,
    )
}
