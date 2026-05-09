package hugy.dependencyreport.core.config

data class DependencyReportConfig(
    val sourceRegistry: List<KnownSourceDefinition> = emptyList(),
    val githubRepositories: List<GitHubRepositoryMapping> = emptyList(),
    val changelogUrls: List<ChangelogUrlMapping> = emptyList(),
    val github: GitHubConfig = GitHubConfig(),
    val llm: LlmConfig = LlmConfig(),
)

data class KnownSourceDefinition(
    val alias: String? = null,
    val module: String? = null,
    val pluginId: String? = null,
    val type: KnownSourceType,
    val url: String? = null,
    val repository: String? = null,
    val displayName: String? = null,
)

enum class KnownSourceType {
    GITHUB_RELEASES,
    CHANGELOG_URL,
}

data class GitHubRepositoryMapping(
    val alias: String? = null,
    val module: String? = null,
    val pluginId: String? = null,
    val repository: String,
)

data class ChangelogUrlMapping(
    val alias: String? = null,
    val module: String? = null,
    val pluginId: String? = null,
    val url: String,
)

data class GitHubConfig(
    val token: String? = null,
    val apiBaseUrl: String = "https://api.github.com",
    val maxReleases: Int = 5,
    val maxScanReleases: Int = 100,
)

data class LlmConfig(
    val mode: String = "disabled",
)
