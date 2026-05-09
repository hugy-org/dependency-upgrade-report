package hugy.dependencyreport.core.catalog

import hugy.dependencyreport.core.model.AliasUsage
import hugy.dependencyreport.core.model.CatalogSnapshot
import hugy.dependencyreport.core.model.UpgradeKind
import hugy.dependencyreport.core.model.UpgradeTarget
import hugy.dependencyreport.core.model.UsageType
import hugy.dependencyreport.core.model.VersionAliasChange
import hugy.dependencyreport.core.model.VersionChangeClassification

data class ChangeDetectionResult(
    val changes: List<VersionAliasChange>,
    val warnings: List<String>,
)

class CatalogDiffService(
    private val versionComparator: VersionComparator = VersionComparator(),
) {
    fun detectVersionAliasChanges(previous: CatalogSnapshot, current: CatalogSnapshot): ChangeDetectionResult {
        val aliases = (previous.versions.keys + current.versions.keys).sorted()
        val changes = mutableListOf<VersionAliasChange>()
        val warnings = mutableListOf<String>()

        aliases.forEach { alias ->
            val oldVersion = previous.versions[alias]
            val newVersion = current.versions[alias]
            if (oldVersion != null && newVersion != null && oldVersion != newVersion) {
                val classification = versionComparator.classify(oldVersion, newVersion)
                when (classification) {
                    VersionChangeClassification.UPGRADE -> changes += VersionAliasChange(alias, oldVersion, newVersion, classification)
                    VersionChangeClassification.DOWNGRADE -> warnings +=
                        "Ignored $alias because the version change was classified as DOWNGRADE: $oldVersion -> $newVersion."
                    VersionChangeClassification.SAME -> warnings +=
                        "Ignored $alias because the version change was classified as SAME: $oldVersion -> $newVersion."
                    VersionChangeClassification.UNKNOWN -> {
                        changes += VersionAliasChange(alias, oldVersion, newVersion, classification)
                        warnings += "Included $alias with UNKNOWN version classification: $oldVersion -> $newVersion."
                    }
                }
            }
        }

        return ChangeDetectionResult(changes = changes, warnings = warnings)
    }

    fun resolveUpgradeTargets(
        changes: List<VersionAliasChange>,
        current: CatalogSnapshot,
    ): List<UpgradeTarget> {
        return changes.map { change ->
            val usages = buildList {
                current.libraries.values
                    .filter { it.versionRef == change.alias }
                    .forEach { library ->
                        add(
                            AliasUsage(
                                alias = library.alias,
                                usageType = if (isBom(library.module, library.alias)) UsageType.BOM else UsageType.LIBRARY,
                                identifier = library.module,
                                versionRef = change.alias,
                            ),
                        )
                    }

                current.plugins.values
                    .filter { it.versionRef == change.alias }
                    .forEach { plugin ->
                        add(
                            AliasUsage(
                                alias = plugin.alias,
                                usageType = UsageType.GRADLE_PLUGIN,
                                identifier = plugin.id,
                                versionRef = change.alias,
                            ),
                        )
                    }
            }
            UpgradeTarget(change, usages, classify(usages))
        }
    }

    private fun classify(usages: List<AliasUsage>): UpgradeKind {
        val types = usages.map { it.usageType }.toSet()
        return when {
            types.isEmpty() -> UpgradeKind.MIXED
            types == setOf(UsageType.GRADLE_PLUGIN) -> UpgradeKind.GRADLE_PLUGIN
            types == setOf(UsageType.BOM) -> UpgradeKind.BOM
            types == setOf(UsageType.LIBRARY) -> UpgradeKind.LIBRARY
            else -> UpgradeKind.MIXED
        }
    }

    private fun isBom(module: String, alias: String): Boolean {
        return module.substringAfter(':').contains("bom", ignoreCase = true) ||
            alias.contains("bom", ignoreCase = true)
    }
}
