package hugy.dependencyreport.core.fetch

internal data class SelectedDocumentContext(
    val content: String,
    val applied: Boolean,
    val strategy: String,
    val originalContentLength: Int,
    val selectedContentLength: Int,
    val selectedHeadings: List<String>,
    val warnings: List<String>,
)

internal class DocumentContextSelector(
    private val maxDocumentContentChars: Int,
) {
    private val tieBreakerKeywords = listOf(
        "breaking",
        "migration",
        "deprecated",
        "removed",
        "security",
        "compatibility",
        "gradle",
        "maven",
        "plugin",
    )

    fun select(
        content: String,
        previousVersion: String,
        currentVersion: String,
    ): SelectedDocumentContext {
        val normalizedContent = content.trim()
        val originalLength = normalizedContent.length
        if (originalLength <= maxDocumentContentChars) {
            return SelectedDocumentContext(
                content = normalizedContent,
                applied = false,
                strategy = "whole-document",
                originalContentLength = originalLength,
                selectedContentLength = originalLength,
                selectedHeadings = emptyList(),
                warnings = emptyList(),
            )
        }

        val previous = VersionSelection.parse(previousVersion)
        val current = VersionSelection.parse(currentVersion)
        if (previous == null || current == null) {
            return fallbackFirstSection(normalizedContent, originalLength, "Unable to parse upgrade window versions.")
        }

        val sections = DocumentSectionSplitter.split(normalizedContent)
        if (sections.isEmpty()) {
            return fallbackFirstSection(normalizedContent, originalLength, "No document sections were detected.")
        }

        val scoredSections = sections.map { section ->
            val headingCandidates = VersionSelection.extractCandidates(section.heading)
            val bodyCandidates = VersionSelection.extractCandidates(section.content)
            val exactCurrentHeadingMatch = headingCandidates.any { it.normalized == current.normalized }
            val exactCurrentBodyMatch = bodyCandidates.any { it.normalized == current.normalized }
            val inWindowHeadingMatch = headingCandidates.any { VersionSelection.isWithinUpgradeWindow(it, previous, current) }
            val versionScore = headingCandidates.sumOf {
                if (VersionSelection.isWithinUpgradeWindow(it, previous, current)) 4 else 0
            } + bodyCandidates.sumOf {
                if (VersionSelection.isWithinUpgradeWindow(it, previous, current)) 2 else 0
            }
            val keywordScore = tieBreakerKeywords.count { keyword ->
                section.content.contains(keyword, ignoreCase = true) ||
                    (section.heading?.contains(keyword, ignoreCase = true) == true)
            }

            ScoredSection(
                section = section,
                versionScore = versionScore,
                keywordScore = keywordScore,
                exactCurrentHeadingMatch = exactCurrentHeadingMatch,
                exactCurrentBodyMatch = exactCurrentBodyMatch,
                inWindowHeadingMatch = inWindowHeadingMatch,
            )
        }

        val exactCurrentHeadingMatches = scoredSections.filter { it.exactCurrentHeadingMatch }
        val candidatePool = when {
            exactCurrentHeadingMatches.isNotEmpty() -> scoredSections.filter { it.inWindowHeadingMatch }
            else -> scoredSections.filter { it.versionScore > 0 }
        }

        val selected = candidatePool
            .sortedWith(
                compareByDescending<ScoredSection> { if (it.exactCurrentHeadingMatch) 1 else 0 }
                    .thenByDescending { if (it.exactCurrentBodyMatch) 1 else 0 }
                    .thenByDescending { it.versionScore }
                    .thenByDescending { it.keywordScore }
                    .thenBy { it.section.index },
            )
            .fold(mutableListOf<ScoredSection>() to 0) { (chosen, size), candidate ->
                val candidateLength = candidate.section.content.length
                if (chosen.isEmpty() || size + candidateLength <= maxDocumentContentChars) {
                    chosen += candidate
                    chosen to (size + candidateLength)
                } else {
                    chosen to size
                }
            }
            .first
            .sortedBy { it.section.index }

        if (selected.isEmpty()) {
            return fallbackFirstSection(
                normalizedContent = normalizedContent,
                originalLength = originalLength,
                warning = "No version-matching section was found; selected the first meaningful section instead.",
            )
        }

        val selectedContent = selected.joinToString("\n\n") { it.section.content.trim() }.trim()
        return SelectedDocumentContext(
            content = selectedContent,
            applied = true,
            strategy = "version-aware-sections",
            originalContentLength = originalLength,
            selectedContentLength = selectedContent.length,
            selectedHeadings = selected.mapNotNull { it.section.heading },
            warnings = emptyList(),
        )
    }

    private fun fallbackFirstSection(
        normalizedContent: String,
        originalLength: Int,
        warning: String,
    ): SelectedDocumentContext {
        val firstSection = DocumentSectionSplitter.split(normalizedContent)
            .firstOrNull { it.content.isNotBlank() }
        val selectedContent = firstSection?.content?.trim().takeUnless { it.isNullOrBlank() } ?: normalizedContent
        return SelectedDocumentContext(
            content = selectedContent,
            applied = true,
            strategy = "fallback-first-section",
            originalContentLength = originalLength,
            selectedContentLength = selectedContent.length,
            selectedHeadings = listOfNotNull(firstSection?.heading),
            warnings = listOf(warning),
        )
    }

    private data class ScoredSection(
        val section: DocumentSection,
        val versionScore: Int,
        val keywordScore: Int,
        val exactCurrentHeadingMatch: Boolean,
        val exactCurrentBodyMatch: Boolean,
        val inWindowHeadingMatch: Boolean,
    )
}
