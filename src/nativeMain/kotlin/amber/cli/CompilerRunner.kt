package amber.cli

import amber.compiler.CompilerCommand
import amber.compiler.CompilerConfig
import amber.compiler.Transpiler
import amber.compiler.backend.NativeCompileResult
import amber.compiler.backend.NativeDiagnosticSeverity
import amber.compiler.backend.tinycc.TccCompiler
import amber.compiler.diagnostic.DiagnosticFormatter
import amber.compiler.diagnostic.DiagnosticSeverity
import amber.compiler.formatter.ProjectFormatter
import amber.util.Ansi
import amber.util.ConsoleLogger
import amber.util.LogLevel
import amber.util.Logger
import amber.util.NoLogger
import amber.util.ensureDirectoryExists
import amber.util.fileExists
import amber.util.isDirectory
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

    private val logger: Logger = if (config.quiet) NoLogger else ConsoleLogger(
        minLevel = if (config.verbose) LogLevel.DEBUG else LogLevel.INFO,
        useColor = config.useColor
    )

    init {
        if (!config.useColor) Ansi.forceDisable()
    }

    private data class Context(
        val config: CompilerConfig,
        val scriptPath: String,
        val projectRoot: String,
        val isProject: Boolean,
        val projectInfo: ProjectConfig
    )

    @OptIn(ExperimentalForeignApi::class)
    fun run(scriptArgs: List<String>) {
        if (config.command == CompilerCommand.FORMAT) {
            handleFormat()
            return
        }

        val timeSource = if (config.benchmark) TimeSource.Monotonic else null
        val totalStartTime = timeSource?.markNow()

        val projectLoader = ProjectLoader()
        val context = when (val projectResult = projectLoader.load(config.entryFile)) {
            is ProjectFileResult.Failure -> {
                logger.error("invalid project or file: ${projectResult.errors.joinToString(", ")}")
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

        logger.debug(Ansi.dim("→ target      : ${config.entryFile}"))
        logger.debug(Ansi.dim("→ project root: $projectRoot"))
        logger.debug(Ansi.dim("→ entry file  : $scriptPath"))

        val buildDir = joinPaths(projectRoot, ".build")

        val src = readFile(scriptPath)
        if (src.isEmpty() && !fileExists(scriptPath)) {
            logger.error("could not read file at $scriptPath")
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
            if (result.diagnostics.any { it.severity == DiagnosticSeverity.WARNING }) {
                logger.warn("compilation produced warnings")
            }
            val formatter = DiagnosticFormatter(projectRoot, useColor = finalConfig.useColor)
            result.diagnostics.forEach { println(formatter.format(it)) }
        }

        val code = result.code ?: return

        if (finalConfig.emitC != null) {
            writeTextFile(finalConfig.emitC, code)
        }

        if (finalConfig.checkOnly) {
            logger.info(Ansi.green(Ansi.bold("✓ check passed")) + Ansi.dim(" in $projectRoot"))
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

        val nativeBackend = when (finalConfig.backend) {
            amber.compiler.BackendType.TINY_CC -> TccCompiler(runtimeRoot)
        }
        val nativeCompileStartTime = timeSource?.markNow()
        val compileResult = nativeBackend.compile(code, exePath, finalConfig, logger)
        val nativeCompileDuration = nativeCompileStartTime?.elapsedNow()

        when (compileResult) {
            is NativeCompileResult.Success -> {
                logger.info(
                    Ansi.green(Ansi.bold("✓")) + " compiled native " + Ansi.bold(
                        artifactBaseName
                    ) + Ansi.dim(" -> $exePath")
                )

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
                logger.error("native compilation failed: ${compileResult.message}")
                compileResult.diagnostics.forEach { diag ->
                    val loc = if (diag.file != null) "${diag.file}:${diag.line ?: ""}: " else ""
                    val msg = "$loc${diag.message}"
                    when (diag.severity) {
                        NativeDiagnosticSeverity.ERROR -> logger.error(msg)
                        NativeDiagnosticSeverity.WARNING -> logger.warn(msg)
                        NativeDiagnosticSeverity.INFO -> logger.info(msg)
                    }
                }
                exitProcess(1)
            }
        }
    }

    private fun handleFormat() {
        val projectFormatter = ProjectFormatter()
        var target = config.entryFile

        // If a "project" file is specified, format the directory containing it as a project.
        if (!isDirectory(target)) {
            val fileName = target.substringAfterLast('/').substringAfterLast('\\')
            if (fileName == "project") {
                target = amber.util.getPathParent(target) ?: "."
            }
        }
        
        if (isDirectory(target)) {
            logger.info("Formatting project at $target...")
            val summary = projectFormatter.formatProject(target)
            
            println("\n${Ansi.bold("Formatting Summary:")}")
            println("  Files formatted      : ${summary.filesFormatted}")
            println("  Files unchanged      : ${summary.filesUnchanged}")
            println("  Files skipped        : ${summary.filesSkipped}")
            if (summary.filesWithErrors > 0) {
                println("  Files with errors    : ${Ansi.red(summary.filesWithErrors.toString())}")
            } else {
                println("  Files with errors    : 0")
            }
        } else {
            logger.info("Formatting file $target...")
            val result = projectFormatter.formatFile(target)
            when (result) {
                ProjectFormatter.FileFormattingResult.Formatted -> logger.info(Ansi.green("Formatted $target"))
                ProjectFormatter.FileFormattingResult.Unchanged -> logger.info("File already formatted")
                ProjectFormatter.FileFormattingResult.Error -> {
                    logger.error("Failed to format $target")
                    exitProcess(1)
                }
            }
        }
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
