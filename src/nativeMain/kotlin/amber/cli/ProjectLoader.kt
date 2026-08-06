package amber.cli

import amber.util.Ansi
import amber.util.fileExists
import amber.util.getPathParent
import amber.util.isDirectory
import amber.util.joinPaths
import amber.util.normalizePath
import amber.util.readFile
import kotlin.system.exitProcess

data class ProjectConfig(
    val name: String,
    val version: String,
    val entry: String
)

sealed class ProjectFileResult {
    data class Success(val config: ProjectConfig, val projectRoot: String, val entryPath: String) : ProjectFileResult()
    data class Failure(val errors: List<String>) : ProjectFileResult()
}

class ProjectLoader {
    private val PROJECT_NAME_REGEX = Regex("^[A-Za-z0-9_-]+$")

    fun load(target: String): ProjectFileResult {
        val absoluteTarget = normalizePath(target)
        val targetBaseName = absoluteTarget.substringAfterLast('/').substringAfterLast('\\')
        val isExplicitProjectFile = !isDirectory(absoluteTarget) && targetBaseName == "project"

        if (isDirectory(absoluteTarget) || isExplicitProjectFile) {
            val currentProjectRoot = if (isDirectory(absoluteTarget)) absoluteTarget else (getPathParent(absoluteTarget) ?: ".")
            val projectFilePath = joinPaths(currentProjectRoot, "project")

            if (!fileExists(projectFilePath)) {
                return ProjectFileResult.Failure(listOf("no 'project' file found in $currentProjectRoot"))
            }

            val configResult = parseProjectFile(readFile(projectFilePath))
            if (configResult is ProjectFileResult.Failure) return configResult
            
            val config = (configResult as ProjectFileResult.Success).config
            val entryPath = normalizePath(joinPaths(currentProjectRoot, config.entry))
            
            if (!fileExists(entryPath)) {
                return ProjectFileResult.Failure(listOf("entry file '${config.entry}' not found in $currentProjectRoot"))
            }
            
            return ProjectFileResult.Success(config, currentProjectRoot, entryPath)
        } else {
            if (!fileExists(absoluteTarget)) {
                return ProjectFileResult.Failure(listOf("file or directory not found: $target"))
            }
            return ProjectFileResult.Success(
                ProjectConfig(sanitizeFileName(baseNameWithoutExtension(absoluteTarget)), "0.0.1", absoluteTarget),
                getPathParent(absoluteTarget) ?: ".",
                absoluteTarget
            )
        }
    }

    private fun parseProjectFile(content: String): ProjectFileResult {
        var name: String? = null
        var version: String? = null
        var entry: String? = null
        val errors = mutableListOf<String>()

        content.lines().forEachIndexed { index, rawLine ->
            val cleanLine = rawLine.split("//")[0].trim()
            if (cleanLine.isEmpty()) return@forEachIndexed

            val eqIndex = cleanLine.indexOf('=')
            if (eqIndex == -1) {
                errors += "line ${index + 1}: expected 'key = value', got '$cleanLine'"
                return@forEachIndexed
            }

            val key = cleanLine.substring(0, eqIndex).trim()
            val value = cleanLine.substring(eqIndex + 1).trim().removeSurrounding("\"")

            if (value.isEmpty()) {
                errors += "line ${index + 1}: '$key' has an empty value"
                return@forEachIndexed
            }

            when (key) {
                "name" -> name = value
                "version" -> version = value
                "entry" -> entry = value
                else -> errors += "line ${index + 1}: unknown project key '$key'"
            }
        }

        val resolvedName = name
        if (resolvedName == null) {
            errors += "missing required field 'name'"
        } else if (!PROJECT_NAME_REGEX.matches(resolvedName)) {
            errors += "'name' must contain only letters, numbers, '-' and '_' (got '$resolvedName')"
        }

        val resolvedEntry = entry
        if (resolvedEntry != null && !resolvedEntry.endsWith(".amb")) {
            errors += "'entry' must point to a '.amb' file (got '$resolvedEntry')"
        }

        if (errors.isNotEmpty()) return ProjectFileResult.Failure(errors)

        return ProjectFileResult.Success(
            ProjectConfig(
                name = resolvedName!!,
                version = version ?: "0.0.1",
                entry = resolvedEntry ?: "main.amb"
            ),
            "", "" // Placeholder, updated in load()
        )
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return cleaned.ifEmpty { "output" }
    }

    private fun baseNameWithoutExtension(path: String): String {
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        return fileName.substringBeforeLast('.', fileName)
    }
}
