package amber.cli

import amber.compiler.BackendType
import amber.compiler.CompilerCommand
import amber.compiler.CompilerConfig
import amber.compiler.GCType
import amber.compiler.OptimizationLevel
import amber.compiler.compilerConfig
import amber.util.Ansi
import amber.util.getExecutableDirectory
import amber.util.normalizePath
import kotlin.system.exitProcess

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

private val VALUE_FLAGS = setOf("--output", "-o", "--emit-c")

class CliParser {
    fun parse(args: Array<String>): CompilerConfig {
        if (args.getOrNull(0) == "fmt") {
            return parseFmt(args.drop(1))
        }

        val dashDashIndex = args.indexOf("--")
        val compilerArgs = if (dashDashIndex != -1) args.slice(0 until dashDashIndex) else args.toList()
        
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
                        printError("'$arg' requires a value")
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

        if ("--no-color" in flags) Ansi.forceDisable()
        
        unknown.forEach {
            println(Ansi.yellow("warning:") + " unknown flag '$it' ignored (see --help)")
        }

        if ("--help" in flags || "-h" in flags) {
            printHelp()
            exitProcess(0)
        }

        if ("--version" in flags || "-v" in flags) {
            println("Amber Compiler v0.1.0")
            exitProcess(0)
        }

        val absoluteTarget = normalizePath(target ?: ".")
        
        return compilerConfig {
            command = CompilerCommand.BUILD
            projectRoot = "."
            entryFile = absoluteTarget
            outputName = output
            this.emitC = emitC
            optimizationLevel = OptimizationLevel.O1
            backend = BackendType.TINY_CC
            gc = GCType.BOEHM
            useColor = "--no-color" !in flags
            verbose = "--verbose" in flags || "-V" in flags
            benchmark = "--benchmark" in flags || "-b" in flags
            quiet = "--quiet" in flags || "-q" in flags
            checkOnly = "--check" in flags || "-c" in flags
            runAfterBuild = "--no-run" !in flags
            executableDir = getExecutableDirectory()
        }
    }

    private fun parseFmt(args: List<String>): CompilerConfig {
        var target: String? = null
        val flags = mutableSetOf<String>()

        args.forEach { arg ->
            if (arg.startsWith("-")) {
                flags += arg
            } else if (target == null) {
                target = arg
            }
        }

        if ("--help" in flags || "-h" in flags) {
            printHelp()
            exitProcess(0)
        }

        val absoluteTarget = normalizePath(target ?: ".")

        return compilerConfig {
            command = CompilerCommand.FORMAT
            entryFile = absoluteTarget
            useColor = "--no-color" !in flags
        }
    }

    private fun printError(message: String) {
        println(Ansi.red(Ansi.bold("error:")) + " $message")
    }

    private fun printHelp() {
        println(
            """
            ${Ansi.bold("Amber Compiler CLI")}
            ${Ansi.dim("Usage:")} amber [command] [options] [path] [-- script_args]
    
            ${Ansi.bold("Commands")}
              fmt [path]          Format Amber source files
    
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
                  --no-color      Disable ANSI color output (see also NO_COLOR)
                  --              Separator indicating all subsequent flags belong to the script

            ${Ansi.bold("Formatting")}
              amber fmt
                  Format the current project.
          
              amber fmt <file>
                  Format a single Amber source file.
          
              amber fmt <directory>
                  Recursively format an Amber project.

              amber fmt project
                  Format the project defined by the 'project' file.
            """.trimIndent()
        )
    }
}
