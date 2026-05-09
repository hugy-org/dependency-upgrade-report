package hugy.dependencyreport.core.report

import hugy.dependencyreport.core.model.GeneratedNarrative
import hugy.dependencyreport.core.model.RiskAssessment
import hugy.dependencyreport.core.model.RiskLevel
import hugy.dependencyreport.core.model.UpgradeKind
import hugy.dependencyreport.core.model.UpgradeTarget

class FallbackNarrativeFactory {
    fun create(target: UpgradeTarget, errors: List<String>): GeneratedNarrative {
        val usageSummary = if (target.usages.isEmpty()) {
            "No library or plugin usages were resolved for this alias."
        } else {
            "Mapped usages: ${target.usages.joinToString { it.identifier }}."
        }
        val riskLevel = when (target.kind) {
            UpgradeKind.BOM,
            UpgradeKind.MIXED,
                -> RiskLevel.HIGH

            UpgradeKind.GRADLE_PLUGIN -> RiskLevel.MEDIUM
            UpgradeKind.LIBRARY -> RiskLevel.MEDIUM
        }
        return GeneratedNarrative(
            headline = "${target.change.alias}: ${target.change.previousVersion} -> ${target.change.currentVersion}",
            summary = buildString {
                append("Fallback summary generated from catalog diff. ")
                append(usageSummary)
                if (errors.isNotEmpty()) {
                    append(" Errors: ${errors.joinToString("; ")}.")
                }
            },
            reviewerChecklist = listOf(
                "Inspect release notes manually if available.",
                "Run focused regression checks for impacted modules.",
            ),
            riskAssessment = RiskAssessment(
                level = riskLevel,
                summary = "Fallback risk rating based on upgrade kind and missing automated enrichment.",
                signals = listOf("fallback", "kind:${target.kind.name.lowercase()}"),
            ),
        )
    }
}
