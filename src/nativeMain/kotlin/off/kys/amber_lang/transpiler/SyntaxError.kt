package off.kys.amber_lang.transpiler

/**
 * Structured syntax error for Go-style reporting.
 */
data class SyntaxError(
    override val filePath: String,
    override val line: Int,
    override val column: Int,
    override val message: String,
    override val severity: Severity = Severity.ERROR
) : Diagnostic {
    override val type: String = "Syntax Error"
    override val length: Int? = null
    override val caretLabel: String? = null
    override val suggestion: String? = null
    override fun toString(): String = "$filePath:$line:$column: syntax error: $message"
}
