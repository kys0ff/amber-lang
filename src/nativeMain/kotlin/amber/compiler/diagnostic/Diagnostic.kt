package amber.compiler.diagnostic

/**
 * Severity level of a diagnostic.
 */
enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    NOTE,
    HINT
}

/**
 * Interface for all diagnostics (errors, warnings) in the Amber compiler.
 */
sealed interface Diagnostic {
    val filePath: String
    val line: Int
    val column: Int
    val message: String
    val type: String
    val length: Int?
    val caretLabel: String?
    val suggestion: String?
    val severity: DiagnosticSeverity
}

data class SyntaxError(
    override val filePath: String,
    override val line: Int,
    override val column: Int,
    override val message: String,
    override val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR
) : Diagnostic {
    override val type: String = "Syntax Error"
    override val length: Int? = null
    override val caretLabel: String? = null
    override val suggestion: String? = null
}

data class TypeError(
    override val filePath: String,
    override val line: Int,
    override val column: Int,
    override val message: String,
    override val length: Int? = null,
    override val caretLabel: String? = null,
    override val suggestion: String? = null,
    override val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR
) : Diagnostic {
    override val type: String = "Type Error"
}

data class GenericDiagnostic(
    override val filePath: String,
    override val line: Int,
    override val column: Int,
    override val message: String,
    override val type: String = "Error",
    override val length: Int? = null,
    override val caretLabel: String? = null,
    override val suggestion: String? = null,
    override val severity: DiagnosticSeverity = DiagnosticSeverity.ERROR
) : Diagnostic
