package hugy.dependencyreport.core.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import hugy.dependencyreport.core.config.LLMMode
import hugy.dependencyreport.core.config.LlmConfig
import hugy.dependencyreport.core.model.FetchedDocument
import hugy.dependencyreport.core.model.RiskLevel
import hugy.dependencyreport.core.model.UpgradeKind
import hugy.dependencyreport.core.model.UpgradeTarget
import hugy.dependencyreport.core.model.VersionAliasChange
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OllamaReportGeneratorTest {
    @Test
    fun `parses structured narrative from ollama response`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val handler = OllamaCapturingHandler()
        server.createContext("/api/chat", handler)
        server.start()
        try {
            val generator = OllamaReportGenerator(
                llmConfig = LlmConfig(
                    mode = LLMMode.OLLAMA,
                    model = "qwen3-coder:30b",
                    baseUrl = "http://127.0.0.1:${server.address.port}/api/chat",
                ),
            )

            val result = generator.generate(request())

            val success = assertIs<LlmGenerationResult.Success>(result)
            assertEquals("versionsPlugin: 0.50.0 -> 0.51.0", success.narrative.headline)
            assertEquals("Short summary", success.narrative.summary)
            assertEquals("MR summary", success.narrative.description)
            assertEquals(RiskLevel.MEDIUM, success.narrative.riskAssessment.level)
            assertTrue(handler.requestBody.contains("\"stream\":false"))
            assertTrue(handler.requestBody.contains("\"model\":\"qwen3-coder:30b\""))
            assertTrue(handler.requestBody.contains("\"format\":{\"type\":\"object\""))
            assertTrue(handler.requestBody.contains("\"additionalProperties\":false"))
            assertTrue(!handler.requestBody.contains("\"json_schema\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `supports line-delimited ollama response chunks`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val handler = OllamaCapturingHandler(
            rawResponse = """
                {"message":{"content":"{\"headline\":\"versionsPlugin: 0.50.0 -> 0.51.0\","}}
                {"message":{"content":"\"summary\":\"Short summary\",\"description\":\"MR summary\",\"riskLevel\":\"LOW\",\"riskSummary\":\"Low risk\",\"riskSignals\":[\"plugin-upgrade\"]}"}}
            """.trimIndent(),
        )
        server.createContext("/api/chat", handler)
        server.start()
        try {
            val generator = OllamaReportGenerator(
                llmConfig = LlmConfig(
                    mode = LLMMode.OLLAMA,
                    model = "qwen3-coder:30b",
                    baseUrl = "http://127.0.0.1:${server.address.port}/api/chat",
                ),
            )

            val result = generator.generate(request())

            val success = assertIs<LlmGenerationResult.Success>(result)
            assertEquals(RiskLevel.LOW, success.narrative.riskAssessment.level)
            assertEquals("Short summary", success.narrative.summary)
        } finally {
            server.stop(0)
        }
    }

    private fun request(): LlmReportRequest {
        return LlmReportRequest(
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
        )
    }
}

private class OllamaCapturingHandler(
    private val rawResponse: String = """
        {
          "message": {
            "content": "{\"headline\":\"versionsPlugin: 0.50.0 -> 0.51.0\",\"summary\":\"Short summary\",\"description\":\"MR summary\",\"riskLevel\":\"MEDIUM\",\"riskSummary\":\"Moderate risk\",\"riskSignals\":[\"plugin-upgrade\"]}"
          }
        }
    """.trimIndent(),
) : HttpHandler {
    var requestBody: String = ""

    override fun handle(exchange: HttpExchange) {
        requestBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
        val bytes = rawResponse.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
