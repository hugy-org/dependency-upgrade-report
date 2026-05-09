package hugy.dependencyreport.cli

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal data class CatalogInputFiles(
    val previousCatalog: Path,
    val currentCatalog: Path,
)

private val logger = KotlinLogging.logger {}

internal class GitCatalogSnapshotProvider(
    private val workingDirectory: Path,
) {
    fun resolve(
        explicitPreviousCatalog: Path?,
        explicitCurrentCatalog: Path?,
        catalogPathOption: String?,
        gitRef: String,
    ): CatalogInputFiles {
        if (explicitPreviousCatalog != null || explicitCurrentCatalog != null) {
            require(explicitPreviousCatalog != null && explicitCurrentCatalog != null) {
                "Both --previous-catalog and --current-catalog must be provided when using explicit snapshots"
            }
            logger.info {
                "Using explicit catalog snapshots: previous=${explicitPreviousCatalog.toAbsolutePath()}, current=${explicitCurrentCatalog.toAbsolutePath()}"
            }
            return CatalogInputFiles(
                previousCatalog = explicitPreviousCatalog,
                currentCatalog = explicitCurrentCatalog,
            )
        }

        return loadFromGit(catalogPathOption, gitRef)
    }

    private fun loadFromGit(
        catalogPathOption: String?,
        gitRef: String,
    ): CatalogInputFiles {
        val repository = openRepository()
        repository.use { repo ->
            val repoRoot = repo.workTree.toPath().toAbsolutePath().normalize()
            val catalogPath = resolveCatalogPath(repoRoot, catalogPathOption)
            val relativeCatalogPath = toRepoRelativePath(repoRoot, catalogPath)
            logger.info {
                "Using git-backed catalog snapshots from ref=$gitRef and path=$relativeCatalogPath"
            }

            require(catalogPath.exists()) {
                "Catalog file does not exist at ${catalogPath.toAbsolutePath()}"
            }

            val previousContent = readRevisionFile(repo, gitRef, relativeCatalogPath)
                ?: error("Catalog file '$relativeCatalogPath' was not found at git ref '$gitRef'")

            val tempDir = Files.createTempDirectory("dependency-report-catalogs")
            val previousCatalog = tempDir.resolve("previous-catalog.toml")
            val currentCatalog = tempDir.resolve("current-catalog.toml")
            previousCatalog.writeText(previousContent)
            currentCatalog.writeText(catalogPath.readText())
            logger.info { "Materialized git-backed snapshots under $tempDir" }

            return CatalogInputFiles(
                previousCatalog = previousCatalog,
                currentCatalog = currentCatalog,
            )
        }
    }

    private fun openRepository(): Repository {
        val builder = FileRepositoryBuilder().findGitDir(workingDirectory.toFile())
        val gitDir = builder.gitDir ?: error("No git repository found from ${workingDirectory.toAbsolutePath()}")
        return builder.setGitDir(gitDir).readEnvironment().build()
    }

    private fun resolveCatalogPath(
        repoRoot: Path,
        catalogPathOption: String?,
    ): Path {
        if (catalogPathOption != null) {
            return repoRoot.resolve(catalogPathOption).normalize()
        }

        val preferredDefaults = listOf(
            repoRoot / "gradle" / "libs.catalog.toml",
            repoRoot / "gradle" / "libs.versions.toml",
        )
        val selectedPath = preferredDefaults.firstOrNull(Path::exists)
            ?: preferredDefaults.first()
        logger.info { "Resolved default catalog path to ${repoRoot.relativize(selectedPath)}" }
        return selectedPath
    }

    private fun toRepoRelativePath(
        repoRoot: Path,
        catalogPath: Path,
    ): String {
        val normalizedCatalogPath = catalogPath.toAbsolutePath().normalize()
        require(normalizedCatalogPath.startsWith(repoRoot)) {
            "Catalog path must be inside the git repository: ${normalizedCatalogPath}"
        }
        return repoRoot.relativize(normalizedCatalogPath).invariantSeparatorsPathString
    }

    private fun readRevisionFile(
        repository: Repository,
        gitRef: String,
        relativeCatalogPath: String,
    ): String? {
        val objectId = repository.resolve(gitRef) ?: error("Could not resolve git ref '$gitRef'")

        repository.newObjectReader().use { reader ->
            RevWalk(repository).use { revWalk ->
                val commit = revWalk.parseCommit(objectId)
                TreeWalk.forPath(reader, relativeCatalogPath, commit.tree)?.use { treeWalk ->
                    val objectLoader = reader.open(treeWalk.getObjectId(0), Constants.OBJ_BLOB)
                    return String(objectLoader.bytes, Charsets.UTF_8)
                }
            }
        }

        return null
    }
}
