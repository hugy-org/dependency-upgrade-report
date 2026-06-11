package hugy.dependencyreport.core.report

import hugy.dependencyreport.core.config.DependencyReportConfig
import hugy.dependencyreport.core.config.LLMMode
import hugy.dependencyreport.core.config.LlmConfig
import hugy.dependencyreport.core.config.SourceDefinition
import hugy.dependencyreport.core.config.SourceMatch
import hugy.dependencyreport.core.fetch.FetchResult
import hugy.dependencyreport.core.fetch.ReleaseDocumentFetcher
import hugy.dependencyreport.core.fetch.ReleaseFetchRequest
import hugy.dependencyreport.core.llm.LlmGenerationResult
import hugy.dependencyreport.core.llm.LlmReportGenerator
import hugy.dependencyreport.core.llm.LlmReportRequest
import hugy.dependencyreport.core.llm.StaticLlmReportGenerator
import hugy.dependencyreport.core.model.FetchedDocument
import hugy.dependencyreport.core.model.GeneratedNarrative
import hugy.dependencyreport.core.model.RiskAssessment
import hugy.dependencyreport.core.model.RiskLevel
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyReportGeneratorTest {
    private val fixturesRoot = Path.of("src/test/resources/fixtures/basic")

    @Test
    fun `generates simplified outputs`() {
        val report = DependencyReportGenerator(
            documentFetcher = FakeFetcher(
                mapOf(
                    "https://github.com/JetBrains/kotlin/releases" to listOf(
                        FetchedDocument(
                            title = "Kotlin 2.0.0",
                            sourceUrl = "https://github.com/JetBrains/kotlin/releases/tag/v2.0.0",
                            content = "Major compiler and stdlib improvements.",
                            version = "2.0.0",
                        ),
                    ),
                ),
            ),
            llmReportGenerator = StaticLlmReportGenerator(),
        ).generate(
            previousCatalog = fixturesRoot.resolve("libs.before.toml"),
            currentCatalog = fixturesRoot.resolve("libs.after.toml"),
            config = DependencyReportConfig(
                sources = listOf(SourceDefinition(match = SourceMatch(alias = "kotlin"), githubRepo = "JetBrains/kotlin")),
                llm = LlmConfig(mode = LLMMode.STATIC),
            ),
        )

        assertTrue(report.outputs.commitBody.contains("- kotlin 1.9.24 -> 2.0.0"))
        assertTrue(report.outputs.commitBody.contains("Release notes: https://github.com/JetBrains/kotlin/releases/tag/v2.0.0"))
        assertTrue(report.outputs.unifiedDescription.contains("Dependency update review"))
        assertTrue(report.outputs.unifiedDescription.contains("kotlin 1.9.24 -> 2.0.0"))
        assertTrue(report.outputs.unifiedDescription.contains("Summary: Deterministic summary generated without LLM."))
        assertTrue(report.outputs.unifiedDescription.contains("Release notes: https://github.com/JetBrains/kotlin/releases/tag/v2.0.0"))
        assertEquals(listOf("sources", "inferredMavenPom", "unresolved"), report.manifest.sourceResolverOrder)
        assertFalse(report.entries.all { it.fallbackUsed })
    }

    @Test
    fun `falls back when fetching and llm are unavailable`() {
        val report = DependencyReportGenerator(
            documentFetcher = object : ReleaseDocumentFetcher {
                override fun fetch(request: ReleaseFetchRequest): FetchResult = FetchResult.Failure("network unavailable")
            },
            llmReportGenerator = object : LlmReportGenerator {
                override fun generate(request: LlmReportRequest): LlmGenerationResult =
                    LlmGenerationResult.Failure("OpenRouter unavailable")
            },
        ).generate(
            previousCatalog = fixturesRoot.resolve("libs.before.toml"),
            currentCatalog = fixturesRoot.resolve("libs.after.toml"),
            config = DependencyReportConfig(
                sources = listOf(SourceDefinition(match = SourceMatch(alias = "kotlin"), githubRepo = "JetBrains/kotlin")),
            ),
        )

        assertTrue(report.entries.all { it.fallbackUsed })
        assertTrue(report.outputs.unifiedDescription.contains("Deterministic summary generated without LLM"))
    }

    @Test
    fun `uses fetched release notes for deterministic fallback description`() {
        val report = DependencyReportGenerator(
            documentFetcher = FakeFetcher(
                mapOf(
                    "https://github.com/ben-manes/gradle-versions-plugin/releases" to listOf(
                        FetchedDocument(
                            title = "v0.51.0",
                            sourceUrl = "https://github.com/ben-manes/gradle-versions-plugin/releases/tag/v0.51.0",
                            content = "Ensures Kotlin dependencies are properly handled when not forced.",
                            version = "0.51.0",
                        ),
                    ),
                ),
            ),
            llmReportGenerator = object : LlmReportGenerator {
                override fun generate(request: LlmReportRequest): LlmGenerationResult =
                    LlmGenerationResult.Failure("OpenRouter unavailable")
            },
        ).generate(
            previousCatalog = fixturesRoot.resolve("libs.before.toml"),
            currentCatalog = fixturesRoot.resolve("libs.after.toml"),
            config = DependencyReportConfig(
                sources = listOf(SourceDefinition(match = SourceMatch(pluginId = "com.github.ben-manes.versions"), githubRepo = "ben-manes/gradle-versions-plugin")),
            ),
        )

        val pluginEntry = report.entries.first { it.target.change.alias == "versionsPlugin" }
        assertTrue(pluginEntry.fallbackUsed)
        assertTrue(pluginEntry.narrative.description.contains("Deterministic summary generated without LLM"))
        assertTrue(pluginEntry.narrative.description.contains("Fetched 1 release-note document(s)"))
        assertFalse(pluginEntry.narrative.description.contains("Ensures Kotlin dependencies are properly handled"))
    }

    @Test
    fun `major version upgrades are elevated to high risk`() {
        val report = DependencyReportGenerator(
            documentFetcher = FakeFetcher(
                mapOf(
                    "https://github.com/JetBrains/kotlin/releases" to listOf(
                        FetchedDocument(
                            title = "Kotlin 2.0.0",
                            sourceUrl = "https://github.com/JetBrains/kotlin/releases/tag/v2.0.0",
                            content = "Major compiler and stdlib improvements.",
                            version = "2.0.0",
                        ),
                    ),
                ),
            ),
            llmReportGenerator = StaticLlmReportGenerator(),
        ).generate(
            previousCatalog = fixturesRoot.resolve("libs.before.toml"),
            currentCatalog = fixturesRoot.resolve("libs.after.toml"),
            config = DependencyReportConfig(
                sources = listOf(SourceDefinition(match = SourceMatch(alias = "kotlin"), githubRepo = "JetBrains/kotlin")),
                llm = LlmConfig(mode = LLMMode.STATIC),
            ),
        )

        val kotlinEntry = report.entries.first { it.target.change.alias == "kotlin" }
        assertEquals(hugy.dependencyreport.core.model.RiskLevel.HIGH, kotlinEntry.narrative.riskAssessment.level)
    }

    @Test
    fun `downgrade changes are excluded from output and recorded as warnings`() {
        val report = DependencyReportGenerator(
            documentFetcher = FakeFetcher(
                mapOf(
                    "https://github.com/example/kept/releases" to listOf(
                        FetchedDocument(
                            title = "kept 1.1.0",
                            sourceUrl = "https://github.com/example/kept/releases/tag/v1.1.0",
                            content = "Minor update.",
                            version = "1.1.0",
                        ),
                    ),
                ),
            ),
            llmReportGenerator = StaticLlmReportGenerator(),
        ).generate(
            previousCatalog = tempCatalog(
                """
                [versions]
                kept = "1.0.0"
                dropped = "2.2.21"

                [libraries]
                kept-lib = { module = "com.example:kept", version.ref = "kept" }
                dropped-lib = { module = "com.example:dropped", version.ref = "dropped" }
                """.trimIndent(),
            ),
            currentCatalog = tempCatalog(
                """
                [versions]
                kept = "1.1.0"
                dropped = "2.0.21"

                [libraries]
                kept-lib = { module = "com.example:kept", version.ref = "kept" }
                dropped-lib = { module = "com.example:dropped", version.ref = "dropped" }
                """.trimIndent(),
            ),
            config = DependencyReportConfig(
                sources = listOf(
                    SourceDefinition(match = SourceMatch(alias = "kept"), githubRepo = "example/kept"),
                    SourceDefinition(match = SourceMatch(alias = "dropped"), githubRepo = "example/dropped"),
                ),
                llm = LlmConfig(mode = LLMMode.STATIC),
            ),
        )

        assertEquals(listOf("kept"), report.entries.map { it.target.change.alias })
        assertTrue(report.outputs.commitBody.contains("- kept 1.0.0 -> 1.1.0"))
        assertFalse(report.outputs.commitBody.contains("dropped 2.2.21 -> 2.0.21"))
        assertTrue(report.warnings.any { it.contains("Ignored dropped because the version change was classified as DOWNGRADE") })
    }

    @Test
    fun `unknown changes are included and recorded as warnings`() {
        val report = DependencyReportGenerator(
            documentFetcher = object : ReleaseDocumentFetcher {
                override fun fetch(request: ReleaseFetchRequest): FetchResult = FetchResult.Failure("network unavailable")
            },
            llmReportGenerator = object : LlmReportGenerator {
                override fun generate(request: LlmReportRequest): LlmGenerationResult =
                    LlmGenerationResult.Failure("OpenRouter unavailable")
            },
        ).generate(
            previousCatalog = tempCatalog(
                """
                [versions]
                weird = "main"

                [libraries]
                weird-lib = { module = "com.example:weird", version.ref = "weird" }
                """.trimIndent(),
            ),
            currentCatalog = tempCatalog(
                """
                [versions]
                weird = "latest"

                [libraries]
                weird-lib = { module = "com.example:weird", version.ref = "weird" }
                """.trimIndent(),
            ),
            config = DependencyReportConfig(),
        )

        assertEquals(listOf("weird"), report.entries.map { it.target.change.alias })
        assertTrue(report.warnings.any { it.contains("Included weird with UNKNOWN version classification") })
    }

    @Test
    fun `unified description suppresses duplicated body and commit body stays headline only`() {
        val report = DependencyReportGenerator(
            documentFetcher = FakeFetcher(
                mapOf(
                    "https://github.com/example/jib/releases" to listOf(
                        FetchedDocument(
                            title = "jib 3.5.3",
                            sourceUrl = "https://github.com/example/jib/releases/tag/v3.5.3",
                            content = "Some release notes.",
                            version = "3.5.3",
                        ),
                    ),
                ),
            ),
            llmReportGenerator = object : LlmReportGenerator {
                override fun generate(request: LlmReportRequest): LlmGenerationResult {
                    val duplicated = "jib 3.5.0 -> 3.5.3"
                    return LlmGenerationResult.Success(
                        GeneratedNarrative(
                            headline = duplicated,
                            summary = duplicated,
                            description = duplicated,
                            riskAssessment = RiskAssessment(
                                level = RiskLevel.MEDIUM,
                                summary = "Moderate risk.",
                                signals = emptyList(),
                            ),
                        ),
                    )
                }
            },
        ).generate(
            previousCatalog = tempCatalog(
                """
                [versions]
                jib = "3.5.0"

                [plugins]
                jib = { id = "com.google.cloud.tools.jib", version.ref = "jib" }
                """.trimIndent(),
            ),
            currentCatalog = tempCatalog(
                """
                [versions]
                jib = "3.5.3"

                [plugins]
                jib = { id = "com.google.cloud.tools.jib", version.ref = "jib" }
                """.trimIndent(),
            ),
            config = DependencyReportConfig(
                sources = listOf(SourceDefinition(match = SourceMatch(alias = "jib"), githubRepo = "example/jib")),
            ),
        )

        assertTrue(report.outputs.unifiedDescription.contains("jib 3.5.0 -> 3.5.3"))
        assertFalse(report.outputs.unifiedDescription.contains("* jib 3.5.0 -> 3.5.3"))
        assertFalse(report.outputs.unifiedDescription.contains("Summary: jib 3.5.0 -> 3.5.3"))
        assertFalse(report.outputs.commitBody.contains("\n  jib 3.5.0 -> 3.5.3"))
        assertTrue(report.outputs.commitBody.contains("- jib 3.5.0 -> 3.5.3"))
        assertTrue(report.outputs.commitBody.contains("Release notes: https://github.com/example/jib/releases/tag/v3.5.3"))
    }

    @Test
    fun `link only sources skip fetch and llm while keeping release notes url`() {
        val report = DependencyReportGenerator(
            documentFetcher = object : ReleaseDocumentFetcher {
                override fun fetch(request: ReleaseFetchRequest): FetchResult {
                    error("fetch should not be called for linkOnly sources")
                }
            },
            llmReportGenerator = object : LlmReportGenerator {
                override fun generate(request: LlmReportRequest): LlmGenerationResult {
                    error("llm should not be called for linkOnly sources")
                }
            },
        ).generate(
            previousCatalog = tempCatalog(
                """
                [versions]
                google-api-services-gmail = "v1-rev20260413-2.0.0"

                [libraries]
                gmail = { module = "com.google.apis:google-api-services-gmail", version.ref = "google-api-services-gmail" }
                """.trimIndent(),
            ),
            currentCatalog = tempCatalog(
                """
                [versions]
                google-api-services-gmail = "v1-rev20260427-2.0.0"

                [libraries]
                gmail = { module = "com.google.apis:google-api-services-gmail", version.ref = "google-api-services-gmail" }
                """.trimIndent(),
            ),
            config = DependencyReportConfig(
                sources = listOf(
                    SourceDefinition(
                        match = SourceMatch(alias = "google-api-services-gmail"),
                        changelogUrl = "https://developers.google.com/workspace/gmail/release-notes",
                        linkOnly = true,
                    ),
                ),
            ),
        )

        val entry = report.entries.single()
        assertFalse(entry.fallbackUsed)
        assertTrue(entry.sourceResolution.linkOnly)
        assertTrue(entry.documents.isEmpty())
        assertTrue(entry.narrative.summary.contains("Configured as link-only source"))
        assertTrue(entry.narrative.riskAssessment.summary.contains("link-only"))
        assertTrue(report.outputs.commitBody.contains("Release notes: https://developers.google.com/workspace/gmail/release-notes"))
        assertTrue(report.outputs.unifiedDescription.contains("Summary: Configured as link-only source. Automatic enrichment was skipped."))
    }

    @Test
    fun `skips llm when all fetched documents were omitted due to size limits`() {
        var llmCalls = 0
        val report = DependencyReportGenerator(
            documentFetcher = FakeFetcher(
                mapOf(
                    "https://github.com/JetBrains/kotlin/releases" to listOf(
                        FetchedDocument(
                            title = "Kotlin 2.4.0",
                            sourceUrl = "https://github.com/JetBrains/kotlin/releases/tag/v2.4.0",
                            content = "Document content omitted because it exceeded 12000 characters. See release notes: https://github.com/JetBrains/kotlin/releases/tag/v2.4.0",
                            version = "2.4.0",
                            contentTruncated = true,
                            originalContentLength = 50_000,
                        ),
                    ),
                ),
            ),
            llmReportGenerator = object : LlmReportGenerator {
                override fun generate(request: LlmReportRequest): LlmGenerationResult {
                    llmCalls += 1
                    return LlmGenerationResult.Failure("should not be called")
                }
            },
        ).generate(
            previousCatalog = tempCatalog(
                """
                [versions]
                kotlin = "2.3.21"

                [plugins]
                kotlinJvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
                """.trimIndent(),
            ),
            currentCatalog = tempCatalog(
                """
                [versions]
                kotlin = "2.4.0"

                [plugins]
                kotlinJvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
                """.trimIndent(),
            ),
            config = DependencyReportConfig(
                sources = listOf(SourceDefinition(match = SourceMatch(alias = "kotlin"), githubRepo = "JetBrains/kotlin")),
            ),
        )

        assertEquals(0, llmCalls)
        val entry = report.entries.single()
        assertTrue(entry.fallbackUsed)
        assertTrue(entry.errors.any { it.contains("Target version release-note document was omitted due to size limits") })
    }

    @Test
    fun `uses meaningful documents plus omission note and raises low risk to medium for partial context`() {
        lateinit var llmRequest: LlmReportRequest
        val report = DependencyReportGenerator(
            documentFetcher = FakeFetcher(
                mapOf(
                    "https://github.com/JetBrains/kotlin/releases" to listOf(
                        FetchedDocument(
                            title = "Kotlin 2.4.0",
                            sourceUrl = "https://github.com/JetBrains/kotlin/releases/tag/v2.4.0",
                            content = "Useful release content.",
                            version = "2.4.0",
                        ),
                        FetchedDocument(
                            title = "Kotlin 2.3.30",
                            sourceUrl = "https://github.com/JetBrains/kotlin/releases/tag/v2.3.30",
                            content = "Document content omitted because it exceeded 12000 characters. See release notes: https://github.com/JetBrains/kotlin/releases/tag/v2.3.30",
                            version = "2.3.30",
                            contentTruncated = true,
                            originalContentLength = 40_000,
                        ),
                    ),
                ),
            ),
            llmReportGenerator = object : LlmReportGenerator {
                override fun generate(request: LlmReportRequest): LlmGenerationResult {
                    llmRequest = request
                    return LlmGenerationResult.Success(
                        GeneratedNarrative(
                            headline = "kotlin 2.3.21 -> 2.4.0",
                            summary = "Upgrade Kotlin Gradle plugins to 2.4.0.",
                            description = "Upgrade Kotlin Gradle plugins to 2.4.0.",
                            riskAssessment = RiskAssessment(
                                level = RiskLevel.LOW,
                                summary = "Low risk from the available notes.",
                                signals = listOf("llm-low"),
                            ),
                        ),
                    )
                }
            },
        ).generate(
            previousCatalog = tempCatalog(
                """
                [versions]
                kotlin = "2.3.21"

                [plugins]
                kotlinJvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
                """.trimIndent(),
            ),
            currentCatalog = tempCatalog(
                """
                [versions]
                kotlin = "2.4.0"

                [plugins]
                kotlinJvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
                """.trimIndent(),
            ),
            config = DependencyReportConfig(
                sources = listOf(SourceDefinition(match = SourceMatch(alias = "kotlin"), githubRepo = "JetBrains/kotlin")),
            ),
        )

        assertEquals(2, llmRequest.documents.size)
        assertTrue(llmRequest.documents.any { it.content == "Useful release content." })
        assertTrue(llmRequest.documents.any { it.title == "Omitted release-note documents" && it.content.contains("2.3.30") })

        val entry = report.entries.single()
        assertFalse(entry.fallbackUsed)
        assertEquals(RiskLevel.MEDIUM, entry.narrative.riskAssessment.level)
        assertTrue(entry.narrative.riskAssessment.signals.contains("partial-release-notes-context"))
    }

    @Test
    fun `skips llm when target version document is omitted even if older version content is available`() {
        var llmCalls = 0
        val report = DependencyReportGenerator(
            documentFetcher = FakeFetcher(
                mapOf(
                    "https://github.com/JetBrains/kotlin/releases" to listOf(
                        FetchedDocument(
                            title = "Kotlin 2.3.21",
                            sourceUrl = "https://github.com/JetBrains/kotlin/releases/tag/v2.3.21",
                            content = "Useful 2.3.21 release content.",
                            version = "2.3.21",
                        ),
                        FetchedDocument(
                            title = "Kotlin 2.4.0",
                            sourceUrl = "https://github.com/JetBrains/kotlin/releases/tag/v2.4.0",
                            content = "Document content omitted because it exceeded 12000 characters. See release notes: https://github.com/JetBrains/kotlin/releases/tag/v2.4.0",
                            version = "2.4.0",
                            contentTruncated = true,
                            originalContentLength = 60_000,
                        ),
                    ),
                ),
            ),
            llmReportGenerator = object : LlmReportGenerator {
                override fun generate(request: LlmReportRequest): LlmGenerationResult {
                    llmCalls += 1
                    return LlmGenerationResult.Failure("should not be called")
                }
            },
        ).generate(
            previousCatalog = tempCatalog(
                """
                [versions]
                kotlin = "2.3.20"

                [plugins]
                kotlinJvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
                """.trimIndent(),
            ),
            currentCatalog = tempCatalog(
                """
                [versions]
                kotlin = "2.4.0"

                [plugins]
                kotlinJvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
                """.trimIndent(),
            ),
            config = DependencyReportConfig(
                sources = listOf(SourceDefinition(match = SourceMatch(alias = "kotlin"), githubRepo = "JetBrains/kotlin")),
            ),
        )

        assertEquals(0, llmCalls)
        val entry = report.entries.single()
        assertTrue(entry.fallbackUsed)
        assertTrue(entry.errors.any { it.contains("Target version release-note document was omitted due to size limits") })
    }
}

private fun tempCatalog(content: String): Path {
    val file = kotlin.io.path.createTempFile("dependency-report-test", ".toml")
    file.writeText(content)
    return file
}

private class FakeFetcher(
    private val documentsByUrl: Map<String, List<FetchedDocument>>,
) : ReleaseDocumentFetcher {
    override fun fetch(request: ReleaseFetchRequest): FetchResult {
        val documents = documentsByUrl[request.source.sourceUrl]
        return if (documents != null) FetchResult.Success(documents)
        else FetchResult.Failure("No fixture document configured for ${request.source.sourceUrl}")
    }
}
