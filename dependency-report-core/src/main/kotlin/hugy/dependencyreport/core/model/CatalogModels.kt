package hugy.dependencyreport.core.model

data class CatalogSnapshot(
    val versions: Map<String, String>,
    val libraries: Map<String, LibraryAlias>,
    val plugins: Map<String, PluginAlias>,
)

data class LibraryAlias(
    val alias: String,
    val group: String,
    val name: String,
    val module: String,
    val versionRef: String?,
    val versionLiteral: String?,
)

data class PluginAlias(
    val alias: String,
    val id: String,
    val versionRef: String?,
    val versionLiteral: String?,
)

data class VersionAliasChange(
    val alias: String,
    val previousVersion: String,
    val currentVersion: String,
    val classification: VersionChangeClassification = VersionChangeClassification.UNKNOWN,
)

enum class VersionChangeClassification {
    UPGRADE,
    DOWNGRADE,
    SAME,
    UNKNOWN,
}

enum class UsageType {
    LIBRARY,
    BOM,
    GRADLE_PLUGIN,
}

data class AliasUsage(
    val alias: String,
    val usageType: UsageType,
    val identifier: String,
    val versionRef: String,
)

enum class UpgradeKind {
    LIBRARY,
    BOM,
    GRADLE_PLUGIN,
    MIXED,
}

data class UpgradeTarget(
    val change: VersionAliasChange,
    val usages: List<AliasUsage>,
    val kind: UpgradeKind,
)

enum class ReleaseSourceType {
    REGISTRY,
    GITHUB_RELEASES,
    EXPLICIT_CHANGELOG_URL,
    UNRESOLVED,
}

data class ReleaseSource(
    val type: ReleaseSourceType,
    val displayName: String,
    val sourceUrl: String? = null,
    val apiUrl: String? = null,
    val repository: String? = null,
)

data class ReleaseSourceResolution(
    val targetAlias: String,
    val source: ReleaseSource,
    val matchedBy: String,
    val warnings: List<String> = emptyList(),
    val provenance: List<String> = emptyList(),
    val linkOnly: Boolean = false,
)

data class FetchedDocument(
    val title: String,
    val sourceUrl: String,
    val content: String,
    val version: String? = null,
    val contentTruncated: Boolean = false,
    val originalContentLength: Int = content.length,
)

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN,
}

data class RiskAssessment(
    val level: RiskLevel,
    val summary: String,
    val signals: List<String>,
)

data class GeneratedNarrative(
    val headline: String,
    val summary: String,
    val description: String = summary,
    val riskAssessment: RiskAssessment,
)

data class UpgradeReportEntry(
    val target: UpgradeTarget,
    val sourceResolution: ReleaseSourceResolution,
    val documents: List<FetchedDocument>,
    val narrative: GeneratedNarrative,
    val fallbackUsed: Boolean,
    val errors: List<String>,
)

data class ExecutionManifest(
    val sourceResolverOrder: List<String>,
    val llmAttempted: Boolean,
    val llmSucceeded: Boolean,
    val fallbackCount: Int,
    val unresolvedCount: Int,
)

data class RenderedOutputs(
    val commitBody: String,
    val unifiedDescription: String,
)

data class GeneratedReport(
    val entries: List<UpgradeReportEntry>,
    val outputs: RenderedOutputs,
    val manifest: ExecutionManifest,
    val warnings: List<String> = emptyList(),
)
