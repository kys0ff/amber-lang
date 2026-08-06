package amber.compiler.diagnostic

import amber.util.Ansi
import amber.util.makeRelative
import amber.util.readFile

/**
 * Formats compiler [Diagnostic]s into human-friendly reports.
 */
class DiagnosticFormatter(
    private val projectRoot: String,
    useColor: Boolean = false,
    private val contextLines: Int = 1,
) {

    init {
        if (!useColor) Ansi.forceDisable()
    }

    fun format(diagnostic: Diagnostic): String = buildString {
        val relativePath = makeRelative(diagnostic.filePath, projectRoot)

        appendLine(headerLine(diagnostic, relativePath))
        appendLine(Ansi.dim(diagnostic.message))

        getCodeContext(diagnostic)?.let {
            appendLine()
            appendLine(it)
        }

        diagnostic.suggestion?.let {
            appendLine()
            appendLine("  ${Ansi.bold(Ansi.cyan("suggestion:"))} $it")
        }
    }.trimEnd('\n') + "\n"

    private fun headerLine(diagnostic: Diagnostic, relativePath: String): String {
        val location = "$relativePath:${diagnostic.line}:${diagnostic.column}"
        return when (diagnostic.severity) {
            DiagnosticSeverity.ERROR -> "${Ansi.bold(location)} → ${Ansi.bold(Ansi.red(diagnostic.type))}"
            DiagnosticSeverity.WARNING -> "${Ansi.bold(location)} → ${Ansi.bold(Ansi.yellow(diagnostic.type))}"
            DiagnosticSeverity.NOTE -> "${Ansi.bold(location)} → ${Ansi.bold(Ansi.blue(diagnostic.type))}"
            DiagnosticSeverity.HINT -> "${Ansi.bold(location)} → ${Ansi.bold(Ansi.cyan(diagnostic.type))}"
        }
    }

    private fun getCodeContext(diagnostic: Diagnostic): String? {
        val content = readFile(diagnostic.filePath)
        if (content.isEmpty()) return null

        val lines = content.lines()
        val line = diagnostic.line
        if (line <= 0 || line > lines.size) return null

        val firstShown = (line - contextLines).coerceAtLeast(1)
        val lastShown = (line + contextLines).coerceAtMost(lines.size)
        val gutterWidth = lastShown.toString().length

        return buildString {
            for (lineNo in firstShown..lastShown) {
                appendLine(sourceLine(lineNo, lines[lineNo - 1], gutterWidth, isOffending = lineNo == line))
                if (lineNo == line) {
                    appendLine(caretLine(gutterWidth, diagnostic))
                }
            }
        }.trimEnd('\n')
    }

    private fun sourceLine(number: Int, text: String, gutterWidth: Int, isOffending: Boolean): String {
        val numStr = number.toString().padStart(gutterWidth)
        val gutter = if (isOffending) Ansi.bold("$numStr |") else Ansi.dim("$numStr |")
        return "  $gutter  $text"
    }

    private fun caretLine(gutterWidth: Int, diagnostic: Diagnostic): String {
        val gutter = Ansi.dim(" ".repeat(gutterWidth) + " |")
        val leadingSpaces = " ".repeat((diagnostic.column - 1).coerceAtLeast(0))
        val caretWidth = (diagnostic.length ?: 1).coerceAtLeast(1)
        val caret = "^".repeat(caretWidth)
        val coloredCaret = when (diagnostic.severity) {
            DiagnosticSeverity.ERROR -> Ansi.bold(Ansi.red(caret))
            DiagnosticSeverity.WARNING -> Ansi.bold(Ansi.yellow(caret))
            DiagnosticSeverity.NOTE -> Ansi.bold(Ansi.blue(caret))
            DiagnosticSeverity.HINT -> Ansi.bold(Ansi.cyan(caret))
        }
        val hint = diagnostic.caretLabel?.let { " $it" } ?: ""
        return "  $gutter  $leadingSpaces$coloredCaret$hint"
    }
}
