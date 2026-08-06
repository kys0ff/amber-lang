package amber.util

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

class ConsoleLogger(
    private val minLevel: LogLevel = LogLevel.INFO,
    useColor: Boolean = true
) : Logger {

    init {
        if (!useColor) Ansi.forceDisable()
    }

    override fun log(level: LogLevel, message: String) {
        if (level.ordinal < minLevel.ordinal) return

        val prefix = when (level) {
            LogLevel.DEBUG -> Ansi.dim("[DEBUG]")
            LogLevel.INFO -> Ansi.cyan("[INFO]")
            LogLevel.WARN -> Ansi.yellow("[WARN]")
            LogLevel.ERROR -> Ansi.red("[ERROR]")
            LogLevel.NONE -> ""
        }
        
        if (prefix.isNotEmpty()) {
            println("$prefix $message")
        } else {
            println(message)
        }
    }
}

object NoLogger : Logger {
    override fun log(level: LogLevel, message: String) {}
}
