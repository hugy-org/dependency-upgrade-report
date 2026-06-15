package hugy.dependencyreport.core.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

class OllamaReportGenerator(
    private val llmConfig: hugy.dependencyreport.core.config.LlmConfig,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : LlmReportGenerator {
    private val objectMapper = StructuredNarrativeSupport.objectMapper

    override fun generate(request: LlmReportRequest): LlmGenerationResult {
        val model = llmConfig.model?.takeIf { it.isNotBlank() }
            ?: return LlmGenerationResult.Failure("Ollama model is not configured")

        logger.info {
            "Starting Ollama enrichment for alias=${request.target.change.alias} using model=$model with ${request.documents.size} document(s)"
        }
        val payload = StructuredNarrativeSupport.buildStructuredPayload(model, request) + mapOf(
            "stream" to false,
            "format" to StructuredNarrativeSupport.buildStructuredSchema(),
        )
        val attempts = llmConfig.retryCount.coerceAtLeast(1)
        var lastFailure: String? = null

        repeat(attempts) { attemptIndex ->
            try {
                val attemptNumber = attemptIndex + 1
                logger.info {
                    "Ollama attempt ${attemptNumber}/${attempts} for alias=${request.target.change.alias}"
                }
                val requestBody = objectMapper.writeValueAsString(payload)
                val httpRequest = HttpRequest.newBuilder(URI.create(llmConfig.baseUrl))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofMillis(llmConfig.requestTimeoutMs.coerceAtLeast(1)))
                    .build()
                val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    lastFailure = "Ollama returned HTTP ${response.statusCode()}"
                    logger.warn {
                        "Ollama returned HTTP ${response.statusCode()} for alias=${request.target.change.alias} on attempt ${attemptNumber}/${attempts}"
                    }
                    return@repeat
                }

                val content = extractAssistantContent(response.body())
                    ?: run {
                        lastFailure = "Ollama response did not contain assistant content"
                        logger.warn {
                            "Ollama response missing assistant content for alias=${request.target.change.alias} on attempt ${attemptNumber}/${attempts}"
                        }
                        return@repeat
                    }
                logger.info { "Ollama enrichment succeeded for alias=${request.target.change.alias}" }
                return LlmGenerationResult.Success(
                    StructuredNarrativeSupport.parseStructuredNarrative(content, request),
                )
            } catch (exception: Exception) {
                lastFailure = "Ollama generation failed: ${exception.message}"
                logger.warn(exception) {
                    "Ollama generation failed for alias=${request.target.change.alias} on attempt ${attemptIndex + 1}/${attempts}"
                }
            }

            if (attemptIndex < attempts - 1 && llmConfig.retryDelayMs > 0) {
                logger.info {
                    "Waiting ${llmConfig.retryDelayMs}ms before retrying Ollama for alias=${request.target.change.alias}"
                }
                try {
                    Thread.sleep(llmConfig.retryDelayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return LlmGenerationResult.Failure(
                        lastFailure ?: "Ollama generation interrupted during retry delay",
                    )
                }
            }
        }

        logger.warn { "Ollama enrichment failed after $attempts attempt(s) for alias=${request.target.change.alias}" }
        return LlmGenerationResult.Failure(lastFailure ?: "Ollama generation failed")
    }

    private fun extractAssistantContent(responseBody: String): String? {
        parseSingleResponseOrNull(responseBody)?.message?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val combined = responseBody.lineSequence()
            .mapNotNull { line -> parseSingleResponseOrNull(line)?.message?.content }
            .joinToString("")
            .trim()
        return combined.takeIf { it.isNotBlank() }
    }

    private fun parseSingleResponseOrNull(content: String): OllamaChatResponse? {
        val trimmed = content.trim()
        if (trimmed.isBlank()) {
            return null
        }
        return runCatching {
            objectMapper.readValue(trimmed, OllamaChatResponse::class.java)
        }.getOrNull()
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class OllamaChatResponse(
    val message: OllamaMessage? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class OllamaMessage(
    val content: String? = null,
)
