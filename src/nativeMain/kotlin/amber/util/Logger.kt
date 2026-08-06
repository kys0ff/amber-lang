package amber.util

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getenv

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    NONE
}

interface Logger {
    fun log(level: LogLevel, message: String)
    fun debug(message: String) = log(LogLevel.DEBUG, message)
    fun info(message: String) = log(LogLevel.INFO, message)
    fun warn(message: String) = log(LogLevel.WARN, message)
    fun error(message: String) = log(LogLevel.ERROR, message)
}

class ConsoleLogger(private val minLevel: LogLevel = LogLevel.INFO) : Logger {
    @OptIn(ExperimentalForeignApi::class)
    private val useColor = getenv("NO_COLOR") == null

    override fun log(level: LogLevel, message: String) {
        if (level.ordinal < minLevel.ordinal) return

        val prefix = when (level) {
            LogLevel.DEBUG -> colorize("[DEBUG]", DIM)
            LogLevel.INFO -> colorize("[INFO]", CYAN)
            LogLevel.WARN -> colorize("[WARN]", YELLOW)
            LogLevel.ERROR -> colorize("[ERROR]", RED)
            LogLevel.NONE -> ""
        }
        
        if (prefix.isNotEmpty()) {
            println("$prefix $message")
        } else {
            println(message)
        }
    }

    private fun colorize(text: String, code: String): String =
        if (useColor) "$code$text$RESET" else text

    private companion object {
        const val RESET = "\u001B[0m"
        const val RED = "\u001B[31m"
        const val YELLOW = "\u001B[33m"
        const val CYAN = "\u001B[36m"
        const val DIM = "\u001B[2m"
    }
}

object NoLogger : Logger {
    override fun log(level: LogLevel, message: String) {}
}
