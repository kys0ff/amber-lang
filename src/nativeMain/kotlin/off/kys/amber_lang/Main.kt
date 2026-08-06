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
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.system
import kotlin.system.exitProcess
import kotlin.time.TimeSource

private const val LIBS_PATH = "libs"
private const val VERSION = "0.1.0"

data class ProjectConfig(
    val name: String,
    val version: String,
    val entry: String
)

/**
 * Minimal ANSI helper. Respects NO_COLOR (https://no-color.org) and --no-color.
 */
private object Ansi {
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
    fun cyan(t: String) = wrap("\u001B[36m", t)
}

private val PROJECT_NAME_REGEX = Regex("^[A-Za-z0-9_-]+$")

// Boolean flags that take no value.
private val KNOWN_BOOL_FLAGS = setOf(
    "--help", "-h",
    "--version", "-v",
    "--benchmark", "-b",
    "--run", "-r",
    "--no-run",
    "--quiet", "-q",
    "--check", "-c",
    "--clean",
    "--verbose", "-V",
    "--no-color"
)

// Flags that consume the next token as their value.
private val VALUE_FLAGS = setOf("--output", "-o", "--emit-c")

sealed class ProjectFileResult {
    data class Success(val config: ProjectConfig) : ProjectFileResult()
    data class Failure(val errors: List<String>) : ProjectFileResult()
}

private data class CliOptions(
    val target: String,
    val help: Boolean,
    val version: Boolean,
    val benchmark: Boolean,
    val noRun: Boolean,
    val quiet: Boolean,
    val check: Boolean,
    val clean: Boolean,
    val verbose: Boolean,
    val noColor: Boolean,
    val output: String?,
    val emitC: String?,
    val unknownFlags: List<String>
)

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    // Separate compiler flags from the executed script arguments using '--'
    val dashDashIndex = args.indexOf("--")
    val compilerArgs = if (dashDashIndex != -1) args.slice(0 until dashDashIndex) else args.toList()
    val scriptArgs = if (dashDashIndex != -1) args.slice(dashDashIndex + 1 until args.size) else emptyList()

    val options = parseCliOptions(compilerArgs)

    if (options.noColor) Ansi.forceDisable()

    if (options.help) {
        printHelp()
        return
    }

    if (options.version) {
        println("Amber Compiler v$VERSION")
        return
    }

    options.unknownFlags.forEach {
        println(Ansi.yellow("warning:") + " unknown flag '$it' ignored (see --help)")
    }

    val isBenchmark = options.benchmark
    val isQuiet = options.quiet
    val isVerbose = options.verbose
    val timeSource = if (isBenchmark) TimeSource.Monotonic else null
    val totalStartTime = timeSource?.markNow()

    val absoluteTarget = normalizePath(options.target)

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
            printCliError("file or directory not found: ${options.target}")
            exitProcess(1)
        }
        Pair(absoluteTarget, getPathParent(absoluteTarget) ?: ".")
    }

    if (isVerbose) {
        println(Ansi.dim("→ target      : $absoluteTarget"))
        println(Ansi.dim("→ project root: $projectRoot"))
        println(Ansi.dim("→ entry file  : $scriptPath"))
        println(Ansi.dim("→ is project  : $isProject"))
    }

    val buildDir = joinPaths(projectRoot, ".build")

    if (options.clean && isDirectory(buildDir)) {
        if (isVerbose || !isQuiet) println(Ansi.dim("cleaning $buildDir"))
        removeDirectoryRecursive(buildDir)
    }

    val src = readFile(scriptPath)
    if (src.isEmpty() && !fileExists(scriptPath)) {
        printCliError("could not read file at $scriptPath")
        exitProcess(1)
    }

    val executableDir = getExecutableDirectory()

    val frontendStartTime = timeSource?.markNow()
    val compiler = Transpiler(src, scriptPath, projectRoot, isProject, executableDir)
    val result = compiler.transpile()
    val frontendDuration = frontendStartTime?.elapsedNow()

    val hasErrors = result.errors.any { it.severity == Severity.ERROR }

    if (hasErrors) {
        println(Ansi.red(Ansi.bold("✗ compilation failed")) + Ansi.dim(" in $projectRoot") + "\n")
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

    if (code == null) {
        printCliError("compiler returned no code and no errors.")
        exitProcess(1)
    }

    if (options.emitC != null) {
        val written = writeTextFile(options.emitC, code)
        if (!written) {
            printCliError("could not write generated c code to ${options.emitC}")
            exitProcess(1)
        } else if (!isQuiet) {
            println(Ansi.green(Ansi.bold("✓")) + " emitted c source " + Ansi.dim("-> ${options.emitC}"))
        }
    }

    if (options.check) {
        if (!isQuiet) println(Ansi.green(Ansi.bold("✓ check passed")) + Ansi.dim(" in $projectRoot"))
        return
    }

    ensureDirectoryExists(buildDir)

    val artifactBaseName = options.output
        ?: projectConfig?.name
        ?: sanitizeFileName(baseNameWithoutExtension(scriptPath))

    val shouldRun = !options.noRun

    val exePath = joinPaths(buildDir, artifactBaseName)

    var libsRoot = normalizePath(joinPaths(executableDir, LIBS_PATH))
    if (!isDirectory(libsRoot)) {
        // Fallback for development: check project root
        libsRoot = "/home/kys0adam/IdeaProjects/amber-lang/libs"
    }

    val tccCompiler = TccCompiler(libsRoot)
    val nativeCompileStartTime = timeSource?.markNow()
    val compileResult = tccCompiler.compile(code, exePath)
    val nativeCompileDuration = nativeCompileStartTime?.elapsedNow()

    when (compileResult) {
        is TccResult.Success -> {
            if (!isQuiet) {
                val label = projectConfig?.let { "${it.name} v${it.version}" } ?: artifactBaseName
                println(Ansi.green(Ansi.bold("✓")) + " compiled native " + Ansi.bold(label) + Ansi.dim(" -> $exePath"))
            }

            if (shouldRun) {
                val executionStartTime = timeSource?.markNow()
                executeNativeBinary(exePath, scriptArgs)
                val executionDuration = executionStartTime?.elapsedNow()

                if (isBenchmark && frontendDuration != null && nativeCompileDuration != null &&
                    executionDuration != null && totalStartTime != null
                ) {
                    println("\n" + Ansi.cyan(Ansi.bold("--- Benchmark Information ---")))
                    println("Front-end Time     : ${frontendDuration.inWholeMilliseconds}ms")
                    println("Native Compile Time: ${nativeCompileDuration.inWholeMilliseconds}ms")
                    println("Execution Time     : ${executionDuration.inWholeMilliseconds}ms")
                    println("Total Pipeline Time: ${totalStartTime.elapsedNow().inWholeMilliseconds}ms")
                    println(Ansi.cyan(Ansi.bold("-----------------------------")))
                }
            } else if (isBenchmark && frontendDuration != null && nativeCompileDuration != null && totalStartTime != null) {
                println("\n" + Ansi.cyan(Ansi.bold("--- Benchmark Information ---")))
                println("Front-end Time     : ${frontendDuration.inWholeMilliseconds}ms")
                println("Native Compile Time: ${nativeCompileDuration.inWholeMilliseconds}ms")
                println("Total Pipeline Time: ${totalStartTime.elapsedNow().inWholeMilliseconds}ms")
                println(Ansi.cyan(Ansi.bold("-----------------------------")))
            }
        }
        is TccResult.Failure -> {
            printCliError("native compilation failed: ${compileResult.message}")
            exitProcess(1)
        }
    }
}

