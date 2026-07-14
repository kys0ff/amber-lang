package off.kys.amber_lang.transpiler

import off.kys.amber_lang.utils.makeRelative
import off.kys.amber_lang.utils.readFile

/**
 * Formats compiler [Diagnostic]s into rustc/elm-style, human-friendly reports:
 *
 * ```
 * src/main.amber:12:5 → TypeMismatch
 * Expected type 'Int' but found 'String'
 *
 *   11 |  let x: Int = 5
 *   12 |  let y: Int = "hello"
 *      |               ^^^^^^^ here
 *   13 |  print(y)
 *
 *   suggestion: convert the value with `.toInt()` or change the declared type
 * ```
 */
class DiagnosticFormatter(
    private val projectRoot: String,
    private val useColor: Boolean = false,
    private val contextLines: Int = 1,
) {

    fun format(diagnostic: Diagnostic): String = buildString {
        val relativePath = makeRelative(diagnostic.filePath, projectRoot)

        appendLine(headerLine(diagnostic, relativePath))
        appendLine(colorize(diagnostic.message, DIM))

        getCodeContext(diagnostic)?.let {
            appendLine()
            appendLine(it)
        }

        diagnostic.suggestion?.let {
            appendLine()
            appendLine("  ${colorize("suggestion:", CYAN + BOLD)} $it")
        }
    }.trimEnd('\n') + "\n"

    private fun headerLine(diagnostic: Diagnostic, relativePath: String): String {
        val location = "$relativePath:${diagnostic.line}:${diagnostic.column}"
        val severityColor = when (diagnostic.severity) {
            Severity.ERROR -> RED
            Severity.WARNING -> YELLOW
        }
        return "${colorize(location, BOLD)} → ${colorize(diagnostic.type, severityColor + BOLD)}"
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
        val gutter = colorize("$numStr |", if (isOffending) BOLD else DIM)
        return "  $gutter  $text"
    }

    private fun caretLine(gutterWidth: Int, diagnostic: Diagnostic): String {
        val gutter = colorize(" ".repeat(gutterWidth) + " |", DIM)
        val leadingSpaces = " ".repeat((diagnostic.column - 1).coerceAtLeast(0))
        val caretWidth = (diagnostic.length ?: 1).coerceAtLeast(1)
        val caret = colorize("^".repeat(caretWidth), RED + BOLD)
        val hint = diagnostic.caretLabel?.let { " $it" } ?: ""
        return "  $gutter  $leadingSpaces$caret$hint"
    }

    private fun colorize(text: String, code: String): String =
        if (useColor) "$code$text$RESET" else text

    private companion object {
        const val RESET = "\u001B[0m"
        const val BOLD = "\u001B[1m"
        const val DIM = "\u001B[2m"
        const val RED = "\u001B[31m"
        const val YELLOW = "\u001B[33m"
        const val BLUE = "\u001B[34m"
        const val CYAN = "\u001B[36m"
    }
}
