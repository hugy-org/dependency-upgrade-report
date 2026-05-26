package hugy.dependencyreport.core.report

import hugy.dependencyreport.core.fetch.VersionSelection
import hugy.dependencyreport.core.model.GeneratedNarrative
import hugy.dependencyreport.core.model.RiskAssessment
import hugy.dependencyreport.core.model.RiskLevel
import hugy.dependencyreport.core.model.UpgradeTarget

class NarrativeRiskPostProcessor {
    fun apply(
        target: UpgradeTarget,
        narrative: GeneratedNarrative,
        hasPartiallyOmittedDocuments: Boolean = false,
    ): GeneratedNarrative {
        var processedNarrative = narrative
        if (hasPartiallyOmittedDocuments && processedNarrative.riskAssessment.level == RiskLevel.LOW) {
            processedNarrative = processedNarrative.copy(
                riskAssessment = RiskAssessment(
                    level = RiskLevel.MEDIUM,
                    summary = "Some selected release-note documents were omitted due to size limits, so risk was raised to MEDIUM for partial context.",
                    signals = processedNarrative.riskAssessment.signals + "partial-release-notes-context",
                ),
            )
        }

        val previous = VersionSelection.parse(target.change.previousVersion)
        val current = VersionSelection.parse(target.change.currentVersion)
        val majorChanged = previous != null && current != null &&
            previous.coreParts.getOrElse(0) { 0 } != current.coreParts.getOrElse(0) { 0 }

        if (!majorChanged) {
            return processedNarrative
        }

        val upgradedRisk = if (processedNarrative.riskAssessment.level == RiskLevel.HIGH) {
            processedNarrative.riskAssessment
        } else {
            RiskAssessment(
                level = RiskLevel.HIGH,
                summary = "Major version upgrade detected; treat as high risk even if the source summary appears otherwise moderate.",
                signals = processedNarrative.riskAssessment.signals + "major-version-upgrade",
            )
        }

        return processedNarrative.copy(riskAssessment = upgradedRisk)
    }
}
