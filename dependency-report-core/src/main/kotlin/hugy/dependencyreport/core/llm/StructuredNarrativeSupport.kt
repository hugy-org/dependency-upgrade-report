package hugy.dependencyreport.core.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import hugy.dependencyreport.core.fetch.VersionSelection
import hugy.dependencyreport.core.model.GeneratedNarrative
import hugy.dependencyreport.core.model.RiskAssessment
import hugy.dependencyreport.core.model.RiskLevel
import hugy.dependencyreport.core.model.UpgradeKind
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.kotlinModule

internal object StructuredNarrativeSupport {
    val objectMapper: ObjectMapper = jacksonObjectMapper()

    private val lenientObjectMapper: ObjectMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
        .build()

    fun buildStructuredPayload(request: LlmReportRequest): Map<String, Any> {
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
        val versionChangeScope = describeVersionChangeScope(
            previousVersion = target.change.previousVersion,
            currentVersion = target.change.currentVersion,
        )
        val systemPrompt = """
            You generate deterministic dependency-upgrade report content.
            Use only the provided release-note documents and upgrade metadata.
            Return concise, concrete output as strict JSON matching the schema.
            Do not recommend whether to merge or apply the upgrade.
            Produce one useful human-readable description that can be reused for both Jira and merge request text.
            The headline must be short.
            The summary must add detail and must not repeat the headline verbatim.
            The description must be suitable for MR and Jira text and must not repeat the headline verbatim.
            Risk must be evidence-based from the provided release-note documents.
            Do not claim breaking changes, incompatibilities, regressions, removals, or required migrations unless they are explicitly supported by the provided release-note documents.
            Do not invent risk signals.
            Patch-only upgrades should rarely be HIGH risk. Use HIGH only when the provided release-note documents explicitly describe breaking changes, removals, required migration, or severe regressions.
            If evidence is limited or ambiguous, prefer MEDIUM over HIGH.
        """.trimIndent()
        val userPrompt = """
            Upgrade alias: ${target.change.alias}
            Previous version: ${target.change.previousVersion}
            Current version: ${target.change.currentVersion}
            Version change scope: $versionChangeScope
            Upgrade kind: ${target.kind}
            Usages: $usages

            Release note documents:
            $documentsText
        """.trimIndent()

        return mapOf(
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userPrompt),
            ),
            "temperature" to 0,
        )
    }

    fun buildStructuredResponseFormat(): Map<String, Any> {
        return mapOf(
            "type" to "json_schema",
            "json_schema" to mapOf(
                "name" to "dependency_upgrade_report",
                "strict" to true,
                "schema" to buildStructuredSchema(),
            ),
        )
    }

    fun buildStructuredSchema(): Map<String, Any> {
        return structuredNarrativeSchema()
    }

    fun parseStructuredNarrative(content: String, request: LlmReportRequest): GeneratedNarrative {
        val structured = parseStructuredNarrativeObject(content, request)
        return GeneratedNarrative(
            headline = structured.headline,
            summary = structured.summary,
            description = structured.description,
            riskAssessment = RiskAssessment(
                level = parseRiskLevel(structured.riskLevel),
                summary = structured.riskSummary,
                signals = structured.riskSignals,
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
                    "description" to "Risk level based only on explicit evidence from the provided release-note documents and upgrade metadata. Prefer MEDIUM over HIGH when evidence is limited.",
                    "enum" to listOf("LOW", "MEDIUM", "HIGH", "UNKNOWN"),
                ),
                "riskSummary" to mapOf(
                    "type" to "string",
                    "description" to "One concise sentence justifying the risk level using only explicit evidence from the provided release-note documents. Do not mention breaking changes unless the documents explicitly describe them.",
                ),
                "riskSignals" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string"),
                    "description" to "Short machine-readable risk signals directly supported by the provided release-note documents, such as breaking-change, removed-api, migration-required, concurrency-change, build-tooling. Do not invent unsupported signals.",
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

    private fun describeVersionChangeScope(
        previousVersion: String,
        currentVersion: String,
    ): String {
        val previous = VersionSelection.parse(previousVersion)
        val current = VersionSelection.parse(currentVersion)
        if (previous == null || current == null) {
            return "unknown"
        }

        val previousMajor = previous.coreParts.getOrElse(0) { 0 }
        val currentMajor = current.coreParts.getOrElse(0) { 0 }
        if (previousMajor != currentMajor) {
            return "major"
        }

        val previousMinor = previous.coreParts.getOrElse(1) { 0 }
        val currentMinor = current.coreParts.getOrElse(1) { 0 }
        if (previousMinor != currentMinor) {
            return "minor"
        }

        val previousPatch = previous.coreParts.getOrElse(2) { 0 }
        val currentPatch = current.coreParts.getOrElse(2) { 0 }
        if (previousPatch != currentPatch) {
            return "patch"
        }

        return "same-series"
    }

    private fun parseRiskLevel(value: String): RiskLevel {
        return RiskLevel.entries.firstOrNull { it.name == value } ?: RiskLevel.UNKNOWN
    }

    private fun parseStructuredNarrativeObject(content: String, request: LlmReportRequest): StructuredNarrative {
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

        throw lastError ?: IllegalArgumentException("Could not parse structured narrative from LLM response")
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
internal data class StructuredNarrative(
    val headline: String,
    val summary: String,
    val description: String,
    val riskLevel: String,
    val riskSummary: String,
    val riskSignals: List<String>,
)
