package off.kys.amber_lang

import kotlinx.cinterop.ExperimentalForeignApi
import off.kys.amber_lang.transpiler.DiagnosticFormatter
import off.kys.amber_lang.transpiler.Severity
import off.kys.amber_lang.transpiler.Transpiler
import off.kys.amber_lang.transpiler.backend.TccCompiler
import off.kys.amber_lang.transpiler.backend.TccResult
import off.kys.amber_lang.utils.fileExists
import off.kys.amber_lang.utils.getExecutableDirectory
import off.kys.amber_lang.utils.getPathParent
import off.kys.amber_lang.utils.isDirectory
import off.kys.amber_lang.utils.joinPaths
import off.kys.amber_lang.utils.normalizePath
import off.kys.amber_lang.utils.readFile
import platform.posix.S_IROTH
import platform.posix.S_IRWXG
import platform.posix.S_IRWXU
import platform.posix.S_IXOTH
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.system
import kotlin.system.exitProcess
import kotlin.time.TimeSource

private const val LIBS_PATH = "libs"

data class ProjectConfig(
    val name: String,
    val version: String,
    val entry: String
)

/**
 * Minimal ANSI helper. Respects NO_COLOR (https://no-color.org).
 */
private object Ansi {
    @OptIn(ExperimentalForeignApi::class)
    val enabled = getenv("NO_COLOR") == null

    private fun wrap(code: String, text: String) = if (enabled) "$code$text\u001B[0m" else text

    fun bold(t: String) = wrap("\u001B[1m", t)
    fun dim(t: String) = wrap("\u001B[2m", t)
    fun red(t: String) = wrap("\u001B[31m", t)
    fun green(t: String) = wrap("\u001B[32m", t)
    fun yellow(t: String) = wrap("\u001B[33m", t)
    fun cyan(t: String) = wrap("\u001B[36m", t)
}

private val PROJECT_NAME_REGEX = Regex("^[A-Za-z0-9_-]+$")

private val KNOWN_FLAGS = setOf(
    "--help", "-h",
    "--version", "-v",
    "--benchmark", "-b",
    "--run", "-r",
    "--no-run",
    "--quiet", "-q"
)

