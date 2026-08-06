package amber.compiler

import amber.compiler.pipeline.CompilerPipeline

/**
 * The main entry point for transpiling Amber source code to the target backend code.
 */
class Transpiler(private val config: CompilerConfig) {
    /**
     * Transpiles the given [sourceCode] into backend code (e.g., C).
     * 
     * @param sourceCode The Amber source code to transpile.
     * @param filePath The path to the source file (used for diagnostics).
     * @return A [CompilationResult] containing the generated code and any diagnostics.
     */
    fun transpile(sourceCode: String, filePath: String): CompilationResult {
        val pipeline = CompilerPipeline(config)
        return pipeline.execute(sourceCode, filePath)
    }
}
