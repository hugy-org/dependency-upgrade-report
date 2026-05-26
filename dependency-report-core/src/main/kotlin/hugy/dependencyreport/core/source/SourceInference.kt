package hugy.dependencyreport.core.source

import hugy.dependencyreport.core.model.ReleaseSource
import hugy.dependencyreport.core.model.ReleaseSourceResolution
import hugy.dependencyreport.core.model.ReleaseSourceType
import hugy.dependencyreport.core.model.UpgradeTarget
import hugy.dependencyreport.core.model.UsageType
import io.github.oshai.kotlinlogging.KotlinLogging
import org.w3c.dom.Document
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

internal object SourceInference {

    private val logger = KotlinLogging.logger {  }

    fun inferFromMavenPom(
        target: UpgradeTarget,
        lookup: SourceMetadataLookup,
        githubApiBaseUrl: String,
        githubMaxReleases: Int,
    ): ReleaseSourceResolution? {
        val provenance = mutableListOf<String>()
        val usages = target.usages
            .filter { it.usageType == UsageType.LIBRARY || it.usageType == UsageType.BOM }
            .sortedBy { it.identifier }

        for (usage in usages) {
            when (val result = lookup.fetchMavenPom(usage.identifier, target.change.currentVersion)) {
                is MetadataLookupResult.Failure -> provenance += "Maven POM lookup failed for ${usage.identifier}:${target.change.currentVersion} at ${result.url}: ${result.reason}"
                is MetadataLookupResult.Success -> {
                    logger.info { "Fetched Maven POM for ${usage.identifier}:${target.change.currentVersion} from ${result.url}" }
                    provenance += "Fetched Maven POM for ${usage.identifier}:${target.change.currentVersion} from ${result.url}"
                    val metadata = parsePomMetadata(result.content)
                    val urls = listOfNotNull(metadata.projectUrl, metadata.scmUrl, metadata.scmConnection, metadata.scmDeveloperConnection)
                    val githubRepo = urls.firstNotNullOfOrNull(GitHubUrlParser::extractRepository)
                    if (githubRepo != null) {
                        provenance += "Derived GitHub repository $githubRepo from Maven POM metadata"
                        return ReleaseSourceResolution(
                            targetAlias = target.change.alias,
                            source = ReleaseSource(
                                type = ReleaseSourceType.GITHUB_RELEASES,
                                displayName = githubRepo,
                                sourceUrl = "https://github.com/$githubRepo/releases",
                                apiUrl = "$githubApiBaseUrl/repos/$githubRepo/releases?per_page=$githubMaxReleases",
                                repository = githubRepo,
                            ),
                            matchedBy = "inferredMavenPom",
                            provenance = provenance,
                        )
                    }

                    val changelogUrl = urls.firstOrNull(::looksLikeChangelogUrl)
                    if (changelogUrl != null) {
                        provenance += "Derived changelog URL $changelogUrl from Maven POM metadata"
                        return ReleaseSourceResolution(
                            targetAlias = target.change.alias,
                            source = ReleaseSource(
                                type = ReleaseSourceType.EXPLICIT_CHANGELOG_URL,
                                displayName = changelogUrl,
                                sourceUrl = changelogUrl,
                            ),
                            matchedBy = "inferredMavenPom",
                            provenance = provenance,
                        )
                    }

                    provenance += "Maven POM for ${usage.identifier} did not expose a trusted GitHub or changelog URL"
                }
            }
        }

        return null
    }

    private fun parsePomMetadata(xml: String): PomMetadata {
        return try {
            val builderFactory = DocumentBuilderFactory.newInstance()
            builderFactory.isNamespaceAware = false
            builderFactory.isXIncludeAware = false
            builderFactory.isExpandEntityReferences = false
            builderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            builderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            builderFactory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            builderFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            builderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            builderFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            builderFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            val builder = builderFactory.newDocumentBuilder()
            val document = builder.parse(InputSource(StringReader(xml)))
            document.documentElement.normalize()
            PomMetadata(
                projectUrl = firstTagText(document, "url"),
                scmUrl = nestedTagText(document, "scm", "url"),
                scmConnection = nestedTagText(document, "scm", "connection"),
                scmDeveloperConnection = nestedTagText(document, "scm", "developerConnection"),
            )
        } catch (_: Exception) {
            PomMetadata()
        }
    }

    private fun firstTagText(document: Document, tagName: String): String? {
        val nodes = document.getElementsByTagName(tagName)
        return if (nodes.length > 0) nodes.item(0).textContent?.trim()?.takeIf { it.isNotBlank() } else null
    }

    private fun nestedTagText(document: Document, parentTag: String, childTag: String): String? {
        val parents = document.getElementsByTagName(parentTag)
        if (parents.length == 0) return null
        val parent = parents.item(0) as? org.w3c.dom.Element ?: return null
        val children = parent.getElementsByTagName(childTag)
        return if (children.length > 0) children.item(0).textContent?.trim()?.takeIf { it.isNotBlank() } else null
    }

    private fun looksLikeChangelogUrl(url: String): Boolean {
        val normalized = url.lowercase()
        return listOf("changelog", "release", "releases", "whats-new", "what-s-new").any { it in normalized }
    }

    private data class PomMetadata(
        val projectUrl: String? = null,
        val scmUrl: String? = null,
        val scmConnection: String? = null,
        val scmDeveloperConnection: String? = null,
    )
}

internal object GitHubUrlParser {
    fun extractRepository(rawUrl: String): String? {
        val normalized = rawUrl
            .removePrefix("scm:git:")
            .removePrefix("scm:")
            .removePrefix("git:")
            .trim()

        val repo = when {
            normalized.startsWith("git@github.com:") -> normalized.removePrefix("git@github.com:")
            normalized.startsWith("ssh://git@github.com/") -> normalized.removePrefix("ssh://git@github.com/")
            normalized.startsWith("https://github.com/") -> normalized.removePrefix("https://github.com/")
            normalized.startsWith("http://github.com/") -> normalized.removePrefix("http://github.com/")
            normalized.startsWith("git://github.com/") -> normalized.removePrefix("git://github.com/")
            else -> return null
        }

        val cleaned = repo
            .removeSuffix(".git")
            .substringBefore("/tree/")
            .substringBefore("/releases")
            .substringBefore("/issues")
            .trim('/')

        val parts = cleaned.split('/')
        return if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            "${parts[0]}/${parts[1]}"
        } else {
            null
        }
    }
}
