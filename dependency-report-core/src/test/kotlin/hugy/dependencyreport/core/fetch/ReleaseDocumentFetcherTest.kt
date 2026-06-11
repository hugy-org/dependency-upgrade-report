package hugy.dependencyreport.core.fetch

import com.sun.net.httpserver.HttpServer
import hugy.dependencyreport.core.config.FetchConfig
import hugy.dependencyreport.core.config.GitHubConfig
import hugy.dependencyreport.core.model.ReleaseSource
import hugy.dependencyreport.core.model.ReleaseSourceType
import hugy.dependencyreport.core.model.UpgradeKind
import hugy.dependencyreport.core.model.UpgradeTarget
import hugy.dependencyreport.core.model.VersionAliasChange
import java.net.InetSocketAddress
import java.net.http.HttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseDocumentFetcherTest {
    private var server: HttpServer? = null
    private var secondServer: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        server = null
        secondServer?.stop(0)
        secondServer = null
    }

    @Test
    fun `oversized explicit changelog selects relevant version section before final cap`() {
        val body = """
            # Changelog
            Index text that is intentionally long and not very useful.
            
            ## 2.1.0
            Old release notes.
            
            ## 2.2.21
            Breaking change for the target release.
            Migration guidance for Gradle plugin users.
            
            ## 2.3.0
            Future release notes that should not be selected.
        """.trimIndent()
        val oversizedBody = List(8) { body }.joinToString("\n\n")

        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/changes") { exchange ->
                exchange.sendResponseHeaders(200, oversizedBody.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(oversizedBody.toByteArray()) }
            }
            start()
        }

        val fetcher = HttpReleaseDocumentFetcher(
            githubConfig = GitHubConfig(),
            fetchConfig = FetchConfig(maxDocumentContentChars = 300),
            httpClient = HttpClient.newHttpClient(),
        )
        val url = "http://localhost:${server!!.address.port}/changes"

        val result = fetcher.fetch(
            ReleaseFetchRequest(
                source = ReleaseSource(
                    type = ReleaseSourceType.EXPLICIT_CHANGELOG_URL,
                    displayName = "Example changelog",
                    sourceUrl = url,
                ),
                target = UpgradeTarget(
                    change = VersionAliasChange(
                        alias = "example",
                        previousVersion = "2.2.20",
                        currentVersion = "2.2.21",
                    ),
                    usages = emptyList(),
                    kind = UpgradeKind.LIBRARY,
                ),
            ),
        )

        val success = result as FetchResult.Success
        val document = success.documents.single()
        assertTrue(document.contentSelectionApplied)
        assertEquals("version-aware-sections", document.contentSelectionStrategy)
        assertTrue(document.selectedHeadings.contains("2.2.21"))
        assertTrue(document.content.contains("## 2.2.21"))
        assertFalse(document.content.contains("## 2.3.0"))
        assertTrue(document.originalContentLength > document.selectedContentLength)
    }

    @Test
    fun `github blob changelog urls are fetched in raw mode`() {
        val fetcher = HttpReleaseDocumentFetcher(
            githubConfig = GitHubConfig(),
            fetchConfig = FetchConfig(maxDocumentContentChars = 500),
            httpClient = HttpClient.newHttpClient(),
        )

        val normalized = fetcher.javaClass.getDeclaredMethod("normalizeExplicitFetchUrl", String::class.java).apply {
            isAccessible = true
        }.invoke(
            fetcher,
            "https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/identity/azure-identity/CHANGELOG.md",
        ) as String

        assertEquals(
            "https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/identity/azure-identity/CHANGELOG.md?raw=1",
            normalized,
        )
    }

    @Test
    fun `explicit changelog fetch follows redirects`() {
        val markdown = """
            ## 1.1.0
            Security fix for the target release.
        """.trimIndent()

        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/redirect") { exchange ->
                exchange.responseHeaders.add("Location", "/raw")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            createContext("/raw") { exchange ->
                exchange.sendResponseHeaders(200, markdown.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(markdown.toByteArray()) }
            }
            start()
        }

        val fetcher = HttpReleaseDocumentFetcher(
            githubConfig = GitHubConfig(),
            fetchConfig = FetchConfig(maxDocumentContentChars = 20),
            httpClient = HttpClient.newHttpClient(),
        )
        val url = "http://localhost:${server!!.address.port}/redirect"

        val result = fetcher.fetch(
            ReleaseFetchRequest(
                source = ReleaseSource(
                    type = ReleaseSourceType.EXPLICIT_CHANGELOG_URL,
                    displayName = "Redirected changelog",
                    sourceUrl = url,
                ),
                target = UpgradeTarget(
                    change = VersionAliasChange(
                        alias = "example",
                        previousVersion = "1.0.0",
                        currentVersion = "1.1.0",
                    ),
                    usages = emptyList(),
                    kind = UpgradeKind.LIBRARY,
                ),
            ),
        )

        val success = result as FetchResult.Success
        val document = success.documents.single()
        assertTrue(document.contentSelectionApplied)
        assertEquals("version-aware-sections", document.contentSelectionStrategy)
        assertTrue(document.selectedHeadings.contains("1.1.0"))
        assertTrue(document.originalContentLength > 0)
    }

    @Test
    fun `github token is not attached to explicit changelog urls or cross host redirects`() {
        var initialAuthorization: String? = null
        var redirectedAuthorization: String? = null
        secondServer = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/raw") { exchange ->
                redirectedAuthorization = exchange.requestHeaders.getFirst("Authorization")
                val markdown = "## 1.1.0\nRedirected changelog."
                exchange.sendResponseHeaders(200, markdown.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(markdown.toByteArray()) }
            }
            start()
        }
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/redirect") { exchange ->
                initialAuthorization = exchange.requestHeaders.getFirst("Authorization")
                exchange.responseHeaders.add("Location", "http://localhost:${secondServer!!.address.port}/raw")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            start()
        }

        val fetcher = HttpReleaseDocumentFetcher(
            githubConfig = GitHubConfig(
                token = "secret-token",
                apiBaseUrl = "http://localhost:${server!!.address.port}/api",
            ),
            fetchConfig = FetchConfig(maxDocumentContentChars = 20),
            httpClient = HttpClient.newHttpClient(),
        )

        val result = fetcher.fetch(
            ReleaseFetchRequest(
                source = ReleaseSource(
                    type = ReleaseSourceType.EXPLICIT_CHANGELOG_URL,
                    displayName = "Redirected changelog",
                    sourceUrl = "http://localhost:${server!!.address.port}/redirect",
                ),
                target = UpgradeTarget(
                    change = VersionAliasChange(
                        alias = "example",
                        previousVersion = "1.0.0",
                        currentVersion = "1.1.0",
                    ),
                    usages = emptyList(),
                    kind = UpgradeKind.LIBRARY,
                ),
            ),
        )

        assertTrue(result is FetchResult.Success)
        assertEquals(null, initialAuthorization)
        assertEquals(null, redirectedAuthorization)
    }

    @Test
    fun `github release bodies skip context selection and use whole body plus final cap`() {
        val oversizedBody = """
            ## 1.1.0
            This is an intentionally large release body that should stay whole for GitHub releases.
        """.trimIndent().repeat(20)

        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/repos/example/library/releases") { exchange ->
                val body = """
                    [
                      {
                        "tag_name": "v1.1.0",
                        "name": "v1.1.0",
                        "html_url": "https://github.com/example/library/releases/tag/v1.1.0",
                        "body": ${quoteJson(oversizedBody)}
                      }
                    ]
                """.trimIndent()
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            start()
        }

        val fetcher = HttpReleaseDocumentFetcher(
            githubConfig = GitHubConfig(
                apiBaseUrl = "http://localhost:${server!!.address.port}",
                maxReleases = 1,
                pageSize = 10,
                maxScanReleases = 10,
            ),
            fetchConfig = FetchConfig(maxDocumentContentChars = 120),
            httpClient = HttpClient.newHttpClient(),
        )

        val result = fetcher.fetch(
            ReleaseFetchRequest(
                source = ReleaseSource(
                    type = ReleaseSourceType.GITHUB_RELEASES,
                    displayName = "example/library",
                    repository = "example/library",
                ),
                target = UpgradeTarget(
                    change = VersionAliasChange(
                        alias = "example",
                        previousVersion = "1.0.0",
                        currentVersion = "1.1.0",
                    ),
                    usages = emptyList(),
                    kind = UpgradeKind.LIBRARY,
                ),
            ),
        )

        val success = result as FetchResult.Success
        val document = success.documents.single()
        assertFalse(document.contentSelectionApplied)
        assertEquals("whole-document", document.contentSelectionStrategy)
        assertTrue(document.contentTruncated)
    }

    private fun quoteJson(value: String): String =
        buildString {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }
}