/**
 * Parses raw CLI tokens into structured [CliOptions].
 * Unknown flags are collected rather than rejected outright, so a warning can be
 * printed once other setup (like --no-color) has already been applied.
 */
private fun parseCliOptions(compilerArgs: List<String>): CliOptions {
    var target: String? = null
    var output: String? = null
    var emitC: String? = null
    val flags = mutableSetOf<String>()
    val unknown = mutableListOf<String>()

    var i = 0
    while (i < compilerArgs.size) {
        val arg = compilerArgs[i]
        when {
            arg in VALUE_FLAGS -> {
                val value = compilerArgs.getOrNull(i + 1)
                if (value == null) {
                    printCliError("'$arg' requires a value")
                    exitProcess(1)
                }
                when (arg) {
                    "--output", "-o" -> output = value
                    "--emit-c" -> emitC = value
                }
                i++
            }
            arg.startsWith("-") -> {
                if (arg in KNOWN_BOOL_FLAGS) flags += arg else unknown += arg
            }
            else -> {
                if (target == null) target = arg
            }
        }
        i++
    }

    return CliOptions(
        target = target ?: ".",
        help = "--help" in flags || "-h" in flags,
        version = "--version" in flags || "-v" in flags,
        benchmark = "--benchmark" in flags || "-b" in flags,
        noRun = "--no-run" in flags,
        quiet = "--quiet" in flags || "-q" in flags,
        check = "--check" in flags || "-c" in flags,
        clean = "--clean" in flags,
        verbose = "--verbose" in flags || "-V" in flags,
        noColor = "--no-color" in flags,
        output = output,
        emitC = emitC,
        unknownFlags = unknown
    )
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
          -h, --help          Show this help message
          -v, --version       Show version information
          -r, --run           Run the generated native executable immediately (default)
              --no-run        Only output the generated native executable
          -c, --check         Type-check and validate only; skip native compilation
          -o, --output <name> Set the output binary name (overrides 'project' name)
              --emit-c <path> Write the generated c source to <path>
              --clean         Remove the .build directory before compiling
          -b, --benchmark     Measure front-end, native compile, and execution durations
          -V, --verbose       Print extra diagnostic information about the build
          -q, --quiet         Suppress the build summary line
              --no-color      Disable ansi color output (see also NO_COLOR)
              --               Separator indicating all subsequent flags belong to the script

        ${Ansi.bold("project file")}
          A directory can be built as a project if it contains a file named 'project'
          with 'key = value' lines (comments start with //):

            name    = myapp        ${Ansi.dim("(required, used as the built binary's name)")}
            version = 0.1.0        ${Ansi.dim("(optional, defaults to 0.0.1)")}
            entry   = main.amb     ${Ansi.dim("(optional, defaults to main.amb)")}

          The generated binary is written to .build/<name>

        ${Ansi.bold("examples")}
          amber                        ${Ansi.dim("# compile & run the project in the current directory")}
          amber ./myproject            ${Ansi.dim("# compile & run a project directory")}
          amber script.amb             ${Ansi.dim("# compile & run a single script")}
          amber script.amb -- --flag   ${Ansi.dim("# forward '--flag' to the running script")}
          amber -c script.amb          ${Ansi.dim("# only check the script, don't compile natively")}
          amber -o app --clean .       ${Ansi.dim("# clean, then compile the project to .build/app")}
          amber --emit-c out.c main.amb ${Ansi.dim("# dump the generated c source alongside the binary")}
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
private fun removeDirectoryRecursive(path: String) {
    val escaped = "'" + path.replace("'", "'\\''") + "'"
    system("rm -rf $escaped")
}

@OptIn(ExperimentalForeignApi::class)
private fun writeTextFile(path: String, content: String): Boolean {
    val file = fopen(path, "w") ?: return false
    fputs(content, file)
    fclose(file)
    return true
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