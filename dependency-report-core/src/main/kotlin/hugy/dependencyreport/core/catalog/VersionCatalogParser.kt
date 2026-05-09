package hugy.dependencyreport.core.catalog

import hugy.dependencyreport.core.model.CatalogSnapshot
import hugy.dependencyreport.core.model.LibraryAlias
import hugy.dependencyreport.core.model.PluginAlias
import org.tomlj.Toml
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import java.nio.file.Path
import kotlin.io.path.reader

class VersionCatalogParser {
    fun parse(path: Path): CatalogSnapshot {
        path.reader().use { reader ->
            val result = Toml.parse(reader)
            if (result.hasErrors()) {
                val details = result.errors().joinToString("; ") { it.toString() }
                throw IllegalArgumentException("Failed to parse version catalog $path: $details")
            }
            return CatalogSnapshot(
                versions = parseVersions(result),
                libraries = parseLibraries(result),
                plugins = parsePlugins(result),
            )
        }
    }

    private fun parseVersions(result: TomlParseResult): Map<String, String> {
        val table = result.getTable("versions") ?: return emptyMap()
        return table.keySet().associateWith { alias ->
            val value = table.get(alias)
            when (value) {
                is String -> value
                is TomlTable -> extractVersionValue(value)
                else -> value?.toString() ?: ""
            }
        }
    }

    private fun parseLibraries(result: TomlParseResult): Map<String, LibraryAlias> {
        val table = result.getTable("libraries") ?: return emptyMap()
        return table.keySet().associateWith { alias ->
            val value = table.getTable(alias)
                ?: throw IllegalArgumentException("Library alias '$alias' must be declared as an inline table")
            val module = value.getString("module") ?: buildModule(value)
            val group = module.substringBefore(':')
            val name = module.substringAfter(':')
            LibraryAlias(
                alias = alias,
                group = group,
                name = name,
                module = module,
                versionRef = value.getString("version.ref"),
                versionLiteral = readVersionLiteral(value),
            )
        }
    }

    private fun parsePlugins(result: TomlParseResult): Map<String, PluginAlias> {
        val table = result.getTable("plugins") ?: return emptyMap()
        return table.keySet().associateWith { alias ->
            val value = table.getTable(alias)
                ?: throw IllegalArgumentException("Plugin alias '$alias' must be declared as an inline table")
            PluginAlias(
                alias = alias,
                id = value.getString("id")
                    ?: throw IllegalArgumentException("Plugin alias '$alias' is missing id"),
                versionRef = value.getString("version.ref"),
                versionLiteral = readVersionLiteral(value),
            )
        }
    }

    private fun buildModule(table: TomlTable): String {
        val group = table.getString("group")
            ?: throw IllegalArgumentException("Library entry is missing group")
        val name = table.getString("name")
            ?: throw IllegalArgumentException("Library entry is missing name")
        return "$group:$name"
    }

    private fun readVersionLiteral(table: TomlTable): String? {
        val versionValue = table.get("version")
        return when (versionValue) {
            is String -> versionValue
            is TomlTable -> extractVersionValue(versionValue)
            else -> null
        }
    }

    private fun extractVersionValue(table: TomlTable): String {
        return table.getString("require")
            ?: table.getString("strictly")
            ?: table.getString("prefer")
            ?: table.toMap().toString()
    }
}
