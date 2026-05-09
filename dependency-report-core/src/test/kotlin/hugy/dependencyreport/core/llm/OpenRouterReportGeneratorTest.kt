package hugy.dependencyreport.core.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import hugy.dependencyreport.core.config.LlmConfig
import hugy.dependencyreport.core.model.FetchedDocument
import hugy.dependencyreport.core.model.UpgradeKind
import hugy.dependencyreport.core.model.UpgradeTarget
import hugy.dependencyreport.core.model.VersionAliasChange
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenRouterReportGeneratorTest {
    @Test
    fun `parses structured narrative from openrouter response`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val handler = CapturingHandler()
        server.createContext("/api/v1/chat/completions", handler)
        server.start()
        try {
            val generator = OpenRouterReportGenerator(
                llmConfig = LlmConfig(
                    mode = "openrouter",
                    model = "openai/gpt-4.1-mini",
                    apiKey = "test-key",
                    baseUrl = "http://127.0.0.1:${server.address.port}/api/v1/chat/completions",
                ),
            )

            val result = generator.generate(
                LlmReportRequest(
                    target = UpgradeTarget(
                        change = VersionAliasChange("versionsPlugin", "0.50.0", "0.51.0"),
                        usages = emptyList(),
                        kind = UpgradeKind.GRADLE_PLUGIN,
                    ),
                    documents = listOf(
                        FetchedDocument(
                            title = "v0.51.0",
                            sourceUrl = "https://github.com/ben-manes/gradle-versions-plugin/releases/tag/v0.51.0",
                            content = "Adds compatibility fixes.",
                            version = "0.51.0",
                        ),
                    ),
                ),
            )

            val success = assertIs<LlmGenerationResult.Success>(result)
            assertEquals("versionsPlugin: 0.50.0 -> 0.51.0", success.narrative.headline)
            assertEquals("Short summary", success.narrative.summary)
            assertEquals("MR summary", success.narrative.description)
            assertEquals("Bearer test-key", handler.authorizationHeader)
            assertTrue(handler.requestBody.contains("\"response_format\""))
            assertTrue(handler.requestBody.contains("\"model\":\"openai/gpt-4.1-mini\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `repairs json with raw newlines inside string values`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val handler = CapturingHandler(
            responseContent = """
                {
                  "headline":"kotlin: 1.9.24 -> 2.0.0",
                  "summary":"First line
Second line",
                  "description":"MR line 1
MR line 2",
                  "riskLevel":"HIGH",
                  "riskSummary":"Potentially broad compiler and tooling impact.",
                  "riskSignals":["compiler-upgrade","tooling-change"]
                }
            """.trimIndent(),
        )
        server.createContext("/api/v1/chat/completions", handler)
        server.start()
        try {
            val generator = OpenRouterReportGenerator(
                llmConfig = LlmConfig(
                    mode = "openrouter",
                    model = "openai/gpt-4.1-mini",
                    apiKey = "test-key",
                    baseUrl = "http://127.0.0.1:${server.address.port}/api/v1/chat/completions",
                ),
            )

            val result = generator.generate(
                LlmReportRequest(
                    target = UpgradeTarget(
                        change = VersionAliasChange("kotlin", "1.9.24", "2.0.0"),
                        usages = emptyList(),
                        kind = UpgradeKind.MIXED,
                    ),
                    documents = listOf(
                        FetchedDocument(
                            title = "Kotlin 2.0.0",
                            sourceUrl = "https://github.com/JetBrains/kotlin/releases/tag/v2.0.0",
                            content = "Large compiler upgrade",
                            version = "2.0.0",
                        ),
                    ),
                ),
            )

            val success = assertIs<LlmGenerationResult.Success>(result)
            assertTrue(success.narrative.summary.contains("Second line"))
            assertTrue(success.narrative.description.contains("MR line 2"))
            assertEquals(hugy.dependencyreport.core.model.RiskLevel.HIGH, success.narrative.riskAssessment.level)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `retries when first response has no assistant content`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val handler = SequencedHandler(
            responses = listOf(
                """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant"
                          }
                        }
                      ]
                    }
                """.trimIndent(),
                """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "{\"headline\":\"versionsPlugin: 0.50.0 -> 0.51.0\",\"summary\":\"Short summary\",\"description\":\"MR summary\",\"riskLevel\":\"LOW\",\"riskSummary\":\"Low risk\",\"riskSignals\":[\"plugin-upgrade\"]}"
                          }
                        }
                      ]
                    }
                """.trimIndent(),
            ),
        )
        server.createContext("/api/v1/chat/completions", handler)
        server.start()
        try {
            val generator = OpenRouterReportGenerator(
                llmConfig = LlmConfig(
                    mode = "openrouter",
                    model = "openai/gpt-4.1-mini",
                    apiKey = "test-key",
                    baseUrl = "http://127.0.0.1:${server.address.port}/api/v1/chat/completions",
                    retryCount = 3,
                    retryDelayMs = 1,
                ),
            )

            val result = generator.generate(
                LlmReportRequest(
                    target = UpgradeTarget(
                        change = VersionAliasChange("versionsPlugin", "0.50.0", "0.51.0"),
                        usages = emptyList(),
                        kind = UpgradeKind.GRADLE_PLUGIN,
                    ),
                    documents = listOf(
                        FetchedDocument(
                            title = "v0.51.0",
                            sourceUrl = "https://github.com/ben-manes/gradle-versions-plugin/releases/tag/v0.51.0",
                            content = "Adds compatibility fixes.",
                            version = "0.51.0",
                        ),
                    ),
                ),
            )

            assertIs<LlmGenerationResult.Success>(result)
            assertEquals(2, handler.requestCount.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `accepts partial structured narrative and fills missing fields deterministically`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val handler = CapturingHandler(
            responseContent = """
                {
                  "description": "Kotlin 2.0.0 introduces K2 compiler changes and needs build validation.",
                  "riskLevel": "HIGH"
                }
            """.trimIndent(),
        )
        server.createContext("/api/v1/chat/completions", handler)
        server.start()
        try {
            val generator = OpenRouterReportGenerator(
                llmConfig = LlmConfig(
                    mode = "openrouter",
                    model = "openai/gpt-4.1-mini",
                    apiKey = "test-key",
                    baseUrl = "http://127.0.0.1:${server.address.port}/api/v1/chat/completions",
                ),
            )

            val result = generator.generate(
                LlmReportRequest(
                    target = UpgradeTarget(
                        change = VersionAliasChange("kotlin", "1.9.24", "2.0.0"),
                        usages = emptyList(),
                        kind = UpgradeKind.MIXED,
                    ),
                    documents = listOf(
                        FetchedDocument(
                            title = "Kotlin 2.0.0",
                            sourceUrl = "https://github.com/JetBrains/kotlin/releases/tag/v2.0.0",
                            content = "K2 compiler becomes stable in Kotlin 2.0.0.",
                            version = "2.0.0",
                        ),
                    ),
                ),
            )

            val success = assertIs<LlmGenerationResult.Success>(result)
            assertEquals("kotlin: 1.9.24 -> 2.0.0", success.narrative.headline)
            assertTrue(success.narrative.summary.isNotBlank())
            assertEquals("Kotlin 2.0.0 introduces K2 compiler changes and needs build validation.", success.narrative.description)
            assertEquals(hugy.dependencyreport.core.model.RiskLevel.HIGH, success.narrative.riskAssessment.level)
        } finally {
            server.stop(0)
        }
    }
}

private class CapturingHandler(
    private val responseContent: String = "{\"headline\":\"versionsPlugin: 0.50.0 -> 0.51.0\",\"summary\":\"Short summary\",\"description\":\"MR summary\",\"riskLevel\":\"MEDIUM\",\"riskSummary\":\"Moderate risk\",\"riskSignals\":[\"plugin-upgrade\"]}",
) : HttpHandler {
    var authorizationHeader: String? = null
    var requestBody: String = ""

    override fun handle(exchange: HttpExchange) {
        authorizationHeader = exchange.requestHeaders.getFirst("Authorization")
        requestBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
        val response = """
            {
              "choices": [
                {
                  "message": {
                    "content": ${jacksonObjectMapper().writeValueAsString(responseContent)}
                  }
                }
              ]
            }
        """.trimIndent()
        val bytes = response.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

private class SequencedHandler(
    private val responses: List<String>,
) : HttpHandler {
    val requestCount = AtomicInteger(0)

    override fun handle(exchange: HttpExchange) {
        val index = requestCount.getAndIncrement().coerceAtMost(responses.lastIndex)
        val bytes = responses[index].toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
