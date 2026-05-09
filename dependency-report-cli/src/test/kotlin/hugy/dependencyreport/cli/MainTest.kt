package hugy.dependencyreport.cli

import org.eclipse.jgit.api.Git
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun `generate uses git-backed default catalog path`() {
        val repoRoot = createTempRepo()
        val catalogPath = repoRoot.resolve("gradle/libs.catalog.toml")
        val outputDir = repoRoot.resolve("build/dependency-report")
        val configPath = repoRoot.resolve("dependency-report.yaml")

        catalogPath.parent.createDirectories()
        catalogPath.writeText(
            """
            [versions]
            kotlin = "1.9.24"

            [libraries]
            kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
            """.trimIndent(),
        )
        configPath.writeText(
            """
            sources: []
            inference:
              enabled: false
            llm:
              mode: disabled
            """.trimIndent(),
        )

        Git.init().setDirectory(repoRoot.toFile()).call().use { git ->
            git.add().addFilepattern("gradle/libs.catalog.toml").call()
            git.commit().setMessage("Initial catalog").setAuthor("Test", "test@example.com").call()
        }

        catalogPath.writeText(
            """
            [versions]
            kotlin = "2.0.0"

            [libraries]
            kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
            """.trimIndent(),
        )

        val exitCode = runCli(
            args = listOf(
                "generate",
                "--config", configPath.toString(),
                "--output-dir", outputDir.toString(),
            ),
            workingDirectory = repoRoot,
        )

        assertEquals(0, exitCode)
        assertTrue(outputDir.resolve("report.json").toFile().isFile)
        val reportJson = outputDir.resolve("report.json").readText()
        assertTrue(reportJson.contains("\"alias\" : \"kotlin\""))
        assertTrue(reportJson.contains("\"previousVersion\" : \"1.9.24\""))
        assertTrue(reportJson.contains("\"currentVersion\" : \"2.0.0\""))
    }

    @Test
    fun `generate supports custom catalog path in git-backed mode`() {
        val repoRoot = createTempRepo()
        val catalogPath = repoRoot.resolve("config/custom-libs.versions.toml")
        val outputDir = repoRoot.resolve("build/dependency-report-custom")
        val configPath = repoRoot.resolve("dependency-report.yaml")

        catalogPath.parent.createDirectories()
        catalogPath.writeText(
            """
            [versions]
            benManes = "0.50.0"

            [plugins]
            versions = { id = "com.github.ben-manes.versions", version.ref = "benManes" }
            """.trimIndent(),
        )
        configPath.writeText(
            """
            sources: []
            inference:
              enabled: false
            llm:
              mode: disabled
            """.trimIndent(),
        )

        Git.init().setDirectory(repoRoot.toFile()).call().use { git ->
            git.add().addFilepattern("config/custom-libs.versions.toml").call()
            git.commit().setMessage("Initial custom catalog").setAuthor("Test", "test@example.com").call()
        }

        catalogPath.writeText(
            """
            [versions]
            benManes = "0.51.0"

            [plugins]
            versions = { id = "com.github.ben-manes.versions", version.ref = "benManes" }
            """.trimIndent(),
        )

        val exitCode = runCli(
            args = listOf(
                "generate",
                "--config", configPath.toString(),
                "--output-dir", outputDir.toString(),
                "--catalog-path", "config/custom-libs.versions.toml",
            ),
            workingDirectory = repoRoot,
        )

        assertEquals(0, exitCode)
        val reportJson = outputDir.resolve("report.json").readText()
        assertTrue(reportJson.contains("\"identifier\" : \"com.github.ben-manes.versions\""))
        assertTrue(reportJson.contains("\"previousVersion\" : \"0.50.0\""))
        assertTrue(reportJson.contains("\"currentVersion\" : \"0.51.0\""))
    }

    private fun createTempRepo(): Path {
        return kotlin.io.path.createTempDirectory("dependency-report-cli-test")
    }
}
