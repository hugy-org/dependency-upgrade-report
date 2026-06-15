package hugy.dependencyreport.core.report

import hugy.dependencyreport.core.fetch.VersionSelection
import hugy.dependencyreport.core.model.ExecutionManifest
import hugy.dependencyreport.core.model.GeneratedReport
import hugy.dependencyreport.core.model.ReleaseSourceType
import hugy.dependencyreport.core.model.RenderedOutputs
import hugy.dependencyreport.core.model.UpgradeReportEntry

class ReportRenderer {
    fun render(
        entries: List<UpgradeReportEntry>,
        llmAttempted: Boolean,
        llmSucceeded: Boolean,
        warnings: List<String>,
    ): GeneratedReport {
        val description = renderUnifiedDescription(entries)
        return GeneratedReport(
            entries = entries,
            outputs = RenderedOutputs(
                commitBody = renderCommitBody(entries),
                unifiedDescription = description,
            ),
            manifest = ExecutionManifest(
                sourceResolverOrder = listOf("sources", "inferredMavenPom", "unresolved"),
                llmAttempted = llmAttempted,
                llmSucceeded = llmSucceeded,
                fallbackCount = entries.count { it.fallbackUsed },
                unresolvedCount = entries.count { it.sourceResolution.source.type == ReleaseSourceType.UNRESOLVED },
            ),
            warnings = warnings,
        )
    }

    private fun renderCommitBody(entries: List<UpgradeReportEntry>): String {
        return buildString {
            entries.forEach { entry ->
                appendLine("- ${deterministicTitle(entry)}")
                appendLine("  Release notes: ${targetVersionUrl(entry)}")
            }
        }.trim()
    }

    private fun renderUnifiedDescription(entries: List<UpgradeReportEntry>): String {
        return buildString {
            appendLine("Dependency update review")
            appendLine()
            entries.forEach { entry ->
                val title = deterministicTitle(entry)
                appendLine(title)
                val body = renderBody(title, entry.narrative.summary)
                if (body != null) {
                    appendLine("Summary: $body")
                }
                appendLine("Risk: ${entry.narrative.riskAssessment.level} - ${entry.narrative.riskAssessment.summary}")
                appendLine("Release notes: ${targetVersionUrl(entry)}")
                appendLine()
            }
        }.trim()
    }

    private fun deterministicTitle(entry: UpgradeReportEntry): String {
        val change = entry.target.change
        return "${change.alias} ${change.previousVersion} -> ${change.currentVersion}"
    }

    private fun renderBody(headline: String, body: String): String? {
        val normalizedHeadline = normalizeForComparison(headline)
        val normalizedBody = normalizeForComparison(body)
        return if (normalizedBody.isBlank() || normalizedBody == normalizedHeadline) {
            null
        } else {
            body.trim()
        }
    }

    private fun normalizeForComparison(value: String): String {
        return value
            .lowercase()
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('.', ':', ';')
    }

    private fun targetVersionUrl(entry: UpgradeReportEntry): String {
        val targetVersion = VersionSelection.parse(entry.target.change.currentVersion)?.normalized
        return entry.documents.firstOrNull { document ->
            VersionSelection.parse(document.version ?: "")?.normalized == targetVersion
        }?.sourceUrl ?: entry.sourceResolution.source.sourceUrl ?: "unavailable"
    }
}
