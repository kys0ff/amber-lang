package amber.compiler.semantic

import amber.compiler.CompilerConfig
import amber.compiler.ast.Program
import amber.compiler.diagnostic.Diagnostic
import amber.compiler.diagnostic.GenericDiagnostic
import amber.compiler.lexer.Lexer
import amber.compiler.parser.Parser
import amber.util.fileExists
import amber.util.getPathParent
import amber.util.joinPaths
import amber.util.normalizePath
import amber.util.readFile

class ImportResolver(
    private val config: CompilerConfig
) {
    private val parsedFilesCache = mutableMapOf<String, Program>()
    private val parsingStack = mutableSetOf<String>()

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

            val (program, errors) = parser.parseProgram()

            if (errors.isNotEmpty()) {
                errors.forEach { importErrors.add(it) }
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
                val primaryPath = joinPaths(config.executableDir, "lib/std", subPath)
                if (fileExists(primaryPath) || fileExists("$primaryPath.amb")) {
                    primaryPath
                } else {
                    joinPaths(".", "lib/std", subPath)
                }
            }
            importPath.startsWith("local:") -> {
                if (!config.isProject) {
                    throw ImportResolutionException("local imports are only allowed in project mode")
                }
                val subPath = importPath.removePrefix("local:").replace(":", "/")
                joinPaths(config.projectRoot, subPath)
            }
            importPath.startsWith("pkg:") -> {
                if (!config.isProject) {
                    throw ImportResolutionException("package imports are only allowed in project mode")
                }
                val subPath = importPath.removePrefix("pkg:").replace(":", "/")
                joinPaths(config.projectRoot, "packages", subPath)
            }
            else -> {
                val currentDirectory = getPathParent(currentFilePath) ?: config.projectRoot
                if (importPath.startsWith("/")) {
                    joinPaths(config.projectRoot, importPath.removePrefix("/"))
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
