package hugy.dependencyreport.core.llm

import hugy.dependencyreport.core.model.FetchedDocument
import hugy.dependencyreport.core.model.GeneratedNarrative
import hugy.dependencyreport.core.model.RiskAssessment
import hugy.dependencyreport.core.model.RiskLevel
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

class DisabledLlmReportGenerator : LlmReportGenerator {
    override fun generate(request: LlmReportRequest): LlmGenerationResult {
        return LlmGenerationResult.Failure("LLM integration is disabled")
    }
}

class StaticLlmReportGenerator : LlmReportGenerator {
    override fun generate(request: LlmReportRequest): LlmGenerationResult {
        val target = request.target
        val titles = request.documents.joinToString { it.title }
        val baseSummary = "Summarized from ${request.documents.size} fetched document(s): $titles."
        return LlmGenerationResult.Success(
            GeneratedNarrative(
                headline = "${target.change.alias}: ${target.change.previousVersion} -> ${target.change.currentVersion}",
                summary = baseSummary,
                description = baseSummary,
                riskAssessment = RiskAssessment(
                    level = RiskLevel.MEDIUM,
                    summary = "Static adapter marked this upgrade as medium risk pending human review.",
                    signals = listOf("llm-static-adapter"),
                ),
            ),
        )
    }
}