sealed class ProjectFileResult {
    data class Success(val config: ProjectConfig) : ProjectFileResult()
    data class Failure(val errors: List<String>) : ProjectFileResult()
}

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    // Separate transpiler flags from the executed script arguments using '--'
    val dashDashIndex = args.indexOf("--")
    val compilerArgs = if (dashDashIndex != -1) args.slice(0 until dashDashIndex) else args.toList()
    val scriptArgs = if (dashDashIndex != -1) args.slice(dashDashIndex + 1 until args.size) else emptyList()

    if (compilerArgs.contains("--help") || compilerArgs.contains("-h")) {
        printHelp()
        return
    }

    if (compilerArgs.contains("--version") || compilerArgs.contains("-v")) {
        println("Amber Transpiler v0.1.0")
        return
    }

    warnAboutUnknownFlags(compilerArgs)

    val isBenchmark = compilerArgs.contains("--benchmark") || compilerArgs.contains("-b")
    val isQuiet = compilerArgs.contains("--quiet") || compilerArgs.contains("-q")
    val timeSource = if (isBenchmark) TimeSource.Monotonic else null
    val totalStartTime = timeSource?.markNow()

    val target = compilerArgs.firstOrNull { !it.startsWith("-") } ?: "."
    val absoluteTarget = normalizePath(target)

    var isProject = false
    var projectConfig: ProjectConfig? = null

    val targetBaseName = absoluteTarget.substringAfterLast('/').substringAfterLast('\\')
    val isExplicitProjectFile = !isDirectory(absoluteTarget) && targetBaseName == "project"

    val (scriptPath, projectRoot) = if (isDirectory(absoluteTarget) || isExplicitProjectFile) {
        val currentProjectRoot = if (isDirectory(absoluteTarget)) absoluteTarget else (getPathParent(absoluteTarget) ?: ".")
        val projectFilePath = joinPaths(currentProjectRoot, "project")
        
        if (!fileExists(projectFilePath)) {
            printCliError("no 'project' file found in $currentProjectRoot")
            println(Ansi.dim("  hint: run inside a directory containing a 'project' file, or pass a .amb file directly"))
            exitProcess(1)
        }

        isProject = true
        val config = when (val result = parseProjectFile(readFile(projectFilePath))) {
            is ProjectFileResult.Failure -> {
                println(Ansi.red(Ansi.bold("error:")) + " invalid project file at $projectFilePath")
                result.errors.forEach { println("  ${Ansi.dim("-")} $it") }
                exitProcess(1)
            }
            is ProjectFileResult.Success -> result.config
        }
        projectConfig = config

        val entryPath = normalizePath(joinPaths(currentProjectRoot, config.entry))
        if (!fileExists(entryPath)) {
            printCliError("entry file '${config.entry}' not found in $currentProjectRoot")
            exitProcess(1)
        }
        Pair(entryPath, currentProjectRoot)
    } else {
        if (!fileExists(absoluteTarget)) {
            printCliError("file or directory not found: $target")
            exitProcess(1)
        }
        Pair(absoluteTarget, getPathParent(absoluteTarget) ?: ".")
    }

    val src = readFile(scriptPath)
    if (src.isEmpty() && !fileExists(scriptPath)) {
        printCliError("could not read file at $scriptPath")
        exitProcess(1)
    }

    val executableDir = getExecutableDirectory()

    val transpileStartTime = timeSource?.markNow()
    val transpiler = Transpiler(src, scriptPath, projectRoot, isProject, executableDir)
    val result = transpiler.transpile()
    val transpileDuration = transpileStartTime?.elapsedNow()

    val hasErrors = result.errors.any { it.severity == Severity.ERROR }

    if (hasErrors) {
        println(Ansi.red(Ansi.bold("✗ transpilation failed")) + Ansi.dim(" in $projectRoot") + "\n")
        val formatter = DiagnosticFormatter(projectRoot)
        result.errors.forEach {
            println(formatter.format(it))
        }
        exitProcess(1)
    }

    if (result.errors.isNotEmpty()) {
        val formatter = DiagnosticFormatter(projectRoot)
        result.errors.forEach {
            println(formatter.format(it))
        }
    }

    val code = result.code
    if (code != null) {
        val buildDir = joinPaths(projectRoot, ".build")
        ensureDirectoryExists(buildDir)

        val artifactBaseName = projectConfig?.name
            ?: sanitizeFileName(baseNameWithoutExtension(scriptPath))
        
        val shouldRun = compilerArgs.contains("--run") || compilerArgs.contains("-r") || !compilerArgs.any { it == "--no-run" }

        val exePath = joinPaths(buildDir, artifactBaseName)
        
        var libsRoot = normalizePath(joinPaths(executableDir, LIBS_PATH))
        if (!isDirectory(libsRoot)) {
            // Fallback for development: check project root
            libsRoot = "/home/kys0adam/IdeaProjects/amber-lang/libs"
        }
        
        val tccCompiler = TccCompiler(libsRoot)
        when (val compileResult = tccCompiler.compile(code, exePath)) {
            is TccResult.Success -> {
                if (!isQuiet) {
                    val label = projectConfig?.let { "${it.name} v${it.version}" } ?: artifactBaseName
                    println(Ansi.green(Ansi.bold("✓")) + " compiled native " + Ansi.bold(label) + Ansi.dim(" -> $exePath"))
                }
                
                if (shouldRun) {
                    val executionStartTime = timeSource?.markNow()
                    executeNativeBinary(exePath, scriptArgs)
                    val executionDuration = executionStartTime?.elapsedNow()

                    if (isBenchmark && transpileDuration != null && executionDuration != null && totalStartTime != null) {
                        println("\n" + Ansi.cyan(Ansi.bold("--- Benchmark Information ---")))
                        println("Transpilation Time : ${transpileDuration.inWholeMilliseconds}ms")
                        println("Execution Time     : ${executionDuration.inWholeMilliseconds}ms")
                        println("Total Pipeline Time: ${totalStartTime.elapsedNow().inWholeMilliseconds}ms")
                        println(Ansi.cyan(Ansi.bold("-----------------------------")))
                    }
                }
            }
            is TccResult.Failure -> {
                printCliError("native compilation failed: ${compileResult.message}")
                exitProcess(1)
            }
        }
    } else {
        printCliError("transpiler returned no code and no errors.")
        exitProcess(1)
    }
}

/**
 * Parses a 'project' file into a validated [ProjectConfig].
 *
 * Rules enforced:
 *  - Every non-empty line must be a 'key = value' pair (comments start with '//').
 *  - 'name' is required and must only contain letters, digits, '-' and '_'
 *    (it is used directly as the generated binary's file name).
 *  - 'entry', if given, must point to a '.amb' file.
 *  - Unknown keys and empty values are reported as errors.
 *  - 'version' is optional and defaults to "0.0.1".
 */
