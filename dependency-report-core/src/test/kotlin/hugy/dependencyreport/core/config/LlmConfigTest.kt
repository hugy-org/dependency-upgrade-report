package hugy.dependencyreport.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LlmConfigTest {
    @Test
    fun `resolveApiKey prefers explicit apiKey over environment variable`() {
        val config = LlmConfig(
            apiKey = "literal-key",
            apiKeyEnv = "OPENROUTER_API_KEY",
        )

        assertEquals(
            "literal-key",
            config.resolveApiKey(mapOf("OPENROUTER_API_KEY" to "env-key")),
        )
    }

    @Test
    fun `resolveApiKey reads from configured environment variable`() {
        val config = LlmConfig(
            apiKeyEnv = "OPENROUTER_API_KEY",
        )

        assertEquals(
            "env-key",
            config.resolveApiKey(mapOf("OPENROUTER_API_KEY" to "env-key")),
        )
    }

    @Test
    fun `resolveApiKey returns null when no key source is available`() {
        val config = LlmConfig(
            apiKeyEnv = "OPENROUTER_API_KEY",
        )

        assertNull(config.resolveApiKey(emptyMap()))
    }

    @Test
    fun `ollama mode requires non-blank model`() {
        assertFailsWith<IllegalArgumentException> {
            LlmConfig(mode = LLMMode.OLLAMA, model = " ")
        }
    }
}
