package hugy.dependencyreport.core.source

import hugy.dependencyreport.core.config.InferenceConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

sealed interface MetadataLookupResult {
    data class Success(
        val url: String,
        val content: String,
    ) : MetadataLookupResult

    data class Failure(
        val url: String,
        val reason: String,
    ) : MetadataLookupResult
}

interface SourceMetadataLookup {
    fun fetchMavenPom(module: String, version: String): MetadataLookupResult
}

class HttpSourceMetadataLookup(
    private val inferenceConfig: InferenceConfig,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : SourceMetadataLookup {
    override fun fetchMavenPom(module: String, version: String): MetadataLookupResult {
        val group = module.substringBefore(':')
        val artifact = module.substringAfter(':')
        val groupPath = group.replace('.', '/')
        val url = "${inferenceConfig.mavenRepositoryBaseUrl.trimEnd('/')}/$groupPath/$artifact/$version/$artifact-$version.pom"
        return fetch(url)
    }

    private fun fetch(url: String): MetadataLookupResult {
        return try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .header("Accept", "application/xml, text/xml, text/plain")
                .header("User-Agent", "dependency-upgrade-report")
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                MetadataLookupResult.Failure(url, "HTTP ${response.statusCode()}")
            } else {
                MetadataLookupResult.Success(url, response.body())
            }
        } catch (exception: Exception) {
            MetadataLookupResult.Failure(url, exception.message ?: "Unknown error")
        }
    }
}
