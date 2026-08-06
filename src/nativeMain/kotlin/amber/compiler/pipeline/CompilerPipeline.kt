package amber.compiler.pipeline

import amber.compiler.CompilationResult
import amber.compiler.CompilerConfig
import amber.compiler.GCType
import amber.compiler.backend.c.CBackend
import amber.compiler.diagnostic.Diagnostic
import amber.compiler.diagnostic.DiagnosticSeverity
import amber.compiler.diagnostic.GenericDiagnostic
import amber.compiler.lexer.Lexer
import amber.compiler.parser.Parser
import amber.compiler.semantic.TreeShaker
import amber.compiler.type.TypeChecker
import amber.runtime.BoehmGC
import amber.runtime.CRuntimeProvider
import amber.runtime.NoGC

/**
 * Orchestrates the various phases of the compiler pipeline.
 */
class CompilerPipeline(val config: CompilerConfig) {
    
    /**
     * Executes the compiler pipeline on the given source.
     */
    fun execute(source: String, filePath: String): CompilationResult {
        if (config.verbose) {
            println("→ pipeline start: $filePath")
        }
        val runtimeProvider = CRuntimeProvider()

        // 1. Lexing & Parsing
        if (config.verbose) println("  → lexing & parsing")
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens, filePath)
        val (program, parseErrors) = parser.parseProgram()
        
        if (parseErrors.any { it.severity == DiagnosticSeverity.ERROR }) {
            return CompilationResult(null, parseErrors)
        }

        // 2. Type Checking
        if (config.verbose) println("  → type checking")
        val typeChecker = TypeChecker(
            config,
            filePath,
            runtimeProvider,
            isMainFile = true
        )
        val (expressionTypes, resolvedSymbols, typeErrors) = typeChecker.check(program)

        if (typeChecker.hasErrors()) {
            return CompilationResult(null, typeErrors)
        }

        // 3. Optimization (Tree Shaking)
        if (config.optimizationLevel != amber.compiler.OptimizationLevel.O0) {
            if (config.verbose) println("  → tree shaking")
            val shaker = TreeShaker(typeChecker.importedModulePrograms)
            shaker.shake(program)
        } else {
            if (config.verbose) println("  → tree shaking skipped (O0)")
        }

        typeChecker.importedModuleTypeCheckers.values.forEach { it.reportUnusedExports() }

        val allDiagnostics = mutableListOf<Diagnostic>()
        allDiagnostics.addAll(parseErrors)
        allDiagnostics.addAll(typeErrors)
        typeChecker.importedModuleTypeCheckers.values.forEach { allDiagnostics.addAll(it.errors) }

        // 4. Code Generation
        if (config.verbose) println("  → code generation")
        val allExpressionTypes = expressionTypes.toMutableMap()
        val allResolvedSymbols = resolvedSymbols.toMutableMap()
        typeChecker.importedModuleTypeCheckers.values.forEach {
            allExpressionTypes.putAll(it.expressionTypes)
            allResolvedSymbols.putAll(it.resolvedSymbols)
        }

        val gc = when (config.gc) {
            GCType.BOEHM -> BoehmGC
            GCType.NONE -> NoGC
        }

        return try {
            val codeGenerator = CBackend(
                allExpressionTypes,
                allResolvedSymbols,
                runtimeProvider,
                gc = gc,
                config = config
            )
            CompilationResult(codeGenerator.generate(program), allDiagnostics)
        } catch (e: Exception) {
            CompilationResult(null, allDiagnostics + listOf(GenericDiagnostic(filePath, 0, 0, e.message ?: "unknown error", type = "Codegen Error")))
        }
    }
}
