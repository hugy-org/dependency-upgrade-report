package hugy.dependencyreport.core.source

import hugy.dependencyreport.core.config.DependencyReportConfig
import hugy.dependencyreport.core.config.InferenceConfig
import hugy.dependencyreport.core.config.SourceDefinition
import hugy.dependencyreport.core.config.SourceMatch
import hugy.dependencyreport.core.model.AliasUsage
import hugy.dependencyreport.core.model.ReleaseSourceType
import hugy.dependencyreport.core.model.UpgradeKind
import hugy.dependencyreport.core.model.UpgradeTarget
import hugy.dependencyreport.core.model.UsageType
import hugy.dependencyreport.core.model.VersionAliasChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseSourceResolverTest {
    @Test
    fun `explicit sources win before maven inference`() {
        val resolver = ReleaseSourceResolver(
            config = DependencyReportConfig(
                sources = listOf(SourceDefinition(match = SourceMatch(alias = "kotlin"), githubRepo = "JetBrains/kotlin")),
            ),
            metadataLookup = FakeSourceMetadataLookup(
                pomResults = mapOf(
                    "org.jetbrains.kotlin:kotlin-bom:2.0.0" to MetadataLookupResult.Success(
                        url = "https://repo1.maven.org/kotlin-bom.pom",
                        content = "<project><scm><url>https://github.com/someone-else/kotlin</url></scm></project>",
                    ),
                ),
            ),
        )

        val resolution = resolver.resolve(kotlinBomTarget())

        assertEquals("sources", resolution.matchedBy)
        assertEquals("JetBrains/kotlin", resolution.source.repository)
    }

    @Test
    fun `infers github repo from maven pom metadata`() {
        val resolver = ReleaseSourceResolver(
            config = DependencyReportConfig(),
            metadataLookup = FakeSourceMetadataLookup(
                pomResults = mapOf(
                    "org.jetbrains.kotlin:kotlin-bom:2.0.0" to MetadataLookupResult.Success(
                        url = "https://repo1.maven.org/kotlin-bom.pom",
                        content = """
                            <project>
                              <url>https://kotlinlang.org</url>
                              <scm>
                                <url>https://github.com/JetBrains/kotlin</url>
                              </scm>
                            </project>
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val resolution = resolver.resolve(kotlinBomTarget())

        assertEquals("inferredMavenPom", resolution.matchedBy)
        assertEquals(ReleaseSourceType.GITHUB_RELEASES, resolution.source.type)
        assertEquals("JetBrains/kotlin", resolution.source.repository)
    }

    @Test
    fun `explicit changelog source can be marked as link only`() {
        val resolver = ReleaseSourceResolver(
            config = DependencyReportConfig(
                sources = listOf(
                    SourceDefinition(
                        match = SourceMatch(alias = "google-api-services-gmail"),
                        changelogUrl = "https://developers.google.com/workspace/gmail/release-notes",
                        linkOnly = true,
                    ),
                ),
            ),
            metadataLookup = FakeSourceMetadataLookup(),
        )

        val resolution = resolver.resolve(
            UpgradeTarget(
                change = VersionAliasChange("google-api-services-gmail", "v1-rev20260413-2.0.0", "v1-rev20260427-2.0.0"),
                usages = listOf(
                    AliasUsage(
                        alias = "google-api-services-gmail",
                        usageType = UsageType.LIBRARY,
                        identifier = "com.google.apis:google-api-services-gmail",
                        versionRef = "google-api-services-gmail",
                    ),
                ),
                kind = UpgradeKind.LIBRARY,
            ),
        )

        assertEquals("sources", resolution.matchedBy)
        assertEquals(ReleaseSourceType.EXPLICIT_CHANGELOG_URL, resolution.source.type)
        assertTrue(resolution.linkOnly)
    }

    @Test
    fun `unresolved includes maven inference provenance only`() {
        val resolver = ReleaseSourceResolver(
            config = DependencyReportConfig(inference = InferenceConfig(enabled = true)),
            metadataLookup = FakeSourceMetadataLookup(),
        )

        val resolution = resolver.resolve(pluginTarget())

        assertEquals("unresolved", resolution.matchedBy)
        assertEquals(ReleaseSourceType.UNRESOLVED, resolution.source.type)
        assertTrue(resolution.provenance.any { it.contains("inferredMavenPom") })
        assertTrue(resolution.provenance.none { it.contains("Gradle Plugin Portal") })
    }

    @Test
    fun `maven pom parsing ignores external entities`() {
        val resolver = ReleaseSourceResolver(
            config = DependencyReportConfig(),
            metadataLookup = FakeSourceMetadataLookup(
                pomResults = mapOf(
                    "org.jetbrains.kotlin:kotlin-bom:2.0.0" to MetadataLookupResult.Success(
                        url = "https://repo1.maven.org/kotlin-bom.pom",
                        content = """
                            <!DOCTYPE project [
                              <!ENTITY xxe SYSTEM "file:///etc/passwd">
                            ]>
                            <project>
                              <url>&xxe;</url>
                              <scm>
                                <url>https://github.com/JetBrains/kotlin</url>
                              </scm>
                            </project>
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val resolution = resolver.resolve(kotlinBomTarget())

        assertEquals("unresolved", resolution.matchedBy)
        assertEquals(ReleaseSourceType.UNRESOLVED, resolution.source.type)
    }

    private fun kotlinBomTarget() = UpgradeTarget(
        change = VersionAliasChange("kotlin", "1.9.24", "2.0.0"),
        usages = listOf(
            AliasUsage(
                alias = "kotlin-bom",
                usageType = UsageType.BOM,
                identifier = "org.jetbrains.kotlin:kotlin-bom",
                versionRef = "kotlin",
            ),
        ),
        kind = UpgradeKind.BOM,
    )

    private fun pluginTarget() = UpgradeTarget(
        change = VersionAliasChange("sentry", "6.5.0", "6.6.0"),
        usages = listOf(
            AliasUsage(
                alias = "sentry",
                usageType = UsageType.GRADLE_PLUGIN,
                identifier = "io.sentry.jvm.gradle",
                versionRef = "sentry",
            ),
        ),
        kind = UpgradeKind.GRADLE_PLUGIN,
    )
}

private class FakeSourceMetadataLookup(
    private val pomResults: Map<String, MetadataLookupResult> = emptyMap(),
) : SourceMetadataLookup {
    override fun fetchMavenPom(module: String, version: String): MetadataLookupResult {
        return pomResults["$module:$version"]
            ?: MetadataLookupResult.Failure("maven://$module/$version", "fixture not found")
    }
}
