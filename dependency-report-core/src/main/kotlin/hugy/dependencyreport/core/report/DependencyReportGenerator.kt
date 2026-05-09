package hugy.dependencyreport.core.report

import hugy.dependencyreport.core.catalog.CatalogDiffService
import hugy.dependencyreport.core.catalog.VersionCatalogParser
import hugy.dependencyreport.core.config.DependencyReportConfig
import hugy.dependencyreport.core.fetch.FetchResult
import hugy.dependencyreport.core.fetch.ReleaseFetchRequest
import hugy.dependencyreport.core.fetch.ReleaseDocumentFetcher
import hugy.dependencyreport.core.llm.LlmGenerationResult
import hugy.dependencyreport.core.llm.LlmReportGenerator
import hugy.dependencyreport.core.llm.LlmReportRequest
import hugy.dependencyreport.core.model.GeneratedReport
import hugy.dependencyreport.core.model.UpgradeReportEntry
import hugy.dependencyreport.core.source.ReleaseSourceResolver
import java.nio.file.Path

class DependencyReportGenerator(
    private val parser: VersionCatalogParser = VersionCatalogParser(),
    private val diffService: CatalogDiffService = CatalogDiffService(),
    private val sourceResolverFactory: (DependencyReportConfig) -> ReleaseSourceResolver = ::ReleaseSourceResolver,
    private val documentFetcher: ReleaseDocumentFetcher,
    private val llmReportGenerator: LlmReportGenerator,
    private val fallbackNarrativeFactory: FallbackNarrativeFactory = FallbackNarrativeFactory(),
    private val reportRenderer: ReportRenderer = ReportRenderer(),
) {
    fun generate(
        previousCatalog: Path,
        currentCatalog: Path,
        config: DependencyReportConfig,
    ): GeneratedReport {
        val previous = parser.parse(previousCatalog)
        val current = parser.parse(currentCatalog)
        val changes = diffService.detectVersionAliasChanges(previous, current)
        val targets = diffService.resolveUpgradeTargets(changes, current)
        val sourceResolver = sourceResolverFactory(config)

        var llmAttempted = false
        var llmSucceeded = false

        val entries = targets.map { target ->
            val resolution = sourceResolver.resolve(target)
            val errors = mutableListOf<String>()

            val documents = when (
                val fetchResult = documentFetcher.fetch(
                    ReleaseFetchRequest(
                        source = resolution.source,
                        target = target,
                    ),
                )
            ) {
                is FetchResult.Success -> fetchResult.documents
                is FetchResult.Failure -> {
                    errors += fetchResult.reason
                    emptyList()
                }
            }

            val llmResult = if (documents.isNotEmpty()) {
                llmAttempted = true
                llmReportGenerator.generate(LlmReportRequest(target, documents))
            } else {
                LlmGenerationResult.Failure("No fetched documents available for LLM enrichment")
            }

            val fallbackUsed: Boolean
            val narrative = when (llmResult) {
                is LlmGenerationResult.Success -> {
                    llmSucceeded = true
                    fallbackUsed = false
                    llmResult.narrative
                }

                is LlmGenerationResult.Failure -> {
                    errors += llmResult.reason
                    fallbackUsed = true
                    fallbackNarrativeFactory.create(target, errors)
                }
            }

            UpgradeReportEntry(
                target = target,
                sourceResolution = resolution,
                documents = documents,
                narrative = narrative,
                fallbackUsed = fallbackUsed,
                errors = errors,
            )
        }

        return reportRenderer.render(entries, llmAttempted, llmSucceeded)
    }
}
