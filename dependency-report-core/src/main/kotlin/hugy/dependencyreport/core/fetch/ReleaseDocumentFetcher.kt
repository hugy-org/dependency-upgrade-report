package hugy.dependencyreport.core.fetch

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hugy.dependencyreport.core.config.GitHubConfig
import hugy.dependencyreport.core.model.FetchedDocument
import hugy.dependencyreport.core.model.ReleaseSource
import hugy.dependencyreport.core.model.ReleaseSourceType
import java.net.URLEncoder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.math.min

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
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : ReleaseDocumentFetcher {
    private val objectMapper = jacksonObjectMapper()

    override fun fetch(request: ReleaseFetchRequest): FetchResult {
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
        return try {
            val request = baseRequest(url)
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                FetchResult.Failure("HTTP ${response.statusCode()} from $url")
            } else {
                FetchResult.Success(
                    listOf(
                        FetchedDocument(
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

        return try {
            val perPage = min(100, githubConfig.maxScanReleases.coerceAtLeast(1))
            val selectedDocuments = mutableListOf<FetchedDocument>()
            var scanned = 0
            var page = 1

            while (scanned < githubConfig.maxScanReleases && selectedDocuments.size < githubConfig.maxReleases) {
                val apiUrl = "${githubConfig.apiBaseUrl}/repos/$repository/releases?per_page=$perPage&page=$page"
                val httpRequest = baseRequest(apiUrl)
                val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    return FetchResult.Failure("GitHub API returned HTTP ${response.statusCode()} for $apiUrl")
                }
                val nodes = objectMapper.readTree(response.body())
                if (!nodes.isArray || nodes.isEmpty) {
                    break
                }

                for (node in nodes) {
                    if (scanned >= githubConfig.maxScanReleases || selectedDocuments.size >= githubConfig.maxReleases) {
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
                        selectedDocuments += FetchedDocument(
                            title = title,
                            sourceUrl = htmlUrl,
                            content = body,
                            version = matchedVersion.normalized,
                        )
                    }
                }

                if (nodes.size() < perPage) {
                    break
                }
                page += 1
            }

            if (selectedDocuments.isEmpty()) {
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
                        selectedDocuments += FetchedDocument(
                            title = title,
                            sourceUrl = htmlUrl,
                            content = body,
                            version = currentVersion.normalized,
                        )
                    }
                }
            }

            if (selectedDocuments.isEmpty()) {
                FetchResult.Failure(
                    "GitHub Releases returned no release notes in the upgrade window ${request.target.change.previousVersion} -> ${request.target.change.currentVersion}",
                )
            } else {
                FetchResult.Success(selectedDocuments.sortedBy { VersionSelection.parse(it.version ?: "") })
            }
        } catch (exception: Exception) {
            FetchResult.Failure("Failed to fetch GitHub releases: ${exception.message}")
        }
    }

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
