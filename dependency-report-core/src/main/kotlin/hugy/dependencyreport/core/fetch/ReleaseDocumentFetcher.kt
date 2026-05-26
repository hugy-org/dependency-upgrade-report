package hugy.dependencyreport.core.fetch

import tools.jackson.module.kotlin.jacksonObjectMapper
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
    private val contextSelector = DocumentContextSelector(fetchConfig.maxDocumentContentChars)
    private val maxRedirects = 5
    private val githubApiBasePrefix = githubConfig.apiBaseUrl.trimEnd('/')

    override fun fetch(request: ReleaseFetchRequest): FetchResult {
        logger.info {
            "Fetching release documents for alias=${request.target.change.alias}, sourceType=${request.source.type}, displayName=${request.source.displayName}"
        }
        return when (request.source.type) {
            ReleaseSourceType.REGISTRY,
            ReleaseSourceType.EXPLICIT_CHANGELOG_URL,
                -> fetchUrlDocument(request)

            ReleaseSourceType.GITHUB_RELEASES -> fetchGitHubReleaseNotes(request)
            ReleaseSourceType.UNRESOLVED -> FetchResult.Failure("No release source was resolved")
        }
    }

    private fun fetchUrlDocument(request: ReleaseFetchRequest): FetchResult {
        val source = request.source
        val url = source.sourceUrl ?: return FetchResult.Failure("Missing source URL")
        val fetchUrl = normalizeExplicitFetchUrl(url)
        logger.info { "Fetching URL-based changelog from $fetchUrl" }
        return try {
            val response = sendTextRequest(fetchUrl)
            if (response.statusCode() !in 200..299) {
                logger.warn { "URL fetch failed with HTTP ${response.statusCode()} for $fetchUrl" }
                FetchResult.Failure("HTTP ${response.statusCode()} from $fetchUrl")
            } else {
                logger.info { "Fetched URL-based changelog successfully from $fetchUrl" }
                FetchResult.Success(
                    listOf(
                        buildFetchedDocument(
                            request = request,
                            title = source.displayName,
                            sourceUrl = url,
                            content = response.body(),
                            applyContextSelection = true,
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
            "Fetching GitHub releases for $repository in window ${request.target.change.previousVersion} -> ${request.target.change.currentVersion}"
        }

        return try {
            val perPage = min(100, githubConfig.pageSize.coerceAtLeast(1))
            val rawDocuments = mutableListOf<VersionedValue<RawFetchedDocument>>()
            var scanned = 0
            var page = 1

            while (scanned < githubConfig.maxScanReleases) {
                val apiUrl = "${githubConfig.apiBaseUrl}/repos/$repository/releases?per_page=$perPage&page=$page"
                val response = sendTextRequest(apiUrl)
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

                    val body = node.path("body").asString("").trim()
                    val htmlUrl = node.path("html_url").asString("").trim()
                    val title = node.path("name").asString(node.path("tag_name").asString("release"))
                    val tagName = node.path("tag_name").asString("")
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
                val response = sendTextRequest(tagApiUrl)
                if (response.statusCode() in 200..299) {
                    val node = objectMapper.readTree(response.body())
                    val body = node.path("body").asString("").trim()
                    val htmlUrl = node.path("html_url").asString("").trim()
                    val title = node.path("name").asString(node.path("tag_name").asString("release"))
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

            logger.debug {
                val candidateVersions = rawDocuments
                    .distinctBy { it.version.normalized to it.value.sourceUrl }
                    .map { it.version.normalized }
                "Collected ${candidateVersions.size} GitHub release candidate(s) for ${repository}: $candidateVersions"
            }

            val selectedDocuments = VersionSelection.selectUpgradeWindowDocuments(
                candidates = rawDocuments.distinctBy { it.version.normalized to it.value.sourceUrl },
                targetVersion = currentVersion,
                maxReleases = githubConfig.maxReleases,
                includePrereleases = githubConfig.includePrereleases,
            ).map {
                buildFetchedDocument(
                    request = request,
                    title = it.value.title,
                    sourceUrl = it.value.sourceUrl,
                    content = it.value.content,
                    version = it.version.normalized,
                    applyContextSelection = false,
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

    private fun buildFetchedDocument(
        request: ReleaseFetchRequest,
        title: String,
        sourceUrl: String,
        content: String,
        version: String? = null,
        applyContextSelection: Boolean,
    ): FetchedDocument {
        val normalizedContent = content.trim()
        val selection = if (applyContextSelection) {
            contextSelector.select(
                content = normalizedContent,
                previousVersion = request.target.change.previousVersion,
                currentVersion = request.target.change.currentVersion,
            )
        } else {
            SelectedDocumentContext(
                content = normalizedContent,
                applied = false,
                strategy = "whole-document",
                originalContentLength = normalizedContent.length,
                selectedContentLength = normalizedContent.length,
                selectedHeadings = emptyList(),
                warnings = emptyList(),
            )
        }
        return contentLimiter.limit(
            title = title,
            sourceUrl = sourceUrl,
            content = selection.content,
            version = version,
            originalContentLength = selection.originalContentLength,
            contentSelectionApplied = selection.applied,
            contentSelectionStrategy = selection.strategy,
            selectedContentLength = selection.selectedContentLength,
            selectedHeadings = selection.selectedHeadings,
            selectionWarnings = selection.warnings,
        )
    }

    private fun baseRequest(url: String): HttpRequest {
        val targetUri = URI.create(url)
        val builder = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .header("Accept", "application/vnd.github+json, text/plain, text/html")
            .header("User-Agent", "dependency-upgrade-report")
        githubConfig.token?.takeIf { it.isNotBlank() && shouldAttachGitHubToken(targetUri) }?.let {
            builder.header("Authorization", "Bearer $it")
        }
        return builder.build()
    }

    private fun normalizeExplicitFetchUrl(url: String): String {
        val uri = URI.create(url)
        if (!uri.host.equals("github.com", ignoreCase = true)) {
            return url
        }
        if (!uri.path.contains("/blob/")) {
            return url
        }

        val rawQuery = buildList {
            uri.rawQuery
                ?.split('&')
                ?.filter { it.isNotBlank() && !it.startsWith("raw=") }
                ?.let(::addAll)
            add("raw=1")
        }.joinToString("&")

        return URI(
            uri.scheme,
            uri.authority,
            uri.path,
            rawQuery,
            uri.fragment,
        ).toString()
    }

    private fun sendTextRequest(url: String, redirectCount: Int = 0): HttpResponse<String> {
        val response = httpClient.send(baseRequest(url), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 300..399) {
            return response
        }
        if (redirectCount >= maxRedirects) {
            return response
        }

        val location = response.headers().firstValue("Location").orElse(null) ?: return response
        val redirectedUrl = URI.create(url).resolve(location).toString()
        logger.debug { "Following HTTP ${response.statusCode()} redirect from $url to $redirectedUrl" }
        return sendTextRequest(redirectedUrl, redirectCount + 1)
    }

    private fun shouldAttachGitHubToken(targetUri: URI): Boolean {
        return targetUri.toString().startsWith(githubApiBasePrefix)
    }
}
