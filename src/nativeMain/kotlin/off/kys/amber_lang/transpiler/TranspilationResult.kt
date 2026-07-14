package off.kys.amber_lang.transpiler

/**
 * Represents the final output of the transpilation process.
 */
data class TranspilationResult(
    val code: String?,
    val errors: List<Diagnostic>
)