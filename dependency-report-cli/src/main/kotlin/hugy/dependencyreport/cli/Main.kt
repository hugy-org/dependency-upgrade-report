package hugy.dependencyreport.cli

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import hugy.dependencyreport.core.config.DependencyReportConfig
import hugy.dependencyreport.core.fetch.HttpReleaseDocumentFetcher
import hugy.dependencyreport.core.json.ObjectMappers
import hugy.dependencyreport.core.llm.DisabledLlmReportGenerator
import hugy.dependencyreport.core.llm.LlmReportGenerator
import hugy.dependencyreport.core.llm.StaticLlmReportGenerator
import hugy.dependencyreport.core.report.DependencyReportGenerator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = try {
        runCli(args.toList())
    } catch (exception: Exception) {
        System.err.println("dependency-report failed: ${exception::class.simpleName}: ${exception.message}")
        1
    }
    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}

internal fun runCli(args: List<String>): Int {
    if (args.isEmpty() || args.first() != "generate") {
        printUsage()
        return 1
    }

    val options = parseOptions(args.drop(1))
    val previousCatalog = options["previous-catalog"]?.let(Path::of)
    val currentCatalog = options["current-catalog"]?.let(Path::of)
    val configPath = options["config"]?.let(Path::of)
    val outputDir = options["output-dir"]?.let(Path::of)

    if (previousCatalog == null || currentCatalog == null || configPath == null || outputDir == null) {
        printUsage()
        return 1
    }

    val yamlMapper = YAMLMapper().findAndRegisterModules()
    val config = yamlMapper.readValue<DependencyReportConfig>(Files.readString(configPath))
    val llmGenerator = createLlmGenerator(config)

    val report = DependencyReportGenerator(
        documentFetcher = HttpReleaseDocumentFetcher(config.github),
        llmReportGenerator = llmGenerator,
    ).generate(previousCatalog, currentCatalog, config)

    outputDir.createDirectories()
    outputDir.resolve("report.json").writeText(ObjectMappers.json.writeValueAsString(report))
    outputDir.resolve("summary.txt").writeText(report.outputs.summaryText)
    outputDir.resolve("commit-body.txt").writeText(report.outputs.commitBody)
    outputDir.resolve("mr-description.txt").writeText(report.outputs.mergeRequestDescription)
    outputDir.resolve("jira-description.txt").writeText(report.outputs.jiraDescription)
    outputDir.resolve("reviewer-checklist.md").writeText(report.outputs.reviewerChecklist)
    outputDir.resolve("risk-summary.md").writeText(report.outputs.riskSummary)

    return 0
}

private fun createLlmGenerator(config: DependencyReportConfig): LlmReportGenerator {
    return when (config.llm.mode.lowercase()) {
        "static" -> StaticLlmReportGenerator()
        else -> DisabledLlmReportGenerator()
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
            --previous-catalog /path/to/libs.before.toml \
            --current-catalog /path/to/libs.after.toml \
            --config /path/to/dependency-report.yaml \
            --output-dir /path/to/output
        """.trimIndent(),
    )
}
