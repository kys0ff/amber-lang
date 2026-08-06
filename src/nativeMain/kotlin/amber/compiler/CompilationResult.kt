package amber.compiler

import amber.compiler.diagnostic.Diagnostic

data class CompilationResult(
    val code: String?,
    val diagnostics: List<Diagnostic>
)
