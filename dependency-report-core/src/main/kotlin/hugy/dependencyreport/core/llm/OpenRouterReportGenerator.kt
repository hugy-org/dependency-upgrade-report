package hugy.dependencyreport.core.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.kotlinModule
import hugy.dependencyreport.core.config.LlmConfig
import hugy.dependencyreport.core.model.GeneratedNarrative
import hugy.dependencyreport.core.model.RiskAssessment
import hugy.dependencyreport.core.model.RiskLevel
import hugy.dependencyreport.core.model.UpgradeKind
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

class OpenRouterReportGenerator(
    private val llmConfig: LlmConfig,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : LlmReportGenerator {
    private val objectMapper = jacksonObjectMapper()
    private val lenientObjectMapper: ObjectMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
        .build()

    override fun generate(request: LlmReportRequest): LlmGenerationResult {
        val model = llmConfig.model?.takeIf { it.isNotBlank() }
            ?: return LlmGenerationResult.Failure("OpenRouter model is not configured")
        val apiKey = llmConfig.resolveApiKey()
            ?: return LlmGenerationResult.Failure(
                if (!llmConfig.apiKeyEnv.isNullOrBlank()) {
                    "OpenRouter apiKey is not configured: environment variable ${llmConfig.apiKeyEnv} is missing or blank"
                } else {
                    "OpenRouter apiKey is not configured"
                },
            )

        logger.info {
            "Starting OpenRouter enrichment for alias=${request.target.change.alias} using model=${model} with ${request.documents.size} document(s)"
        }
        llmConfig.apiKeyEnv?.takeIf { it.isNotBlank() }?.let { envName ->
            if (llmConfig.apiKey.isNullOrBlank()) {
                logger.info { "Using OpenRouter API key from environment variable $envName" }
            }
        }
        val payload = buildPayload(model, request)
        val attempts = llmConfig.retryCount.coerceAtLeast(1)
        var lastFailure: String? = null
        var httpRequest: String
        var response : HttpResponse<String>
        repeat(attempts) { attemptIndex ->
            try {
                val attemptNumber = attemptIndex + 1
                logger.info {
                    "OpenRouter attempt ${attemptNumber}/${attempts} for alias=${request.target.change.alias}"
                }
                httpRequest = objectMapper.writeValueAsString(payload)
                val httpRequest = HttpRequest.newBuilder(URI.create(llmConfig.baseUrl))
                    .POST(HttpRequest.BodyPublishers.ofString(httpRequest))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-Title", "dependency-upgrade-report")
                    .timeout(Duration.ofMillis(llmConfig.requestTimeoutMs.coerceAtLeast(1)))
                    .build()
                 response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    lastFailure = "OpenRouter returned HTTP ${response.statusCode()}"
                    logger.warn {
                        "OpenRouter returned HTTP ${response.statusCode()} for alias=${request.target.change.alias} on attempt ${attemptNumber}/${attempts}"
                    }
                    return@repeat
                }

                val apiResponse = objectMapper.readValue(response.body(), OpenRouterResponse::class.java)
                val message = apiResponse.choices.firstOrNull()?.message
                    ?: run {
                        lastFailure = "OpenRouter response did not contain an assistant message"
                        logger.warn {
                            "OpenRouter response missing assistant message for alias=${request.target.change.alias} on attempt ${attemptNumber}/${attempts}"
                        }
                        return@repeat
                    }
                val content = extractMessageContent(message)
                    ?: run {
                        lastFailure = "OpenRouter response did not contain assistant content"
                        logger.warn {
                            "OpenRouter response missing assistant content for alias=${request.target.change.alias} on attempt ${attemptNumber}/${attempts}"
                        }
                        return@repeat
                    }
                val structured = parseStructuredNarrative(content, request)
                logger.info { "OpenRouter enrichment succeeded for alias=${request.target.change.alias}" }
                return LlmGenerationResult.Success(
                    GeneratedNarrative(
                        headline = structured.headline,
                        summary = structured.summary,
                        description = structured.description,
                        riskAssessment = RiskAssessment(
                            level = parseRiskLevel(structured.riskLevel),
                            summary = structured.riskSummary,
                            signals = structured.riskSignals,
                        ),
                    ),
                )
            } catch (exception: Exception) {
                lastFailure = "OpenRouter generation failed: ${exception.message}"
                logger.warn(exception) {
                    "OpenRouter generation failed for alias=${request.target.change.alias} on attempt ${attemptIndex + 1}/${attempts}"
                }
            }

            if (attemptIndex < attempts - 1 && llmConfig.retryDelayMs > 0) {
                logger.info {
                    "Waiting ${llmConfig.retryDelayMs}ms before retrying OpenRouter for alias=${request.target.change.alias}"
                }
                try {
                    Thread.sleep(llmConfig.retryDelayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return LlmGenerationResult.Failure(
                        lastFailure ?: "OpenRouter generation interrupted during retry delay"
                    )
                }
            }
        }
        logger.warn { "OpenRouter enrichment failed after $attempts attempt(s) for alias=${request.target.change.alias}" }
        return LlmGenerationResult.Failure(lastFailure ?: "OpenRouter generation failed")
    }

    private fun buildPayload(model: String, request: LlmReportRequest): Map<String, Any> {
        val documentsText = request.documents.joinToString("\n\n") { document ->
            buildString {
                appendLine("Title: ${document.title}")
                appendLine("Version: ${document.version ?: "unknown"}")
                appendLine("Source URL: ${document.sourceUrl}")
                appendLine("Content:")
                appendLine(document.content)
            }.trim()
        }
        val target = request.target
        val usages = if (target.usages.isEmpty()) {
            "none"
        } else {
            target.usages.joinToString(", ") { "${it.usageType}:${it.identifier}" }
        }
        val systemPrompt = """
            You generate deterministic dependency-upgrade report content.
            Use only the provided release-note documents and upgrade metadata.
            Return concise, concrete output as strict JSON matching the schema.
            Do not recommend whether to merge or apply the upgrade.
            Produce one useful human-readable description that can be reused for both Jira and merge request text.
            The headline must be short.
            The summary must add detail and must not repeat the headline verbatim.
            The description must be suitable for MR and Jira text and must not repeat the headline verbatim.
        """.trimIndent()
        val userPrompt = """
            Upgrade alias: ${target.change.alias}
            Previous version: ${target.change.previousVersion}
            Current version: ${target.change.currentVersion}
            Upgrade kind: ${target.kind}
            Usages: $usages

            Release note documents:
            $documentsText
        """.trimIndent()

        return mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt),
            ),
            "temperature" to 0,
            "response_format" to mapOf(
                "type" to "json_schema",
                "json_schema" to mapOf(
                    "name" to "dependency_upgrade_report",
                    "strict" to true,
                    "schema" to structuredNarrativeSchema(),
                ),
            ),
        )
    }

    private fun structuredNarrativeSchema(): Map<String, Any> {
        return mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "properties" to mapOf(
                "headline" to mapOf(
                    "type" to "string",
                    "description" to "Short heading naming the dependency and the version change. Keep it brief and title-like.",
                ),
                "summary" to mapOf(
                    "type" to "string",
                    "description" to "One to three concise sentences summarizing the upgrade using the provided release notes. Do not repeat the headline verbatim.",
                ),
                "description" to mapOf(
                    "type" to "string",
                    "description" to "One to three sentences suitable for both a merge request and Jira description, mentioning the dependency, version change, key changes, and review focus. Do not repeat the headline verbatim.",
                ),
                "riskLevel" to mapOf(
                    "type" to "string",
                    "enum" to listOf("LOW", "MEDIUM", "HIGH", "UNKNOWN"),
                ),
                "riskSummary" to mapOf(
                    "type" to "string",
                    "description" to "One concise sentence justifying the risk level.",
                ),
                "riskSignals" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string"),
                    "description" to "Short machine-readable risk signals such as breaking-change, plugin-upgrade, concurrency-change, build-tooling, etc.",
                ),
            ),
            "required" to listOf(
                "headline",
                "summary",
                "description",
                "riskLevel",
                "riskSummary",
                "riskSignals",
            ),
        )
    }

    private fun parseRiskLevel(value: String): RiskLevel {
        return RiskLevel.entries.firstOrNull { it.name == value } ?: RiskLevel.UNKNOWN
    }

    private fun extractMessageContent(message: OpenRouterMessage): String? {
        val contentNode = message.content
        if (contentNode != null && !contentNode.isNull) {
            if (contentNode.isTextual) {
                return contentNode.asString().takeIf { it.isNotBlank() }
            }
            if (contentNode.isArray) {
                val text = contentNode.mapNotNull { node ->
                    when {
                        node.isTextual -> node.asString()
                        node.isObject && node.path("text").isTextual -> node.path("text").asString()
                        node.isObject && node.path("content").isTextual -> node.path("content").asString()
                        else -> null
                    }
                }.joinToString("\n").trim()
                if (text.isNotBlank()) {
                    return text
                }
            }
        }
        return message.refusal?.takeIf { it.isNotBlank() }
            ?: message.reasoning?.takeIf { it.isNotBlank() }
    }

    private fun parseStructuredNarrative(content: String, request: LlmReportRequest): StructuredNarrative {
        val candidates = listOfNotNull(
            content.trim().takeIf { it.isNotBlank() },
            stripMarkdownFence(content),
            extractJsonObject(content),
            extractJsonObject(stripMarkdownFence(content) ?: ""),
        ).distinct()

        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                return normalizeStructuredNarrative(
                    objectMapper.readTree(candidate),
                    request,
                )
            } catch (exception: Exception) {
                lastError = exception
            }
            try {
                return normalizeStructuredNarrative(
                    lenientObjectMapper.readTree(candidate),
                    request,
                )
            } catch (exception: Exception) {
                lastError = exception
            }
            try {
                return normalizeStructuredNarrative(
                    lenientObjectMapper.readTree(escapeControlCharsInsideStrings(candidate)),
                    request,
                )
            } catch (exception: Exception) {
                lastError = exception
            }
        }

        throw lastError ?: IllegalArgumentException("Could not parse structured narrative from OpenRouter response")
    }

    private fun normalizeStructuredNarrative(
        root: JsonNode,
        request: LlmReportRequest,
    ): StructuredNarrative {
        require(root.isObject) { "Structured narrative root must be a JSON object" }

        val target = request.target
        val fallbackHeadline =
            "${target.change.alias}: ${target.change.previousVersion} -> ${target.change.currentVersion}"
        val firstDocumentLine = request.documents.firstOrNull()?.content
            ?.substringBefore('\n')
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val genericSummary = buildString {
            append("Upgrade ${target.change.alias} from ${target.change.previousVersion} to ${target.change.currentVersion}.")
            firstDocumentLine?.let {
                append(" ")
                append(it.removeSuffix("."))
                append(".")
            }
        }
        val summary = root.requiredText("summary")
            ?: root.requiredText("description")
            ?: root.requiredText("mergeRequestSummary")
            ?: root.requiredText("jiraSummary")
            ?: genericSummary
        val description = root.requiredText("description")
            ?: root.requiredText("summary")
            ?: root.requiredText("mergeRequestSummary")
            ?: summary
        val headline = root.requiredText("headline") ?: fallbackHeadline
        val riskLevel = root.requiredText("riskLevel")
            ?.uppercase()
            ?.takeIf { it in RiskLevel.entries.map { level -> level.name } }
            ?: defaultRiskLevel(target.kind).name
        val riskSummary = root.requiredText("riskSummary")
            ?: "Generated from partial LLM output; review the linked release notes for full context."
        val riskSignals = root.textArray("riskSignals").ifEmpty {
            listOf("llm-partial")
        }

        return StructuredNarrative(
            headline = headline,
            summary = summary,
            description = description,
            riskLevel = riskLevel,
            riskSummary = riskSummary,
            riskSignals = riskSignals,
        )
    }

    private fun defaultRiskLevel(kind: UpgradeKind): RiskLevel {
        return when (kind) {
            UpgradeKind.BOM, UpgradeKind.MIXED -> RiskLevel.HIGH
            UpgradeKind.GRADLE_PLUGIN, UpgradeKind.LIBRARY -> RiskLevel.MEDIUM
        }
    }

    private fun stripMarkdownFence(content: String): String? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) {
            return null
        }
        return trimmed
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun extractJsonObject(content: String): String? {
        val start = content.indexOf('{')
        if (start < 0) {
            return null
        }
        var depth = 0
        var inString = false
        var escaping = false
        for (index in start until content.length) {
            val char = content[index]
            if (escaping) {
                escaping = false
                continue
            }
            when (char) {
                '\\' -> if (inString) escaping = true
                '"' -> inString = !inString
                '{' -> if (!inString) depth += 1
                '}' -> if (!inString) {
                    depth -= 1
                    if (depth == 0) {
                        return content.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun escapeControlCharsInsideStrings(content: String): String {
        val result = StringBuilder(content.length + 32)
        var inString = false
        var escaping = false
        for (char in content) {
            if (escaping) {
                result.append(char)
                escaping = false
                continue
            }
            when (char) {
                '\\' -> {
                    result.append(char)
                    if (inString) {
                        escaping = true
                    }
                }

                '"' -> {
                    result.append(char)
                    inString = !inString
                }

                '\n' -> if (inString) result.append("\\n") else result.append(char)
                '\r' -> if (inString) result.append("\\r") else result.append(char)
                '\t' -> if (inString) result.append("\\t") else result.append(char)
                else -> result.append(char)
            }
        }
        return result.toString()
    }
}

private fun JsonNode.requiredText(fieldName: String): String? {
    val value = path(fieldName)
    return value.asString().trim().takeIf { value.isValueNode && it.isNotBlank() }
}

private fun JsonNode.textArray(fieldName: String): List<String> {
    val value = path(fieldName)
    if (!value.isArray) {
        return emptyList()
    }
    return value.mapNotNull { node ->
        node.asString().trim().takeIf { node.isValueNode && it.isNotBlank() }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class OpenRouterResponse(
    val choices: List<OpenRouterChoice> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class OpenRouterChoice(
    val message: OpenRouterMessage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class OpenRouterMessage(
    val content: JsonNode? = null,
    val refusal: String? = null,
    val reasoning: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class StructuredNarrative(
    val headline: String,
    val summary: String,
    val description: String,
    val riskLevel: String,
    val riskSummary: String,
    val riskSignals: List<String>,
)
