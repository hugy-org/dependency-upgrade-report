package hugy.dependencyreport.core.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import tools.jackson.databind.JsonNode
import hugy.dependencyreport.core.config.LlmConfig
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
    private val objectMapper = StructuredNarrativeSupport.objectMapper

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
        val modelRouting = if (llmConfig.fallbackModels.isEmpty()) {
            mapOf("model" to model)
        } else {
            mapOf("models" to listOf(model) + llmConfig.fallbackModels)
        }
        val payload = StructuredNarrativeSupport.buildStructuredPayload(request) + modelRouting + mapOf(
            "response_format" to StructuredNarrativeSupport.buildStructuredResponseFormat(),
        )
        val attempts = llmConfig.retryCount.coerceAtLeast(1)
        var lastFailure: String? = null
        var httpRequest: String
        var response: HttpResponse<String>
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
                logger.info {
                    "OpenRouter enrichment succeeded for alias=${request.target.change.alias} using model=${apiResponse.model ?: model}"
                }
                return LlmGenerationResult.Success(
                    StructuredNarrativeSupport.parseStructuredNarrative(content, request),
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
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class OpenRouterResponse(
    val choices: List<OpenRouterChoice> = emptyList(),
    val model: String? = null,
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
