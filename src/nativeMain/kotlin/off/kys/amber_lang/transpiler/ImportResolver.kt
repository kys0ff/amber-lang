package off.kys.amber_lang.transpiler

import off.kys.amber_lang.transpiler.ast.Program
import off.kys.amber_lang.transpiler.lexer.Lexer
import off.kys.amber_lang.transpiler.parser.Parser
import off.kys.amber_lang.utils.fileExists
import off.kys.amber_lang.utils.getPathParent
import off.kys.amber_lang.utils.joinPaths
import off.kys.amber_lang.utils.normalizePath
import off.kys.amber_lang.utils.readFile

class ImportResolver(
    private val projectRoot: String,
    private val isProject: Boolean,
    private val executableDir: String
) {
    private val parsedFilesCache = mutableMapOf<String, Program>()
    private val parsingStack = mutableSetOf<String>()

    // We need a way to collect errors from imported files
    val importErrors = mutableListOf<Diagnostic>()

    fun resolveAndParse(importPath: String, currentFilePath: String): Program? {
        val absolutePath = try {
            resolveAbsolutePath(importPath, currentFilePath)
        } catch (e: ImportResolutionException) {
            importErrors.add(GenericDiagnostic(currentFilePath, 0, 0, e.message ?: "unknown import error", type = "Import Error"))
            return null
        }

        if (parsingStack.contains(absolutePath)) {
            importErrors.add(GenericDiagnostic(currentFilePath, 0, 0, "circular import detected: $absolutePath", type = "Import Error"))
            return null
        }

        if (parsedFilesCache.containsKey(absolutePath)) {
            return parsedFilesCache.getValue(absolutePath)
        }

        parsingStack.add(absolutePath)
        return try {
            val fileContent = readFileContent(absolutePath)
            val lexer = Lexer(fileContent)
            val parser = Parser(lexer.tokenize(), absolutePath)

            // Destructure the Pair returned by the refactored Parser
            val (program, errors) = parser.parseProgram()

            if (errors.isNotEmpty()) {
                errors.forEach { importErrors.add(it) }
                // We return the partial program anyway to allow the
                // type checker to find as many errors as possible
            }

            parsedFilesCache[absolutePath] = program
            program
        } catch (e: Exception) {
            importErrors.add(GenericDiagnostic(currentFilePath, 0, 0, "failed to resolve import '$importPath': ${e.message?.lowercase()}", type = "Import Error"))
            null
        } finally {
            parsingStack.remove(absolutePath)
        }
    }

    fun resolveAbsolutePath(importPath: String, currentFilePath: String): String {
        val resolvedPath = when {
            importPath.startsWith("core:") -> {
                val subPath = importPath.removePrefix("core:").replace(":", "/")
                val primaryPath = joinPaths(executableDir, "lib/std", subPath)
                // Check if file exists (with or without extension)
                if (fileExists(primaryPath) || fileExists("$primaryPath.amb")) {
                    primaryPath
                } else {
                    // Fallback to current working directory's lib/std (useful during development)
                    joinPaths(".", "lib/std", subPath)
                }
            }
            importPath.startsWith("local:") -> {
                if (!isProject) {
                    throw ImportResolutionException("local imports are only allowed in project mode")
                }
                val subPath = importPath.removePrefix("local:").replace(":", "/")
                joinPaths(projectRoot, subPath)
            }
            importPath.startsWith("pkg:") -> {
                if (!isProject) {
                    throw ImportResolutionException("package imports are only allowed in project mode")
                }
                val subPath = importPath.removePrefix("pkg:").replace(":", "/")
                joinPaths(projectRoot, "packages", subPath)
            }
            else -> {
                // Legacy support or direct relative/absolute paths
                val currentDirectory = getPathParent(currentFilePath) ?: projectRoot
                if (importPath.startsWith("/")) {
                    joinPaths(projectRoot, importPath.removePrefix("/"))
                } else {
                    joinPaths(currentDirectory, importPath)
                }
            }
        }

        val normalizedResolvedPath = normalizePath(resolvedPath)
        return if (normalizedResolvedPath.endsWith(".amb")) normalizedResolvedPath else "$normalizedResolvedPath.amb"
    }

    private fun readFileContent(absolutePath: String): String {
        if (!fileExists(absolutePath)) {
            throw ImportResolutionException("file not found: $absolutePath")
        }
        return readFile(absolutePath)
    }
}

class ImportResolutionException(message: String) : Exception(message)