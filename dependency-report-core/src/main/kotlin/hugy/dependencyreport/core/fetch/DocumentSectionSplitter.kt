package hugy.dependencyreport.core.fetch

internal data class DocumentSection(
    val heading: String?,
    val content: String,
    val index: Int,
    val headingLevel: Int? = null,
)

internal object DocumentSectionSplitter {
    private val markdownHeadingRegex = Regex("""^\s{0,3}(#{1,6})\s+(.+?)\s*$""")
    private val versionHeadingRegex = Regex(
        """^(?:v)?\d+(?:\.\d+)+(?:[-.][0-9A-Za-z]+)*(?:\s*(?:release|changes?|notes?))?$|^(?:release|version|changes?)\s+(?:v)?\d+(?:\.\d+)+(?:[-.][0-9A-Za-z]+)*$""",
        RegexOption.IGNORE_CASE,
    )

    fun split(content: String): List<DocumentSection> {
        val lines = content.lines()
        if (lines.isEmpty()) {
            return listOf(DocumentSection(heading = null, content = content.trim(), index = 0))
        }

        val headings = lines.mapIndexedNotNull { lineIndex, line ->
            extractHeading(line)?.let { heading ->
                ParsedHeading(
                    lineIndex = lineIndex,
                    heading = heading.text,
                    level = heading.level,
                )
            }
        }
        if (headings.isEmpty()) {
            return listOf(DocumentSection(heading = null, content = content.trim(), index = 0))
        }

        val sections = mutableListOf<DocumentSection>()

        val preamble = lines.take(headings.first().lineIndex).joinToString("\n").trim()
        if (preamble.isNotBlank()) {
            sections += DocumentSection(
                heading = null,
                content = preamble,
                index = sections.size,
                headingLevel = null,
            )
        }

        headings.forEachIndexed { index, heading ->
            val nextBoundary = headings
                .drop(index + 1)
                .firstOrNull { it.level <= heading.level }
                ?.lineIndex ?: lines.size
            val sectionContent = lines.subList(heading.lineIndex, nextBoundary).joinToString("\n").trim()
            if (sectionContent.isBlank()) {
                return@forEachIndexed
            }
            sections += DocumentSection(
                heading = heading.heading,
                content = sectionContent,
                index = sections.size,
                headingLevel = heading.level,
            )
        }

        return sections.ifEmpty {
            listOf(DocumentSection(heading = null, content = content.trim(), index = 0))
        }
    }

    private fun extractHeading(line: String): ParsedHeadingText? {
        markdownHeadingRegex.matchEntire(line)?.let {
            return ParsedHeadingText(
                text = it.groupValues[2].trim(),
                level = it.groupValues[1].length,
            )
        }

        val normalized = line.trim()
        if (normalized.isBlank() || normalized.length > 80) {
            return null
        }
        if (
            normalized.startsWith("-") ||
            normalized.startsWith("*") ||
            normalized.startsWith("http://") ||
            normalized.startsWith("https://")
        ) {
            return null
        }
        if (versionHeadingRegex.matches(normalized) && VersionSelection.extractCandidates(normalized).isNotEmpty()) {
            return ParsedHeadingText(text = normalized, level = 2)
        }

        return null
    }

    private data class ParsedHeading(
        val lineIndex: Int,
        val heading: String,
        val level: Int,
    )

    private data class ParsedHeadingText(
        val text: String,
        val level: Int,
    )
}
