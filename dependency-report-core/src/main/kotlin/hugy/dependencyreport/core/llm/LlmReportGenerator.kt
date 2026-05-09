package hugy.dependencyreport.core.llm

import hugy.dependencyreport.core.model.FetchedDocument
import hugy.dependencyreport.core.model.GeneratedNarrative
import hugy.dependencyreport.core.model.RiskAssessment
import hugy.dependencyreport.core.model.RiskLevel
import hugy.dependencyreport.core.model.UpgradeKind
import hugy.dependencyreport.core.model.UpgradeTarget

data class LlmReportRequest(
    val target: UpgradeTarget,
    val documents: List<FetchedDocument>,
)

sealed interface LlmGenerationResult {
    data class Success(val narrative: GeneratedNarrative) : LlmGenerationResult
    data class Failure(val reason: String) : LlmGenerationResult
}

interface LlmReportGenerator {
    fun generate(request: LlmReportRequest): LlmGenerationResult
}

class StaticLlmReportGenerator : LlmReportGenerator {
    override fun generate(request: LlmReportRequest): LlmGenerationResult {
        val target = request.target
        val usageSummary = if (target.usages.isEmpty()) {
            "No library or plugin usages were resolved for this alias."
        } else {
            "Mapped usages: ${target.usages.joinToString { it.identifier }}."
        }
        val sourceSummary = if (request.documents.isEmpty()) {
            "No fetched release-note documents were available."
        } else {
            "Fetched ${request.documents.size} release-note document(s)."
        }
        return LlmGenerationResult.Success(
            GeneratedNarrative(
                headline = "${target.change.alias}: ${target.change.previousVersion} -> ${target.change.currentVersion}",
                summary = "Deterministic summary generated without LLM. $usageSummary $sourceSummary",
                description = "Deterministic summary generated without LLM. $usageSummary $sourceSummary",
                riskAssessment = RiskAssessment(
                    level = when (target.kind) {
                        UpgradeKind.BOM,
                        UpgradeKind.MIXED,
                            -> RiskLevel.HIGH

                        UpgradeKind.GRADLE_PLUGIN,
                        UpgradeKind.LIBRARY,
                            -> RiskLevel.MEDIUM
                    },
                    summary = "Deterministic risk rating generated without LLM.",
                    signals = listOf("llm-static-mode", "kind:${target.kind.name.lowercase()}"),
                ),
            ),
        )
    }
}
