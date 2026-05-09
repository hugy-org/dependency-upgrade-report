package hugy.dependencyreport.core.report

import hugy.dependencyreport.core.model.ExecutionManifest
import hugy.dependencyreport.core.model.GeneratedReport
import hugy.dependencyreport.core.model.RenderedOutputs
import hugy.dependencyreport.core.model.RiskLevel
import hugy.dependencyreport.core.model.UpgradeReportEntry

class ReportRenderer {
    fun render(entries: List<UpgradeReportEntry>, llmAttempted: Boolean, llmSucceeded: Boolean): GeneratedReport {
        val renderedOutputs = RenderedOutputs(
            summaryText = renderSummary(entries),
            commitBody = renderCommitBody(entries),
            mergeRequestDescription = renderMergeRequestDescription(entries),
            jiraDescription = renderJiraDescription(entries),
            reviewerChecklist = renderReviewerChecklist(entries),
            riskSummary = renderRiskSummary(entries),
        )
        return GeneratedReport(
            entries = entries,
            outputs = renderedOutputs,
            manifest = ExecutionManifest(
                sourceResolverOrder = listOf("sourceRegistry", "githubRepositories", "changelogUrls", "unresolved"),
                llmAttempted = llmAttempted,
                llmSucceeded = llmSucceeded,
                fallbackCount = entries.count { it.fallbackUsed },
                unresolvedCount = entries.count { it.sourceResolution.source.type == hugy.dependencyreport.core.model.ReleaseSourceType.UNRESOLVED },
            ),
        )
    }

    private fun renderSummary(entries: List<UpgradeReportEntry>): String {
        return buildString {
            appendLine("Dependency Upgrade Summary")
            appendLine()
            entries.forEach { entry ->
                appendLine("- ${entry.narrative.headline}")
                appendLine("  Kind: ${entry.target.kind}")
                appendLine("  Source: ${entry.sourceResolution.source.type} via ${entry.sourceResolution.matchedBy}")
                appendLine("  Risk: ${entry.narrative.riskAssessment.level}")
            }
        }.trim()
    }

    private fun renderCommitBody(entries: List<UpgradeReportEntry>): String {
        return buildString {
            appendLine("Dependency upgrade report")
            appendLine()
            entries.forEach { entry ->
                appendLine("- ${entry.narrative.headline}")
                appendLine("  ${entry.narrative.summary}")
            }
        }.trim()
    }

    private fun renderMergeRequestDescription(entries: List<UpgradeReportEntry>): String {
        return buildString {
            appendLine("## Dependency upgrade report")
            appendLine()
            entries.forEach { entry ->
                appendLine("### ${entry.narrative.headline}")
                appendLine()
                appendLine(entry.narrative.summary)
                appendLine()
                appendLine("Source: ${entry.sourceResolution.source.displayName}")
                appendLine("Risk: ${entry.narrative.riskAssessment.level}")
                appendLine()
            }
        }.trim()
    }

    private fun renderJiraDescription(entries: List<UpgradeReportEntry>): String {
        return buildString {
            appendLine("Dependency upgrade review")
            appendLine()
            entries.forEach { entry ->
                appendLine("* ${entry.narrative.headline}")
                appendLine("  ${entry.narrative.summary}")
            }
        }.trim()
    }

    private fun renderReviewerChecklist(entries: List<UpgradeReportEntry>): String {
        return buildString {
            appendLine("# Reviewer checklist")
            appendLine()
            entries.forEach { entry ->
                appendLine("## ${entry.narrative.headline}")
                entry.narrative.reviewerChecklist.forEach { item ->
                    appendLine("- [ ] $item")
                }
                appendLine()
            }
        }.trim()
    }

    private fun renderRiskSummary(entries: List<UpgradeReportEntry>): String {
        val counts = RiskLevel.entries.associateWith { level ->
            entries.count { it.narrative.riskAssessment.level == level }
        }
        return buildString {
            appendLine("# Risk summary")
            appendLine()
            counts.forEach { (level, count) ->
                appendLine("- $level: $count")
            }
            appendLine()
            entries.forEach { entry ->
                appendLine("- ${entry.narrative.headline}: ${entry.narrative.riskAssessment.summary}")
            }
        }.trim()
    }
}
