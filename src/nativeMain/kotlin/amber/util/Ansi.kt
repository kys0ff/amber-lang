package amber.util

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getenv

/**
 * Minimal ANSI helper. Respects NO_COLOR (https://no-color.org) and --no-color.
 */
object Ansi {
    @OptIn(ExperimentalForeignApi::class)
    private val envEnabled = getenv("NO_COLOR") == null
    private var forcedDisabled = false

    val enabled: Boolean get() = envEnabled && !forcedDisabled

    fun forceDisable() {
        forcedDisabled = true
    }

    private fun wrap(code: String, text: String) = if (enabled) "$code$text\u001B[0m" else text

    fun bold(t: String) = wrap("\u001B[1m", t)
    fun dim(t: String) = wrap("\u001B[2m", t)
    fun red(t: String) = wrap("\u001B[31m", t)
    fun green(t: String) = wrap("\u001B[32m", t)
    fun yellow(t: String) = wrap("\u001B[33m", t)
    fun blue(t: String) = wrap("\u001B[34m", t)
    fun cyan(t: String) = wrap("\u001B[36m", t)
}
