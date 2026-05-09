package hugy.dependencyreport.core.config

data class DependencyReportConfig(
    val sources: List<SourceDefinition> = emptyList(),
    val github: GitHubConfig = GitHubConfig(),
    val fetch: FetchConfig = FetchConfig(),
    val inference: InferenceConfig = InferenceConfig(),
    val llm: LlmConfig = LlmConfig(),
)

data class SourceDefinition(
    val match: SourceMatch,
    val githubRepo: String? = null,
    val changelogUrl: String? = null,
    val displayName: String? = null,
    val linkOnly: Boolean = false,
)

data class SourceMatch(
    val alias: String? = null,
    val module: String? = null,
    val pluginId: String? = null,
)

data class GitHubConfig(
    val token: String? = null,
    val apiBaseUrl: String = "https://api.github.com",
    val maxReleases: Int = 5,
    val pageSize: Int = 20,
    val maxScanReleases: Int = 100,
    val includePrereleases: Boolean = false,
)

data class FetchConfig(
    val maxDocumentContentChars: Int = 12_000,
)

data class InferenceConfig(
    val enabled: Boolean = true,
    val mavenRepositoryBaseUrl: String = "https://repo1.maven.org/maven2",
)

data class LlmConfig(
    val mode: String = "disabled",
    val model: String? = null,
    val apiKey: String? = null,
    val baseUrl: String = "https://openrouter.ai/api/v1/chat/completions",
    val retryCount: Int = 3,
    val retryDelayMs: Long = 750,
    val requestTimeoutMs: Long = 45_000,
)
