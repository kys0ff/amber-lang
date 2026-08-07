package amber.compiler.formatter

import amber.util.isDirectory
import amber.util.joinPaths
import amber.util.listFiles
import amber.util.readFile
import amber.util.writeFile

data class FormattingSummary(
    val filesFormatted: Int,
    val filesUnchanged: Int,
    val filesSkipped: Int,
    val filesWithErrors: Int
)

class ProjectFormatter(options: FormattingOptions = FormattingOptions()) {
    private val formatter = Formatter(options)
    
    private val ignoredDirectories = setOf(".build", "build", "out", ".git", ".gradle", ".kotlin", "gradle")

    fun formatProject(path: String): FormattingSummary {
        var formatted = 0
        var unchanged = 0
        var skipped = 0
        var errors = 0
        
        fun walk(currentPath: String) {
            val items = listFiles(currentPath)
            for (item in items) {
                val fullPath = joinPaths(currentPath, item)
                if (isDirectory(fullPath)) {
                    if (item in ignoredDirectories) {
                        skipped++
                        continue
                    }
                    walk(fullPath)
                } else if (item.endsWith(".amb")) {
                    val result = formatFile(fullPath)
                    when (result) {
                        FileFormattingResult.Formatted -> formatted++
                        FileFormattingResult.Unchanged -> unchanged++
                        FileFormattingResult.Error -> errors++
                    }
                }
            }
        }
        
        walk(path)
        
        return FormattingSummary(formatted, unchanged, skipped, errors)
    }

    fun formatFile(filePath: String): FileFormattingResult {
        val original = readFile(filePath)
        if (original.isEmpty()) return FileFormattingResult.Unchanged
        
        val formatted = try {
            formatter.format(original, filePath)
        } catch (_: Exception) {
            return FileFormattingResult.Error
        }
        
        if (formatted == original) {
            return FileFormattingResult.Unchanged
        }
        
        writeFile(filePath, formatted)
        return FileFormattingResult.Formatted
    }

    enum class FileFormattingResult {
        Formatted, Unchanged, Error
    }
}
