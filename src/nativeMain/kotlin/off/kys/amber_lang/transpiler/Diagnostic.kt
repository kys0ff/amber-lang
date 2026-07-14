package off.kys.amber_lang.transpiler

/**
 * Severity level of a diagnostic.
 */
enum class Severity {
    ERROR,
    WARNING
}

/**
 * Interface for all diagnostics (errors, warnings) in the Amber compiler.
 */
interface Diagnostic {
    val filePath: String
    val line: Int
    val column: Int
    val message: String
    val type: String
    val length: Int?
    val caretLabel: String?
    val suggestion: String?
    val severity: Severity
}

/**
 * Generic implementation for diagnostics that don't fit into syntax or type errors.
 */
data class GenericDiagnostic(
    override val filePath: String,
    override val line: Int,
    override val column: Int,
    override val message: String,
    override val type: String = "Error",
    override val length: Int? = null,
    override val caretLabel: String? = null,
    override val suggestion: String? = null,
    override val severity: Severity = Severity.ERROR
) : Diagnostic
