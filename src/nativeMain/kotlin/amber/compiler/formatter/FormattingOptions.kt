package amber.compiler.formatter

data class FormattingOptions(
    val indentSize: Int = 4,
    val insertFinalNewline: Boolean = true,
    val trimTrailingWhitespace: Boolean = true
)
