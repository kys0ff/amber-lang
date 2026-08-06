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
import amber.util.ConsoleLogger
import amber.util.LogLevel
import amber.util.Logger
import amber.util.NoLogger

/**
 * Orchestrates the various phases of the compiler pipeline.
 */
class CompilerPipeline(val config: CompilerConfig) {

    private val logger: Logger = if (config.quiet) NoLogger else ConsoleLogger(
        minLevel = if (config.verbose) LogLevel.DEBUG else LogLevel.INFO,
        useColor = config.useColor
    )
    
    /**
     * Executes the compiler pipeline on the given source.
     */
    fun execute(source: String, filePath: String): CompilationResult {
        logger.debug("→ pipeline start: $filePath")
        
        val runtimeProvider = CRuntimeProvider()

        // 1. Lexing & Parsing
        logger.debug("  → lexing & parsing")
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens, filePath)
        val (program, parseErrors) = parser.parseProgram()
        
        if (parseErrors.any { it.severity == DiagnosticSeverity.ERROR }) {
            return CompilationResult(null, parseErrors)
        }

        // 2. Type Checking
        logger.debug("  → type checking")
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
            logger.debug("  → tree shaking")
            val shaker = TreeShaker(typeChecker.importedModulePrograms)
            shaker.shake(program)
        } else {
            logger.debug("  → tree shaking skipped (O0)")
        }

        typeChecker.importedModuleTypeCheckers.values.forEach { it.reportUnusedExports() }

        val allDiagnostics = mutableListOf<Diagnostic>()
        allDiagnostics.addAll(parseErrors)
        allDiagnostics.addAll(typeErrors)
        typeChecker.importedModuleTypeCheckers.values.forEach { allDiagnostics.addAll(it.errors) }

        if (allDiagnostics.any { it.severity == DiagnosticSeverity.WARNING }) {
            logger.warn("frontend produced warnings")
        }

        // 4. Code Generation
        logger.debug("  → code generation")
        val allExpressionTypes = expressionTypes.toMutableMap()
        val allResolvedSymbols = resolvedSymbols.toMutableMap()
        val allResolvedIsTypes = typeChecker.resolvedIsTypes.toMutableMap()
        
        typeChecker.importedModuleTypeCheckers.values.forEach {
            allExpressionTypes.putAll(it.expressionTypes)
            allResolvedSymbols.putAll(it.resolvedSymbols)
            allResolvedIsTypes.putAll(it.resolvedIsTypes)
        }

        val gc = when (config.gc) {
            GCType.BOEHM -> BoehmGC
            GCType.NONE -> NoGC
        }

        return try {
            val codeGenerator = CBackend(
                allExpressionTypes,
                allResolvedSymbols,
                allResolvedIsTypes,
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
