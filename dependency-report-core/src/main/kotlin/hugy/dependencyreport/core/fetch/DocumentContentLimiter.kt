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
        originalContentLength: Int = content.trim().length,
        contentSelectionApplied: Boolean = false,
        contentSelectionStrategy: String? = null,
        selectedContentLength: Int = content.trim().length,
        selectedHeadings: List<String> = emptyList(),
        selectionWarnings: List<String> = emptyList(),
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
                originalContentLength = originalContentLength,
                contentSelectionApplied = contentSelectionApplied,
                contentSelectionStrategy = contentSelectionStrategy,
                selectedContentLength = selectedContentLength,
                selectedHeadings = selectedHeadings,
                selectionWarnings = selectionWarnings,
            )
        }

        return FetchedDocument(
            title = title,
            sourceUrl = sourceUrl,
            content = "Document content omitted because it exceeded $normalizedLimit characters. See release notes: $sourceUrl",
            version = version,
            contentTruncated = true,
            originalContentLength = originalContentLength,
            contentSelectionApplied = contentSelectionApplied,
            contentSelectionStrategy = contentSelectionStrategy,
            selectedContentLength = selectedContentLength,
            selectedHeadings = selectedHeadings,
            selectionWarnings = selectionWarnings + "Selected content still exceeded $normalizedLimit characters and was omitted.",
        )
    }
}
