package hugy.dependencyreport.core.report

import hugy.dependencyreport.core.fetch.VersionSelection
import hugy.dependencyreport.core.model.FetchedDocument
import hugy.dependencyreport.core.model.GeneratedNarrative
import hugy.dependencyreport.core.model.RiskAssessment
import hugy.dependencyreport.core.model.RiskLevel
import hugy.dependencyreport.core.model.UpgradeKind
import hugy.dependencyreport.core.model.UpgradeTarget
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class FallbackNarrativeFactory {
    fun createLinkOnly(target: UpgradeTarget, releaseNotesUrl: String?): GeneratedNarrative {
        val usageSummary = if (target.usages.isEmpty()) {
            "No library or plugin usages were resolved for this alias."
        } else {
            "Mapped usages: ${target.usages.joinToString { it.identifier }}."
        }
        return GeneratedNarrative(
            headline = "${target.change.alias}: ${target.change.previousVersion} -> ${target.change.currentVersion}",
            summary = buildString {
                append("Configured as link-only source. ")
                append("Automatic enrichment was skipped. ")
                append(usageSummary)
                append(" The release notes URL is included for manual review")
                if (!releaseNotesUrl.isNullOrBlank()) {
                    append(" because the configured source is not intended for automatic enrichment")
                }
                append(".")
            },
            description = buildString {
                append("This dependency uses a link-only release notes source, so automatic enrichment was skipped. ")
                append(usageSummary)
                if (!releaseNotesUrl.isNullOrBlank()) {
                    append(" Review the linked release notes manually.")
                }
            },
            riskAssessment = RiskAssessment(
                level = when (target.kind) {
                    UpgradeKind.BOM,
                    UpgradeKind.MIXED,
                        -> RiskLevel.HIGH

                    UpgradeKind.GRADLE_PLUGIN,
                    UpgradeKind.LIBRARY,
                        -> RiskLevel.MEDIUM
                },
                summary = "Manual review is required because this source is configured as link-only and was not automatically summarized.",
                signals = listOf("link-only", "kind:${target.kind.name.lowercase()}"),
            ),
        )
    }

    fun create(target: UpgradeTarget, documents: List<FetchedDocument>, errors: List<String>): GeneratedNarrative {
        logger.warn {
            "Building fallback narrative for alias=${target.change.alias} (documents=${documents.size}, errors=${errors.size})"
        }
        if (documents.isNotEmpty()) {
            return createFromDocuments(target, documents, errors)
        }
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
            description = buildString {
                append("Fallback summary generated from catalog diff. ")
                append(usageSummary)
                if (errors.isNotEmpty()) {
                    append(" Errors: ${errors.joinToString("; ")}.")
                }
            },
            riskAssessment = RiskAssessment(
                level = riskLevel,
                summary = "Fallback risk rating based on upgrade kind and missing automated enrichment.",
                signals = listOf("fallback", "kind:${target.kind.name.lowercase()}"),
            ),
        )
    }

    private fun createFromDocuments(
        target: UpgradeTarget,
        documents: List<FetchedDocument>,
        errors: List<String>,
    ): GeneratedNarrative {
        val primary = documents.firstOrNull { document ->
            VersionSelection.parse(document.version ?: "")?.normalized ==
                VersionSelection.parse(target.change.currentVersion)?.normalized
        } ?: documents.last()
        val cleanContent = primary.content
            .substringBefore("See release notes:")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { "Release notes were fetched but the content was too limited for richer summarization." }
        val concise = cleanContent.removeSuffix(".")
        val usageSummary = if (target.usages.isEmpty()) {
            target.kind.name.lowercase()
        } else {
            target.usages.joinToString(", ") { it.identifier }
        }
        return GeneratedNarrative(
            headline = "${target.change.alias}: ${target.change.previousVersion} -> ${target.change.currentVersion}",
            summary = buildString {
                append("${target.change.alias} ${target.change.previousVersion} -> ${target.change.currentVersion}. ")
                append(concise)
                append(".")
            },
            description = buildString {
                append("Upgrade $usageSummary to ${target.change.currentVersion}. ")
                append(concise)
                append(".")
            },
            riskAssessment = RiskAssessment(
                level = when (target.kind) {
                    UpgradeKind.BOM,
                    UpgradeKind.MIXED,
                        -> RiskLevel.HIGH

                    UpgradeKind.GRADLE_PLUGIN,
                    UpgradeKind.LIBRARY,
                        -> RiskLevel.MEDIUM
                },
                summary = buildString {
                    append("Fallback risk rating based on fetched release notes and upgrade kind.")
                    if (errors.isNotEmpty()) {
                        append(" LLM enrichment failed, so this summary was generated deterministically from the fetched notes.")
                    }
                },
                signals = listOf("fallback", "fetched-documents", "kind:${target.kind.name.lowercase()}"),
            ),
        )
    }
}
