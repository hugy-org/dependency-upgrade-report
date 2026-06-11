package hugy.dependencyreport.core.report

class JiraDescriptionFormatter {
    fun format(description: String): List<Map<String, Any>> {
        val lines = description.lines()
        val firstNonEmptyIndex = lines.indexOfFirst { it.isNotBlank() }
        if (firstNonEmptyIndex == -1) {
            return emptyList()
        }

        val title = lines[firstNonEmptyIndex].trim()
        val blocks = splitBlocks(lines.drop(firstNonEmptyIndex + 1))
        val nodes = mutableListOf<Map<String, Any>>()
        nodes += heading(level = 3, text = title)

        blocks.forEach { block ->
            val parsed = parseBlock(block)
            nodes += paragraph(
                textNode("${parsed.name} ", marks = listOf(strongMark())),
                textNode(parsed.versionChange.replace("->", "→")),
            )
            nodes += paragraph(
                textNode("Summary: ", marks = listOf(strongMark())),
                textNode(parsed.summary),
            )

            val riskColor = colorForRisk(parsed.riskLevel)
            val riskMarks = listOfNotNull(strongMark(), riskColor?.let(::textColorMark))
            val riskValueMarks = listOfNotNull(riskColor?.let(::textColorMark))
            nodes += paragraph(
                textNode("Risk: ", marks = riskMarks),
                textNode(parsed.riskText, marks = riskValueMarks),
            )

            if (!parsed.releaseNotes.isNullOrBlank() && parsed.releaseNotes != "unavailable") {
                nodes += paragraph(
                    textNode("Release notes: ", marks = listOf(emMark())),
                    inlineCard(parsed.releaseNotes),
                )
            }

            nodes += mapOf("type" to "rule")
        }

        return nodes
    }

    private fun splitBlocks(lines: List<String>): List<List<String>> {
        val blocks = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        lines.forEach { line ->
            if (line.isBlank()) {
                if (current.isNotEmpty()) {
                    blocks += current.toList()
                    current.clear()
                }
            } else {
                current += line.trim()
            }
        }
        if (current.isNotEmpty()) {
            blocks += current.toList()
        }
        return blocks
    }

    private fun parseBlock(lines: List<String>): ParsedDependencyBlock {
        require(lines.isNotEmpty()) { "Dependency block must not be empty" }

        val titleLine = lines.first()
        val titleMatch = TITLE_REGEX.matchEntire(titleLine)
            ?: throw IllegalArgumentException("Invalid dependency title line: $titleLine")
        val summaryLine = lines.firstOrNull { it.startsWith("Summary: ") }
            ?: throw IllegalArgumentException("Missing Summary line in dependency block: $titleLine")
        val riskLine = lines.firstOrNull { it.startsWith("Risk: ") }
            ?: throw IllegalArgumentException("Missing Risk line in dependency block: $titleLine")
        val releaseNotesLine = lines.firstOrNull { it.startsWith("Release notes: ") }

        val riskText = riskLine.removePrefix("Risk: ").trim()
        val riskLevel = RISK_REGEX.find(riskText)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Invalid Risk line in dependency block: $riskLine")

        return ParsedDependencyBlock(
            name = titleMatch.groupValues[1],
            versionChange = titleMatch.groupValues[2].trim(),
            summary = summaryLine.removePrefix("Summary: ").trim(),
            riskLevel = riskLevel,
            riskText = riskText,
            releaseNotes = releaseNotesLine?.removePrefix("Release notes: ")?.trim(),
        )
    }

    private fun colorForRisk(riskLevel: String): String? {
        return when (riskLevel) {
            "LOW" -> "#36b37e"
            "MEDIUM" -> "#ff991f"
            "HIGH" -> "#ff5630"
            else -> null
        }
    }

    private fun heading(level: Int, text: String): Map<String, Any> {
        return mapOf(
            "type" to "heading",
            "attrs" to mapOf("level" to level),
            "content" to listOf(textNode(text)),
        )
    }

    private fun paragraph(vararg content: Map<String, Any>): Map<String, Any> {
        return mapOf(
            "type" to "paragraph",
            "content" to content.toList(),
        )
    }

    private fun inlineCard(url: String): Map<String, Any> {
        return mapOf(
            "type" to "inlineCard",
            "attrs" to mapOf("url" to url),
        )
    }

    private fun textNode(
        text: String,
        marks: List<Map<String, Any>> = emptyList(),
    ): Map<String, Any> {
        return buildMap {
            put("type", "text")
            put("text", text)
            if (marks.isNotEmpty()) {
                put("marks", marks)
            }
        }
    }

    private fun strongMark(): Map<String, Any> = mapOf("type" to "strong")

    private fun emMark(): Map<String, Any> = mapOf("type" to "em")

    private fun textColorMark(color: String): Map<String, Any> =
        mapOf("type" to "textColor", "attrs" to mapOf("color" to color))

    private data class ParsedDependencyBlock(
        val name: String,
        val versionChange: String,
        val summary: String,
        val riskLevel: String,
        val riskText: String,
        val releaseNotes: String?,
    )

    private companion object {
        val TITLE_REGEX = Regex("""^(.+?)\s+(\S+\s*->\s*\S+)$""")
        val RISK_REGEX = Regex("""^(LOW|MEDIUM|HIGH)\b""")
    }
}
