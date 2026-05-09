package hugy.dependencyreport.core.source

import hugy.dependencyreport.core.config.DependencyReportConfig
import hugy.dependencyreport.core.config.SourceDefinition
import hugy.dependencyreport.core.model.ReleaseSource
import hugy.dependencyreport.core.model.ReleaseSourceResolution
import hugy.dependencyreport.core.model.ReleaseSourceType
import hugy.dependencyreport.core.model.UpgradeTarget
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class ReleaseSourceResolver(
    private val config: DependencyReportConfig,
    private val metadataLookup: SourceMetadataLookup = HttpSourceMetadataLookup(config.inference),
) {
    fun resolve(target: UpgradeTarget): ReleaseSourceResolution {
        matchExplicitSource(target)?.let {
            if (it.warnings.isNotEmpty()) {
                logger.warn { "Ambiguous explicit source match for alias=${target.change.alias}: ${it.warnings.joinToString(" | ")}" }
            }
            return it
        }

        if (config.inference.enabled) {
            SourceInference.inferFromMavenPom(
                target = target,
                lookup = metadataLookup,
                githubApiBaseUrl = config.github.apiBaseUrl,
                githubMaxReleases = config.github.maxReleases,
            )?.let {
                logger.info { "Resolved alias=${target.change.alias} via inferredMavenPom" }
                return it
            }
        }

        logger.warn { "No deterministic source mapping found for alias=${target.change.alias}; marking as unresolved" }
        return ReleaseSourceResolution(
            targetAlias = target.change.alias,
            source = ReleaseSource(
                type = ReleaseSourceType.UNRESOLVED,
                displayName = "Unresolved",
            ),
            matchedBy = "unresolved",
            warnings = listOf("No deterministic source mapping was found."),
            provenance = unresolvedProvenance(target),
        )
    }

    private fun matchExplicitSource(target: UpgradeTarget): ReleaseSourceResolution? {
        val matches = config.sources.filter { matches(target, it) }
        val selected = matches.firstOrNull() ?: return null
        val source = when {
            selected.githubRepo != null -> ReleaseSource(
                type = ReleaseSourceType.GITHUB_RELEASES,
                displayName = selected.displayName ?: selected.githubRepo,
                sourceUrl = "https://github.com/${selected.githubRepo}/releases",
                apiUrl = "${config.github.apiBaseUrl}/repos/${selected.githubRepo}/releases?per_page=${config.github.maxReleases}",
                repository = selected.githubRepo,
            )
            selected.changelogUrl != null -> ReleaseSource(
                type = ReleaseSourceType.EXPLICIT_CHANGELOG_URL,
                displayName = selected.displayName ?: selected.changelogUrl,
                sourceUrl = selected.changelogUrl,
            )
            else -> return null
        }

        val warnings = if (matches.size > 1) {
            listOf(
                buildString {
                    append("Multiple source rules matched alias '${target.change.alias}'; using the first match ")
                    append(describe(selected))
                    append(". Other matches: ")
                    append(matches.drop(1).joinToString { describe(it) })
                    append(".")
                },
            )
        } else {
            emptyList()
        }

        return ReleaseSourceResolution(
            targetAlias = target.change.alias,
            source = source,
            matchedBy = "sources",
            warnings = warnings,
            provenance = listOf("Resolved from explicit sources configuration."),
            linkOnly = selected.linkOnly,
        )
    }

    private fun matches(target: UpgradeTarget, definition: SourceDefinition): Boolean {
        val match = definition.match
        if (match.alias == target.change.alias) {
            return true
        }
        val identifiers = target.usages.map { it.identifier }.toSet()
        return (match.module != null && match.module in identifiers) ||
                (match.pluginId != null && match.pluginId in identifiers)
    }

    private fun describe(definition: SourceDefinition): String {
        val selector = when {
            definition.match.alias != null -> "alias=${definition.match.alias}"
            definition.match.module != null -> "module=${definition.match.module}"
            definition.match.pluginId != null -> "pluginId=${definition.match.pluginId}"
            else -> "match=<unknown>"
        }
        val destination = when {
            definition.githubRepo != null -> "githubRepo=${definition.githubRepo}"
            definition.changelogUrl != null -> "changelogUrl=${definition.changelogUrl}"
            else -> "source=<unconfigured>"
        }
        val suffix = if (definition.linkOnly) ", linkOnly=true" else ""
        return "[$selector -> $destination$suffix]"
    }

    private fun unresolvedProvenance(target: UpgradeTarget): List<String> {
        val usages = if (target.usages.isEmpty()) {
            "none"
        } else {
            target.usages.joinToString { "${it.usageType}:${it.identifier}" }
        }
        return buildList {
            add("No explicit sources entry matched alias '${target.change.alias}'.")
            add("Resolved usages for alias '${target.change.alias}': $usages.")
            if (config.inference.enabled) {
                add("Inferred resolvers attempted in order: inferredMavenPom.")
            } else {
                add("Inferred resolvers were disabled by configuration.")
            }
        }
    }
}
