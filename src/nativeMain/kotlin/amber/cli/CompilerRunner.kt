package amber.cli

import amber.compiler.CompilerConfig
import amber.compiler.Transpiler
import amber.compiler.backend.NativeCompileResult
import amber.compiler.backend.tinycc.TccCompiler
import amber.compiler.diagnostic.DiagnosticFormatter
import amber.compiler.diagnostic.DiagnosticSeverity
import amber.util.Ansi
import amber.util.ensureDirectoryExists
import amber.util.fileExists
import amber.util.joinPaths
import amber.util.readFile
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.system
import kotlin.system.exitProcess
import kotlin.time.TimeSource

class CompilerRunner(private val config: CompilerConfig) {

    private data class Context(
        val config: CompilerConfig,
        val scriptPath: String,
        val projectRoot: String,
        val isProject: Boolean,
        val projectInfo: ProjectConfig
    )

    @OptIn(ExperimentalForeignApi::class)
    fun run(scriptArgs: List<String>) {
        val timeSource = if (config.benchmark) TimeSource.Monotonic else null
        val totalStartTime = timeSource?.markNow()

        val projectLoader = ProjectLoader()
        val context = when (val projectResult = projectLoader.load(config.entryFile)) {
            is ProjectFileResult.Failure -> {
                printError("invalid project or file: ${projectResult.errors.joinToString(", ")}")
                exitProcess(1)
            }

            is ProjectFileResult.Success -> {
                val updatedConfig = config.copy(
                    projectRoot = projectResult.projectRoot,
                    entryFile = projectResult.entryPath,
                    isProject = projectResult.projectRoot != "."
                )
                Context(
                    updatedConfig,
                    projectResult.entryPath,
                    projectResult.projectRoot,
                    updatedConfig.isProject,
                    projectResult.config
                )
            }
        }

        val finalConfig = context.config
        val projectRoot = context.projectRoot
        val scriptPath = context.scriptPath
        val projectInfo = context.projectInfo

        if (finalConfig.verbose) {
            println(Ansi.dim("→ target      : ${config.entryFile}"))
            println(Ansi.dim("→ project root: $projectRoot"))
            println(Ansi.dim("→ entry file  : $scriptPath"))
        }

        val buildDir = joinPaths(projectRoot, ".build")

        val src = readFile(scriptPath)
        if (src.isEmpty() && !fileExists(scriptPath)) {
            printError("could not read file at $scriptPath")
            exitProcess(1)
        }

        val transpiler = Transpiler(finalConfig)
        val frontendStartTime = timeSource?.markNow()
        val result = transpiler.transpile(src, scriptPath)
        val frontendDuration = frontendStartTime?.elapsedNow()

        if (result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            println(Ansi.red(Ansi.bold("✗ compilation failed")) + Ansi.dim(" in $projectRoot") + "\n")
            val formatter = DiagnosticFormatter(projectRoot, useColor = finalConfig.useColor)
            result.diagnostics.forEach { println(formatter.format(it)) }
            exitProcess(1)
        }

        if (result.diagnostics.isNotEmpty()) {
            val formatter = DiagnosticFormatter(projectRoot, useColor = finalConfig.useColor)
            result.diagnostics.forEach { println(formatter.format(it)) }
        }

        val code = result.code ?: return

        if (finalConfig.emitC != null) {
            writeTextFile(finalConfig.emitC, code)
        }

        if (finalConfig.checkOnly) {
            if (!finalConfig.quiet) println(Ansi.green(Ansi.bold("✓ check passed")) + Ansi.dim(" in $projectRoot"))
            return
        }

        ensureDirectoryExists(buildDir)
        val artifactBaseName = finalConfig.outputName ?: projectInfo.name
        val exePath = joinPaths(buildDir, artifactBaseName)

        val runtimeRoot = if (fileExists(
                joinPaths(
                    config.executableDir,
                    "runtime"
                )
            )
        ) joinPaths(
            config.executableDir,
            "runtime"
        ) else "/home/kys0adam/IdeaProjects/amber-lang/runtime"

        val tccCompiler = TccCompiler(runtimeRoot)
        val nativeCompileStartTime = timeSource?.markNow()
        val compileResult = tccCompiler.compile(code, exePath, finalConfig)
        val nativeCompileDuration = nativeCompileStartTime?.elapsedNow()

        when (compileResult) {
            is NativeCompileResult.Success -> {
                if (!finalConfig.quiet) {
                    println(
                        Ansi.green(Ansi.bold("✓")) + " compiled native " + Ansi.bold(
                            artifactBaseName
                        ) + Ansi.dim(" -> $exePath")
                    )
                }

                if (finalConfig.runAfterBuild) {
                    val executionStartTime = timeSource?.markNow()
                    executeNativeBinary(exePath, scriptArgs)
                    val executionDuration = executionStartTime?.elapsedNow()

                    if (config.benchmark) {
                        printBenchmark(
                            frontendDuration,
                            nativeCompileDuration,
                            executionDuration,
                            totalStartTime?.elapsedNow()
                        )
                    }
                }
            }

            is NativeCompileResult.Failure -> {
                printError("native compilation failed: ${compileResult.message}")
                exitProcess(1)
            }
        }
    }

    private fun printError(message: String) {
        println(Ansi.red(Ansi.bold("error:")) + " $message")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeTextFile(path: String, content: String) {
        val file = fopen(path, "w") ?: return
        fputs(content, file)
        fclose(file)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun executeNativeBinary(path: String, scriptArgs: List<String>) {
        val escapedArgs =
            scriptArgs.joinToString(" ") { arg -> "'" + arg.replace("'", "'\\''") + "'" }
        val command = "$path $escapedArgs"
        val exitCode = system(command)
        if (exitCode != 0) exitProcess(exitCode)
    }

    private fun printBenchmark(
        frontend: kotlin.time.Duration?,
        native: kotlin.time.Duration?,
        execution: kotlin.time.Duration?,
        total: kotlin.time.Duration?
    ) {
        println("\n" + Ansi.cyan(Ansi.bold("--- Benchmark Information ---")))
        println("Front-end Time     : ${frontend?.inWholeMilliseconds}ms")
        println("Native Compile Time: ${native?.inWholeMilliseconds}ms")
        println("Execution Time     : ${execution?.inWholeMilliseconds}ms")
        println("Total Pipeline Time: ${total?.inWholeMilliseconds}ms")
        println(Ansi.cyan(Ansi.bold("-----------------------------")))
    }
}
