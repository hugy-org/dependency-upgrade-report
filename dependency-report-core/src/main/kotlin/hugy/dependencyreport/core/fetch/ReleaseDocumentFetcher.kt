package hugy.dependencyreport.core.fetch

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hugy.dependencyreport.core.config.FetchConfig
import hugy.dependencyreport.core.config.GitHubConfig
import hugy.dependencyreport.core.model.FetchedDocument
import hugy.dependencyreport.core.model.ReleaseSource
import hugy.dependencyreport.core.model.ReleaseSourceType
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URLEncoder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.math.min

private val logger = KotlinLogging.logger {}

sealed interface FetchResult {
    data class Success(val documents: List<FetchedDocument>) : FetchResult
    data class Failure(val reason: String) : FetchResult
}

interface ReleaseDocumentFetcher {
    fun fetch(request: ReleaseFetchRequest): FetchResult
}

class NoopReleaseDocumentFetcher : ReleaseDocumentFetcher {
    override fun fetch(request: ReleaseFetchRequest): FetchResult = FetchResult.Failure("Fetching is disabled")
}

class HttpReleaseDocumentFetcher(
    private val githubConfig: GitHubConfig,
    fetchConfig: FetchConfig = FetchConfig(),
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : ReleaseDocumentFetcher {
    private val objectMapper = jacksonObjectMapper()
    private val contentLimiter = DocumentContentLimiter(fetchConfig.maxDocumentContentChars)

    override fun fetch(request: ReleaseFetchRequest): FetchResult {
        logger.info {
            "Fetching release documents for alias=${request.target.change.alias}, sourceType=${request.source.type}, displayName=${request.source.displayName}"
        }
        return when (request.source.type) {
            ReleaseSourceType.REGISTRY,
            ReleaseSourceType.EXPLICIT_CHANGELOG_URL,
                -> fetchUrlDocument(request.source)

            ReleaseSourceType.GITHUB_RELEASES -> fetchGitHubReleaseNotes(request)
            ReleaseSourceType.UNRESOLVED -> FetchResult.Failure("No release source was resolved")
        }
    }

    private fun fetchUrlDocument(source: ReleaseSource): FetchResult {
        val url = source.sourceUrl ?: return FetchResult.Failure("Missing source URL")
        logger.info { "Fetching URL-based changelog from $url" }
        return try {
            val request = baseRequest(url)
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                logger.warn { "URL fetch failed with HTTP ${response.statusCode()} for $url" }
                FetchResult.Failure("HTTP ${response.statusCode()} from $url")
            } else {
                logger.info { "Fetched URL-based changelog successfully from $url" }
                FetchResult.Success(
                    listOf(
                        contentLimiter.limit(
                            title = source.displayName,
                            sourceUrl = url,
                            content = response.body(),
                        ),
                    ),
                )
            }
        } catch (exception: Exception) {
            FetchResult.Failure("Failed to fetch $url: ${exception.message}")
        }
    }

    private fun fetchGitHubReleaseNotes(request: ReleaseFetchRequest): FetchResult {
        val source = request.source
        val repository = source.repository ?: return FetchResult.Failure("Missing GitHub repository")
        val previousVersion = VersionSelection.parse(request.target.change.previousVersion)
            ?: return FetchResult.Failure("Unsupported previous version format: ${request.target.change.previousVersion}")
        val currentVersion = VersionSelection.parse(request.target.change.currentVersion)
            ?: return FetchResult.Failure("Unsupported current version format: ${request.target.change.currentVersion}")
        if (currentVersion <= previousVersion) {
            return FetchResult.Failure("Current version must be greater than previous version for GitHub release selection")
        }
        logger.info {
            "Fetching GitHub releases for ${repository} in window ${request.target.change.previousVersion} -> ${request.target.change.currentVersion}"
        }

        return try {
            val perPage = min(100, githubConfig.pageSize.coerceAtLeast(1))
            val rawDocuments = mutableListOf<VersionedValue<RawFetchedDocument>>()
            var scanned = 0
            var page = 1

            while (scanned < githubConfig.maxScanReleases) {
                val apiUrl = "${githubConfig.apiBaseUrl}/repos/$repository/releases?per_page=$perPage&page=$page"
                val httpRequest = baseRequest(apiUrl)
                val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    logger.warn { "GitHub releases request failed with HTTP ${response.statusCode()} for $apiUrl" }
                    return FetchResult.Failure("GitHub API returned HTTP ${response.statusCode()} for $apiUrl")
                }
                val nodes = objectMapper.readTree(response.body())
                if (!nodes.isArray || nodes.isEmpty) {
                    break
                }

                for (node in nodes) {
                    if (scanned >= githubConfig.maxScanReleases) {
                        break
                    }
                    scanned += 1

                    val body = node.path("body").asText("").trim()
                    val htmlUrl = node.path("html_url").asText("").trim()
                    val title = node.path("name").asText(node.path("tag_name").asText("release"))
                    val tagName = node.path("tag_name").asText("")
                    val candidateVersions = VersionSelection.extractCandidates(tagName, title, htmlUrl)
                    val matchedVersion = candidateVersions.firstOrNull {
                        VersionSelection.isWithinUpgradeWindow(it, previousVersion, currentVersion)
                    }

                    if (matchedVersion != null && body.isNotBlank() && htmlUrl.isNotBlank()) {
                        rawDocuments += VersionedValue(
                            version = matchedVersion,
                            value = RawFetchedDocument(
                                title = title,
                                sourceUrl = htmlUrl,
                                content = body,
                            ),
                        )
                    }
                }

                if (
                    VersionSelection.hasEnoughUsefulReleaseCandidates(
                        candidates = rawDocuments,
                        targetVersion = currentVersion,
                        maxReleases = githubConfig.maxReleases,
                        includePrereleases = githubConfig.includePrereleases,
                    )
                ) {
                    logger.info {
                        "Stopping GitHub release scan for $repository after $scanned scanned release(s); found target version and enough useful candidates"
                    }
                    break
                }

                if (nodes.size() < perPage) {
                    break
                }
                page += 1
            }

            if (rawDocuments.none { it.version.normalized == currentVersion.normalized }) {
                val encodedVersion = URLEncoder.encode(request.target.change.currentVersion, Charsets.UTF_8)
                val tagApiUrl = "${githubConfig.apiBaseUrl}/repos/$repository/releases/tags/$encodedVersion"
                val httpRequest = baseRequest(tagApiUrl)
                val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in 200..299) {
                    val node = objectMapper.readTree(response.body())
                    val body = node.path("body").asText("").trim()
                    val htmlUrl = node.path("html_url").asText("").trim()
                    val title = node.path("name").asText(node.path("tag_name").asText("release"))
                    if (body.isNotBlank() && htmlUrl.isNotBlank()) {
                        rawDocuments += VersionedValue(
                            version = currentVersion,
                            value = RawFetchedDocument(
                                title = title,
                                sourceUrl = htmlUrl,
                                content = body,
                            ),
                        )
                    }
                }
            }

            val selectedDocuments = VersionSelection.selectUpgradeWindowDocuments(
                candidates = rawDocuments.distinctBy { it.version.normalized to it.value.sourceUrl },
                targetVersion = currentVersion,
                maxReleases = githubConfig.maxReleases,
                includePrereleases = githubConfig.includePrereleases,
            ).map {
                contentLimiter.limit(
                    title = it.value.title,
                    sourceUrl = it.value.sourceUrl,
                    content = it.value.content,
                    version = it.version.normalized,
                )
            }
            logger.info {
                "Selected ${selectedDocuments.size} GitHub release document(s) for ${repository}: ${
                    selectedDocuments.joinToString { it.version ?: it.title }
                }"
            }

            if (selectedDocuments.isEmpty()) {
                logger.warn {
                    "No GitHub release notes found in window ${request.target.change.previousVersion} -> ${request.target.change.currentVersion} for ${repository}"
                }
                FetchResult.Failure(
                    "GitHub Releases returned no release notes in the upgrade window ${request.target.change.previousVersion} -> ${request.target.change.currentVersion}",
                )
            } else {
                FetchResult.Success(selectedDocuments.sortedBy { VersionSelection.parse(it.version ?: "") })
            }
        } catch (exception: Exception) {
            logger.warn(exception) { "GitHub release fetching failed for ${repository}" }
            FetchResult.Failure("Failed to fetch GitHub releases: ${exception.message}")
        }
    }

    private data class RawFetchedDocument(
        val title: String,
        val sourceUrl: String,
        val content: String,
    )

    private fun baseRequest(url: String): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .header("Accept", "application/vnd.github+json, text/plain, text/html")
            .header("User-Agent", "dependency-upgrade-report")
        githubConfig.token?.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }
        return builder.build()
    }
}
