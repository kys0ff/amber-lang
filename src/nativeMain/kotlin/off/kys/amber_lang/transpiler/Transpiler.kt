package off.kys.amber_lang.transpiler

import off.kys.amber_lang.runtime.BashRuntimeProvider
import off.kys.amber_lang.transpiler.ast.Program
import off.kys.amber_lang.transpiler.codegen.BashCodeGenerator
import off.kys.amber_lang.transpiler.lexer.Lexer
import off.kys.amber_lang.transpiler.parser.Parser
import off.kys.amber_lang.transpiler.type.TypeChecker

class Transpiler(
    private val sourceCode: String,
    private val currentFilePath: String,
    private val projectRoot: String,
    private val isProject: Boolean = false,
    private val executableDir: String = "."
) {

    /**
     * Transpiles the source code to Bash.
     * Returns a structured TranspilationResult containing the code and/or a list of errors.
     */
    fun transpile(): TranspilationResult {
        // 0. Runtime Provider
        val runtimeProvider = BashRuntimeProvider()

        // 1. Lexing and Parsing
        val (mainProgram, parseErrors) = parseSource(sourceCode)
        if (parseErrors.any { it.severity == Severity.ERROR }) {
            // Return early if syntax is broken
            return TranspilationResult(null, parseErrors)
        }

        // 2. Type Checking
        val typeChecker = TypeChecker(projectRoot, currentFilePath, runtimeProvider, isMainFile = true, isProject = isProject, executableDir = executableDir)
        val (expressionTypes, resolvedSymbols, typeErrors) = typeChecker.check(mainProgram)

        if (typeChecker.hasErrors()) {
            // Return early if types don't match
            return TranspilationResult(null, typeErrors)
        }

        // 3. Optimization (Tree Shaking)
        val shaker = TreeShaker(typeChecker.importedModulePrograms)
        val usedSymbols = shaker.shake(mainProgram)

        // 4. Report unused exports from imported modules
        typeChecker.importedModuleTypeCheckers.values.forEach { it.reportUnusedExports() }

        // Combine all errors and warnings
        val allDiagnostics = mutableListOf<Diagnostic>()
        allDiagnostics.addAll(parseErrors) // Add parse diagnostics (warnings)
        allDiagnostics.addAll(typeErrors)
        typeChecker.importedModuleTypeCheckers.values.forEach { allDiagnostics.addAll(it.errors) }

        // 5. Code Generation
        val allExpressionTypes = expressionTypes.toMutableMap()
        val allResolvedSymbols = resolvedSymbols.toMutableMap()
        typeChecker.importedModuleTypeCheckers.values.forEach {
            allExpressionTypes.putAll(it.expressionTypes)
            allResolvedSymbols.putAll(it.resolvedSymbols)
        }

        return try {
            val codeGenerator = BashCodeGenerator(
                allExpressionTypes,
                allResolvedSymbols,
                typeChecker.importedModulePrograms,
                usedSymbols,
                runtimeProvider
            )
            TranspilationResult(codeGenerator.generate(mainProgram), allDiagnostics)
        } catch (e: Exception) {
            TranspilationResult(null, listOf(GenericDiagnostic(currentFilePath, 0, 0, e.message ?: "unknown error", type = "Codegen Error")))
        }
    }

    /**
     * Helper to handle the parser's Pair(Program, List<SyntaxError>) return type.
     */
    private fun parseSource(source: String): Pair<Program, List<SyntaxError>> {
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens, currentFilePath)
        return parser.parseProgram()
    }
}