fun parseProjectFile(content: String): ProjectFileResult {
    var name: String? = null
    var version: String? = null
    var entry: String? = null
    val errors = mutableListOf<String>()

    content.lines().forEachIndexed { index, rawLine ->
        val cleanLine = rawLine.split("//")[0].trim()
        if (cleanLine.isEmpty()) return@forEachIndexed

        val eqIndex = cleanLine.indexOf('=')
        if (eqIndex == -1) {
            errors += "line ${index + 1}: expected 'key = value', got '$cleanLine'"
            return@forEachIndexed
        }

        val key = cleanLine.substring(0, eqIndex).trim()
        val value = cleanLine.substring(eqIndex + 1).trim().removeSurrounding("\"")

        if (value.isEmpty()) {
            errors += "line ${index + 1}: '$key' has an empty value"
            return@forEachIndexed
        }

        when (key) {
            "name" -> name = value
            "version" -> version = value
            "entry" -> entry = value
            else -> errors += "line ${index + 1}: unknown project key '$key'"
        }
    }

    val resolvedName = name
    if (resolvedName == null) {
        errors += "missing required field 'name'"
    } else if (!PROJECT_NAME_REGEX.matches(resolvedName)) {
        errors += "'name' must contain only letters, numbers, '-' and '_' (got '$resolvedName')"
    }

    val resolvedEntry = entry
    if (resolvedEntry != null && !resolvedEntry.endsWith(".amb")) {
        errors += "'entry' must point to a '.amb' file (got '$resolvedEntry')"
    }

    if (errors.isNotEmpty()) return ProjectFileResult.Failure(errors)

    return ProjectFileResult.Success(
        ProjectConfig(
            name = resolvedName!!,
            version = version ?: "0.0.1",
            entry = resolvedEntry ?: "main.amb"
        )
    )
}

private fun warnAboutUnknownFlags(compilerArgs: List<String>) {
    val unknown = compilerArgs.filter { it.startsWith("-") && it !in KNOWN_FLAGS }
    unknown.forEach {
        println(Ansi.yellow("warning:") + " unknown flag '$it' ignored (see --help)")
    }
}

private fun printCliError(message: String) {
    println(Ansi.red(Ansi.bold("error:")) + " $message")
}

private fun sanitizeFileName(name: String): String {
    val cleaned = name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    return cleaned.ifEmpty { "output" }
}

private fun baseNameWithoutExtension(path: String): String {
    val fileName = path.substringAfterLast('/').substringAfterLast('\\')
    return fileName.substringBeforeLast('.', fileName)
}

fun printHelp() {
    println(
        """
        ${Ansi.bold("Amber Compiler CLI")}
        ${Ansi.dim("Usage:")} amber [options] [path] [-- script_args]

        ${Ansi.bold("path")}
          Path to a .amb script, or a directory containing a 'project' file.
          Defaults to current directory.

        ${Ansi.bold("options")}
          -h, --help       Show this help message
          -r, --run        Run the generated native executable immediately (default)
          --no-run         Only output the generated native executable
          -b, --benchmark  Measure execution and compilation durations
          -q, --quiet      Suppress the build summary line
          -v, --version    Show version information
          --               Separator indicating all subsequent flags belong to the script

        ${Ansi.bold("project file")}
          A directory can be built as a project if it contains a file named 'project'
          with 'key = value' lines (comments start with //):

            name    = myapp        ${Ansi.dim("(required, used as the built binary's name)")}
            version = 0.1.0        ${Ansi.dim("(optional, defaults to 0.0.1)")}
            entry   = main.amb     ${Ansi.dim("(optional, defaults to main.amb)")}

          The generated binary is written to .build/<name>

        ${Ansi.bold("examples")}
          amber                        ${Ansi.dim("# build & run the project in the current directory")}
          amber ./myproject            ${Ansi.dim("# build & run a project directory")}
          amber script.amb             ${Ansi.dim("# build & run a single script")}
          amber script.amb -- --flag   ${Ansi.dim("# forward '--flag' to the running script")}
        """.trimIndent()
    )
}

@OptIn(ExperimentalForeignApi::class)
fun ensureDirectoryExists(path: String) {
    if (!isDirectory(path)) {
        mkdir(path, (S_IRWXU or S_IRWXG or S_IROTH or S_IXOTH).toUInt())
    }
}

@OptIn(ExperimentalForeignApi::class)
fun executeNativeBinary(path: String, scriptArgs: List<String>) {
    val escapedArgs = scriptArgs.joinToString(" ") { arg ->
        "'" + arg.replace("'", "'\\''") + "'"
    }
    val command = "$path $escapedArgs"
    val exitCode = system(command)
    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}
