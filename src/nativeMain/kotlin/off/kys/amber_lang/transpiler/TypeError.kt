package off.kys.amber_lang.transpiler

data class TypeError(
    override val filePath: String,
    override val line: Int,
    override val column: Int,
    override val message: String,
    override val length: Int? = null,
    override val caretLabel: String? = null,
    override val suggestion: String? = null,
    override val severity: Severity = Severity.ERROR
) : Diagnostic {
    override val type: String = "Type Error"
    override fun toString(): String =
        "$filePath:$line:$column: type error: $message${suggestion?.let { ". $it" } ?: ""}"
}
