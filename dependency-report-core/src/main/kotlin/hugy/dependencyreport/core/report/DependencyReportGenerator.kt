package hugy.dependencyreport.core.report

import hugy.dependencyreport.core.catalog.CatalogDiffService
import hugy.dependencyreport.core.catalog.VersionCatalogParser
import hugy.dependencyreport.core.config.DependencyReportConfig
import hugy.dependencyreport.core.fetch.FetchResult
import hugy.dependencyreport.core.fetch.ReleaseFetchRequest
import hugy.dependencyreport.core.fetch.ReleaseDocumentFetcher
import hugy.dependencyreport.core.fetch.VersionSelection
import hugy.dependencyreport.core.llm.LlmGenerationResult
import hugy.dependencyreport.core.llm.LlmReportGenerator
import hugy.dependencyreport.core.llm.LlmReportRequest
import hugy.dependencyreport.core.model.GeneratedReport
import hugy.dependencyreport.core.model.FetchedDocument
import hugy.dependencyreport.core.model.UpgradeKind
import hugy.dependencyreport.core.model.UpgradeReportEntry
import hugy.dependencyreport.core.source.ReleaseSourceResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

class DependencyReportGenerator(
    private val parser: VersionCatalogParser = VersionCatalogParser(),
    private val diffService: CatalogDiffService = CatalogDiffService(),
    private val sourceResolverFactory: (DependencyReportConfig) -> ReleaseSourceResolver = ::ReleaseSourceResolver,
    private val documentFetcher: ReleaseDocumentFetcher,
    private val llmReportGenerator: LlmReportGenerator,
    private val fallbackNarrativeFactory: FallbackNarrativeFactory = FallbackNarrativeFactory(),
    private val narrativeRiskPostProcessor: NarrativeRiskPostProcessor = NarrativeRiskPostProcessor(),
    private val reportRenderer: ReportRenderer = ReportRenderer(),
) {
    fun generate(
        previousCatalog: Path,
        currentCatalog: Path,
        config: DependencyReportConfig,
    ): GeneratedReport {
        logger.info { "Parsing version catalogs" }
        val previous = parser.parse(previousCatalog)
        val current = parser.parse(currentCatalog)
        val changeDetection = diffService.detectVersionAliasChanges(previous, current)
        logger.info { "Detected ${changeDetection.changes.size} included version alias changes" }
        if (changeDetection.warnings.isNotEmpty()) {
            logger.warn { "Version change detection produced ${changeDetection.warnings.size} warning(s)" }
        }
        val targets = diffService.resolveUpgradeTargets(changeDetection.changes, current)
        logger.info { "Resolved ${targets.size} upgrade targets" }

        if (targets.isEmpty()) {
            logger.info { "No resolved targets. Exiting prematurely." }
            return reportRenderer.render(
                entries = emptyList(),
                false,
                llmSucceeded = false,
                warnings = changeDetection.warnings
            )

        }
        val sourceResolver = sourceResolverFactory(config)

        var llmAttempted = false
        var llmSucceeded = false

        val entries = targets.map { target ->
            logger.info {
                "Processing target alias=${target.change.alias} ${target.change.previousVersion} -> ${target.change.currentVersion} kind=${target.kind}"
            }
            val resolution = sourceResolver.resolve(target)
            logger.info {
                "Resolved source for alias=${target.change.alias}: matchedBy=${resolution.matchedBy}, sourceType=${resolution.source.type}, displayName=${resolution.source.displayName}"
            }
            val errors = mutableListOf<String>()

            if (resolution.linkOnly) {
                logger.info { "Skipping fetch and LLM enrichment for alias=${target.change.alias} because the source is configured as linkOnly" }
                val narrative = narrativeRiskPostProcessor.apply(
                    target,
                    fallbackNarrativeFactory.createLinkOnly(target, resolution.source.sourceUrl),
                )
                return@map UpgradeReportEntry(
                    target = target,
                    sourceResolution = resolution,
                    documents = emptyList(),
                    narrative = narrative,
                    fallbackUsed = false,
                    errors = emptyList(),
                )
            }

            val documents = when (
                val fetchResult = documentFetcher.fetch(
                    ReleaseFetchRequest(
                        source = resolution.source,
                        target = target,
                    ),
                )
            ) {
                is FetchResult.Success -> {
                    logger.info { "Fetched ${fetchResult.documents.size} document(s) for alias=${target.change.alias}" }
                    fetchResult.documents
                }

                is FetchResult.Failure -> {
                    logger.warn { "Fetching failed for alias=${target.change.alias}: ${fetchResult.reason}" }
                    errors += fetchResult.reason
                    emptyList()
                }
            }

            val meaningfulDocuments = documents.filterNot { it.contentTruncated }
            val omittedDocuments = documents.filter { it.contentTruncated }
            val hasPartialContext = meaningfulDocuments.isNotEmpty() && omittedDocuments.isNotEmpty()
            val targetVersionDocumentOmitted = omittedDocuments.any {
                isSameVersion(it.version, target.change.currentVersion)
            }
            val llmDocuments = when {
                targetVersionDocumentOmitted -> emptyList()
                meaningfulDocuments.isEmpty() -> emptyList()
                omittedDocuments.isEmpty() -> meaningfulDocuments
                else -> meaningfulDocuments + buildOmittedDocumentsNote(target, omittedDocuments)
            }

            val llmResult = if (llmDocuments.isNotEmpty()) {
                llmAttempted = true
                logger.info {
                    "Attempting LLM enrichment for alias=${target.change.alias} with ${llmDocuments.size} prompt document(s) " +
                        "(meaningful=${meaningfulDocuments.size}, omitted=${omittedDocuments.size})"
                }
                llmReportGenerator.generate(LlmReportRequest(target, llmDocuments))
            } else {
                val reason = when {
                    documents.isEmpty() -> "No fetched documents available for LLM enrichment"
                    targetVersionDocumentOmitted -> "Target version release-note document was omitted due to size limits; skipping LLM enrichment"
                    else -> "All fetched documents were omitted due to size limits; skipping LLM enrichment"
                }
                LlmGenerationResult.Failure(reason)
            }

            val fallbackUsed: Boolean
            val narrative = when (llmResult) {
                is LlmGenerationResult.Success -> {
                    llmSucceeded = true
                    fallbackUsed = false
                    logger.info { "LLM enrichment succeeded for alias=${target.change.alias}" }
                    llmResult.narrative
                }

                is LlmGenerationResult.Failure -> {
                    logger.warn { "Using fallback narrative for alias=${target.change.alias}: ${llmResult.reason}" }
                    errors += llmResult.reason
                    fallbackUsed = true
                    fallbackNarrativeFactory.create(target, documents, errors)
                }
            }

            val processedNarrative = narrativeRiskPostProcessor.apply(
                target = target,
                narrative = narrative,
                hasPartiallyOmittedDocuments = hasPartialContext,
            )

            UpgradeReportEntry(
                target = target,
                sourceResolution = resolution,
                documents = documents,
                narrative = processedNarrative,
                fallbackUsed = fallbackUsed,
                errors = errors,
            )
        }

        logger.info {
            "Rendering report with ${entries.size} entries (fallbacks=${entries.count { it.fallbackUsed }}, unresolved=${entries.count { it.sourceResolution.source.type.name == "UNRESOLVED" }})"
        }
        return reportRenderer.render(entries, llmAttempted, llmSucceeded, changeDetection.warnings)
    }

    private fun buildOmittedDocumentsNote(
        target: hugy.dependencyreport.core.model.UpgradeTarget,
        omittedDocuments: List<FetchedDocument>,
    ): FetchedDocument {
        val omittedVersions = omittedDocuments.mapNotNull { it.version }.distinct()
        val omittedVersionSummary = if (omittedVersions.isEmpty()) {
            "Some selected release-note documents"
        } else {
            "Release-note documents for versions ${omittedVersions.joinToString(", ")}"
        }
        return FetchedDocument(
            title = "Omitted release-note documents",
            sourceUrl = omittedDocuments.first().sourceUrl,
            version = target.change.currentVersion,
            content = "$omittedVersionSummary were omitted because they exceeded the configured character limit. " +
                "Base summary and risk must account for partial context.",
            contentTruncated = false,
        )
    }

    private fun isSameVersion(left: String?, right: String): Boolean {
        if (left.isNullOrBlank()) {
            return false
        }
        val leftParsed = VersionSelection.parse(left)
        val rightParsed = VersionSelection.parse(right)
        return if (leftParsed != null && rightParsed != null) {
            leftParsed.normalized == rightParsed.normalized
        } else {
            left == right
        }
    }
}
