package hugy.dependencyreport.core.source

import hugy.dependencyreport.core.config.ChangelogUrlMapping
import hugy.dependencyreport.core.config.DependencyReportConfig
import hugy.dependencyreport.core.config.GitHubRepositoryMapping
import hugy.dependencyreport.core.config.KnownSourceDefinition
import hugy.dependencyreport.core.config.KnownSourceType
import hugy.dependencyreport.core.model.ReleaseSource
import hugy.dependencyreport.core.model.ReleaseSourceResolution
import hugy.dependencyreport.core.model.ReleaseSourceType
import hugy.dependencyreport.core.model.UpgradeTarget

class ReleaseSourceResolver(
    private val config: DependencyReportConfig,
) {
    fun resolve(target: UpgradeTarget): ReleaseSourceResolution {
        matchKnownSource(target)?.let { return it }
        matchGitHubMapping(target)?.let { return it }
        matchChangelogUrl(target)?.let { return it }
        return ReleaseSourceResolution(
            targetAlias = target.change.alias,
            source = ReleaseSource(
                type = ReleaseSourceType.UNRESOLVED,
                displayName = "Unresolved",
            ),
            matchedBy = "unresolved",
            warnings = listOf("No deterministic source mapping was found."),
        )
    }

    private fun matchKnownSource(target: UpgradeTarget): ReleaseSourceResolution? {
        val definition = config.sourceRegistry.firstOrNull { matches(target, it.alias, it.module, it.pluginId) } ?: return null
        val source = when (definition.type) {
            KnownSourceType.GITHUB_RELEASES -> {
                val repo = requireNotNull(definition.repository) {
                    "Known source entry for ${target.change.alias} must declare repository"
                }
                ReleaseSource(
                    type = ReleaseSourceType.GITHUB_RELEASES,
                    displayName = definition.displayName ?: repo,
                    sourceUrl = "https://github.com/$repo/releases",
                    apiUrl = "${config.github.apiBaseUrl}/repos/$repo/releases?per_page=${config.github.maxReleases}",
                    repository = repo,
                )
            }

            KnownSourceType.CHANGELOG_URL -> ReleaseSource(
                type = ReleaseSourceType.REGISTRY,
                displayName = definition.displayName ?: (definition.url ?: "Registry changelog"),
                sourceUrl = definition.url,
            )
        }
        return ReleaseSourceResolution(target.change.alias, source, "sourceRegistry")
    }

    private fun matchGitHubMapping(target: UpgradeTarget): ReleaseSourceResolution? {
        val mapping = config.githubRepositories.firstOrNull {
            matches(target, it.alias, it.module, it.pluginId)
        } ?: return null
        return ReleaseSourceResolution(
            targetAlias = target.change.alias,
            source = ReleaseSource(
                type = ReleaseSourceType.GITHUB_RELEASES,
                displayName = mapping.repository,
                sourceUrl = "https://github.com/${mapping.repository}/releases",
                apiUrl = "${config.github.apiBaseUrl}/repos/${mapping.repository}/releases?per_page=${config.github.maxReleases}",
                repository = mapping.repository,
            ),
            matchedBy = "githubRepositories",
        )
    }

    private fun matchChangelogUrl(target: UpgradeTarget): ReleaseSourceResolution? {
        val mapping = config.changelogUrls.firstOrNull {
            matches(target, it.alias, it.module, it.pluginId)
        } ?: return null
        return ReleaseSourceResolution(
            targetAlias = target.change.alias,
            source = ReleaseSource(
                type = ReleaseSourceType.EXPLICIT_CHANGELOG_URL,
                displayName = mapping.url,
                sourceUrl = mapping.url,
            ),
            matchedBy = "changelogUrls",
        )
    }

    private fun matches(target: UpgradeTarget, alias: String?, module: String?, pluginId: String?): Boolean {
        if (alias != null && alias == target.change.alias) {
            return true
        }
        val identifiers = target.usages.map { it.identifier }.toSet()
        return (module != null && module in identifiers) || (pluginId != null && pluginId in identifiers)
    }
}
