package hugy.dependencyreport.core.fetch

import hugy.dependencyreport.core.model.FetchedDocument

class DocumentContentLimiter(
    private val maxDocumentContentChars: Int,
) {
    fun limit(
        title: String,
        sourceUrl: String,
        content: String,
        version: String? = null,
    ): FetchedDocument {
        val normalizedLimit = maxDocumentContentChars.coerceAtLeast(1)
        val normalizedContent = content.trim()
        if (normalizedContent.length <= normalizedLimit) {
            return FetchedDocument(
                title = title,
                sourceUrl = sourceUrl,
                content = normalizedContent,
                version = version,
                contentTruncated = false,
                originalContentLength = normalizedContent.length,
            )
        }

        return FetchedDocument(
            title = title,
            sourceUrl = sourceUrl,
            content = "Document content omitted because it exceeded $normalizedLimit characters. See release notes: $sourceUrl",
            version = version,
            contentTruncated = true,
            originalContentLength = normalizedContent.length,
        )
    }
}
