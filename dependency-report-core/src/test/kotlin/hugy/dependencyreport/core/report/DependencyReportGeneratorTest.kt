package hugy.dependencyreport.core.report

import hugy.dependencyreport.core.config.ChangelogUrlMapping
import hugy.dependencyreport.core.config.DependencyReportConfig
import hugy.dependencyreport.core.config.GitHubRepositoryMapping
import hugy.dependencyreport.core.config.KnownSourceDefinition
import hugy.dependencyreport.core.config.KnownSourceType
import hugy.dependencyreport.core.fetch.FetchResult
import hugy.dependencyreport.core.fetch.ReleaseFetchRequest
import hugy.dependencyreport.core.fetch.ReleaseDocumentFetcher
import hugy.dependencyreport.core.llm.DisabledLlmReportGenerator
import hugy.dependencyreport.core.llm.StaticLlmReportGenerator
import hugy.dependencyreport.core.model.FetchedDocument
import hugy.dependencyreport.testkit.FixtureLoader
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyReportGeneratorTest {
    private val fixturesRoot = Path.of("src/test/resources/fixtures/basic")

    @Test
    fun `generates enriched report from fixtures`() {
        val report = DependencyReportGenerator(
            documentFetcher = FakeFetcher(
                mapOf(
                    "https://github.com/JetBrains/kotlin/releases" to listOf(
                        FetchedDocument(
                            title = "Kotlin 2.0.0",
                            sourceUrl = "https://github.com/JetBrains/kotlin/releases/tag/v2.0.0",
                            content = "Major compiler and stdlib improvements.",
                        ),
                    ),
                    "https://github.com/ben-manes/gradle-versions-plugin/releases" to listOf(
                        FetchedDocument(
                            title = "Gradle Versions 0.51.0",
                            sourceUrl = "https://github.com/ben-manes/gradle-versions-plugin/releases/tag/v0.51.0",
                            content = "Adds compatibility fixes and dependency reporting improvements.",
                        ),
                    ),
                ),
            ),
            llmReportGenerator = StaticLlmReportGenerator(),
        ).generate(
            previousCatalog = fixturesRoot.resolve("libs.before.toml"),
            currentCatalog = fixturesRoot.resolve("libs.after.toml"),
            config = DependencyReportConfig(
                githubRepositories = listOf(
                    GitHubRepositoryMapping(alias = "kotlin", repository = "JetBrains/kotlin"),
                    GitHubRepositoryMapping(pluginId = "com.github.ben-manes.versions", repository = "ben-manes/gradle-versions-plugin"),
                ),
                changelogUrls = listOf(
                    ChangelogUrlMapping(module = "org.jetbrains.kotlin:kotlin-bom", url = "https://kotlinlang.org/docs/releases.html"),
                ),
                llm = hugy.dependencyreport.core.config.LlmConfig(mode = "static"),
            ),
        )

        assertEquals(FixtureLoader.text(fixturesRoot.resolve("expected/summary.txt")), report.outputs.summaryText)
        assertEquals(FixtureLoader.text(fixturesRoot.resolve("expected/commit-body.txt")), report.outputs.commitBody)
        assertEquals(FixtureLoader.text(fixturesRoot.resolve("expected/mr-description.txt")), report.outputs.mergeRequestDescription)
        assertEquals(FixtureLoader.text(fixturesRoot.resolve("expected/jira-description.txt")), report.outputs.jiraDescription)
        assertEquals(FixtureLoader.text(fixturesRoot.resolve("expected/reviewer-checklist.md")), report.outputs.reviewerChecklist)
        assertEquals(FixtureLoader.text(fixturesRoot.resolve("expected/risk-summary.md")), report.outputs.riskSummary)
        assertFalse(report.entries.all { it.fallbackUsed })
        assertTrue(report.manifest.llmAttempted)
    }

    @Test
    fun `falls back when fetching or llm enrichment is unavailable`() {
        val report = DependencyReportGenerator(
            documentFetcher = object : ReleaseDocumentFetcher {
                override fun fetch(request: ReleaseFetchRequest): FetchResult = FetchResult.Failure("network unavailable")
            },
            llmReportGenerator = DisabledLlmReportGenerator(),
        ).generate(
            previousCatalog = fixturesRoot.resolve("libs.before.toml"),
            currentCatalog = fixturesRoot.resolve("libs.after.toml"),
            config = DependencyReportConfig(
                githubRepositories = listOf(
                    GitHubRepositoryMapping(alias = "kotlin", repository = "JetBrains/kotlin"),
                ),
            ),
        )

        assertTrue(report.entries.all { it.fallbackUsed })
        assertTrue(report.outputs.summaryText.contains("REGISTRY") || report.outputs.summaryText.contains("GITHUB_RELEASES"))
        assertTrue(report.entries.first().narrative.summary.contains("Fallback summary"))
    }

    @Test
    fun `prefers source registry before github mappings`() {
        val report = DependencyReportGenerator(
            documentFetcher = FakeFetcher(
                mapOf(
                    "https://github.com/JetBrains/kotlin/releases" to listOf(
                        FetchedDocument(
                            title = "Kotlin 2.0.0",
                            sourceUrl = "https://github.com/JetBrains/kotlin/releases/tag/v2.0.0",
                            content = "Registry-backed release notes.",
                        ),
                    ),
                ),
            ),
            llmReportGenerator = StaticLlmReportGenerator(),
        ).generate(
            previousCatalog = fixturesRoot.resolve("libs.before.toml"),
            currentCatalog = fixturesRoot.resolve("libs.after.toml"),
            config = DependencyReportConfig(
                sourceRegistry = listOf(
                    KnownSourceDefinition(
                        alias = "kotlin",
                        type = KnownSourceType.GITHUB_RELEASES,
                        repository = "JetBrains/kotlin",
                    ),
                ),
                githubRepositories = listOf(
                    GitHubRepositoryMapping(alias = "kotlin", repository = "someone-else/kotlin"),
                ),
                llm = hugy.dependencyreport.core.config.LlmConfig(mode = "static"),
            ),
        )

        assertEquals("sourceRegistry", report.entries.first { it.target.change.alias == "kotlin" }.sourceResolution.matchedBy)
    }
}

private class FakeFetcher(
    private val documentsByUrl: Map<String, List<FetchedDocument>>,
) : ReleaseDocumentFetcher {
    override fun fetch(request: ReleaseFetchRequest): FetchResult {
        val documents = documentsByUrl[request.source.sourceUrl]
        return if (documents != null) {
            FetchResult.Success(documents)
        } else {
            FetchResult.Failure("No fixture document configured for ${request.source.sourceUrl}")
        }
    }
}
