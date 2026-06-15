package hugy.dependencyreport.cli

import tools.jackson.databind.MapperFeature
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue
import hugy.dependencyreport.core.config.DependencyReportConfig
import hugy.dependencyreport.core.config.LLMMode
import hugy.dependencyreport.core.fetch.HttpReleaseDocumentFetcher
import hugy.dependencyreport.core.json.ObjectMappers
import hugy.dependencyreport.core.llm.LlmReportGenerator
import hugy.dependencyreport.core.llm.OllamaReportGenerator
import hugy.dependencyreport.core.llm.OpenRouterReportGenerator
import hugy.dependencyreport.core.llm.StaticLlmReportGenerator
import hugy.dependencyreport.core.report.DependencyReportGenerator
import hugy.dependencyreport.core.report.JiraDescriptionFormatter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

private enum class OutputFile(val fileName: String) {
    REPORT_JSON("report.json"),
    COMMIT_BODY("commit-body.txt"),
    MR_DESCRIPTION("mr-description.txt"),
    JIRA_DESCRIPTION("jira-description.txt"),
    JIRA_DESCRIPTION_JSON("jira-description.json"),
}

fun main(args: Array<String>) {
    val exitCode = try {
        runCli(args.toList())
    } catch (exception: Exception) {
        logger.error(exception) { "dependency-report failed with an unhandled exception" }
        System.err.println("dependency-report failed: ${exception::class.simpleName}: ${exception.message}")
        1
    }
    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}

internal fun runCli(args: List<String>): Int {
    return runCli(args, Path.of("").toAbsolutePath())
}

internal fun runCli(
    args: List<String>,
    workingDirectory: Path,
): Int {
    logger.info { "Starting dependency-report CLI in ${workingDirectory.toAbsolutePath()}" }
    if (args.isEmpty() || args.first() != "generate") {
        printUsage()
        return 1
    }

    val options = parseOptions(args.drop(1))
    val configPath = options["config"]?.let(Path::of)
    val outputDir = options["output-dir"]?.let(Path::of)

    if (configPath == null || outputDir == null) {
        printUsage()
        return 1
    }

    logger.info {
        "Resolving catalog inputs (explicitSnapshots=${options.containsKey("previous-catalog") || options.containsKey("current-catalog")}, " +
                "catalogPath=${options["catalog-path"] ?: "<default>"}, gitRef=${options["git-ref"] ?: "HEAD"})"
    }
    val catalogInputs = GitCatalogSnapshotProvider(workingDirectory).resolve(
        explicitPreviousCatalog = options["previous-catalog"]?.let(Path::of),
        explicitCurrentCatalog = options["current-catalog"]?.let(Path::of),
        catalogPathOption = options["catalog-path"],
        gitRef = options["git-ref"] ?: "HEAD",
    )

    logger.info { "Loading configuration from ${configPath.toAbsolutePath()}" }
    val yamlMapper = YAMLMapper.builder()
        .addModule(kotlinModule())
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
        .build()
    val config = yamlMapper.readValue<DependencyReportConfig>(Files.readString(configPath))
    val llmGenerator = createLlmGenerator(config)
    val jiraDescriptionFormatter = JiraDescriptionFormatter()
    logger.info { "Configured LLM mode: ${config.llm.mode}" }

    logger.info {
        "Generating report using previous catalog ${catalogInputs.previousCatalog.toAbsolutePath()} and current catalog ${catalogInputs.currentCatalog.toAbsolutePath()}"
    }
    val report = DependencyReportGenerator(
        documentFetcher = HttpReleaseDocumentFetcher(config.github, config.fetch),
        llmReportGenerator = llmGenerator,
    ).generate(catalogInputs.previousCatalog, catalogInputs.currentCatalog, config)

    outputDir.createDirectories()
    cleanToolOutputFiles(outputDir)

    if (report.entries.isEmpty()) {
        writeOutput(outputDir, OutputFile.REPORT_JSON, ObjectMappers.json.writeValueAsString(report))
        return 0
    }

    logger.info { "Writing report outputs to ${outputDir.toAbsolutePath()}" }
    writeOutput(outputDir, OutputFile.REPORT_JSON, ObjectMappers.json.writeValueAsString(report))
    writeOutput(outputDir, OutputFile.COMMIT_BODY, report.outputs.commitBody)
    writeOutput(outputDir, OutputFile.MR_DESCRIPTION, report.outputs.unifiedDescription)
    writeOutput(outputDir, OutputFile.JIRA_DESCRIPTION, report.outputs.unifiedDescription)
    writeOutput(
        outputDir,
        OutputFile.JIRA_DESCRIPTION_JSON,
        ObjectMappers.json.writeValueAsString(jiraDescriptionFormatter.format(report.outputs.unifiedDescription)),
    )

    logger.info {
        "Report generation completed (entries=${report.entries.size}, fallbacks=${report.manifest.fallbackCount}, unresolved=${report.manifest.unresolvedCount})"
    }
    return 0
}

private fun cleanToolOutputFiles(outputDir: Path) {
    OutputFile.entries.forEach { outputFile ->
        outputDir.resolve(outputFile.fileName).deleteIfExists()
    }
}

private fun writeOutput(outputDir: Path, outputFile: OutputFile, content: String) {
    outputDir.resolve(outputFile.fileName).writeText(content)
}

private fun createLlmGenerator(config: DependencyReportConfig): LlmReportGenerator {
    return when (config.llm.mode) {
        LLMMode.STATIC -> StaticLlmReportGenerator()
        LLMMode.OPENROUTER -> OpenRouterReportGenerator(config.llm)
        LLMMode.OLLAMA -> OllamaReportGenerator(config.llm)
    }
}

private fun parseOptions(args: List<String>): Map<String, String> {
    val values = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val current = args[index]
        if (!current.startsWith("--") || index + 1 >= args.size) {
            throw IllegalArgumentException("Invalid argument sequence near '$current'")
        }
        values[current.removePrefix("--")] = args[index + 1]
        index += 2
    }
    return values
}

private fun printUsage() {
    println(
        """
        Usage:
          dependency-report generate \
            --config /path/to/dependency-report.yaml \
            --output-dir /path/to/output

        Default git-backed mode:
          dependency-report generate \
            --config /path/to/dependency-report.yaml \
            --output-dir /path/to/output \
            [--catalog-path gradle/libs.catalog.toml] \
            [--git-ref HEAD]

        Explicit snapshot mode:
          dependency-report generate \
            --previous-catalog /path/to/libs.before.toml \
            --current-catalog /path/to/libs.after.toml \
            --config /path/to/dependency-report.yaml \
            --output-dir /path/to/output
        """.trimIndent(),
    )
}
