package amber.compiler.backend.c

class CodeWriter {
    private val builder = StringBuilder()
    private var indentLevel = 0

    fun indent() { indentLevel++ }
    fun dedent() { indentLevel-- }

    fun write(text: String) {
        builder.append(text)
    }

    fun writeLine(text: String = "") {
        if (text.isNotEmpty()) {
            builder.append("    ".repeat(indentLevel))
            builder.append(text)
        }
        builder.append("\n")
    }

    override fun toString(): String = builder.toString()
}
