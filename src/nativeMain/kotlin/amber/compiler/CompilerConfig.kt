package amber.compiler

enum class OptimizationLevel {
    O0, O1, O2, O3
}

enum class BackendType {
    C, TINYCC, LLVM, WASM
}

enum class GCType {
    BOEHM, NONE
}

/**
 * Configuration options for the Amber compiler.
 */
data class CompilerConfig(
    val projectRoot: String,
    val entryFile: String,
    val isProject: Boolean = false,
    val outputName: String? = null,
    val emitC: String? = null,
    val optimizationLevel: OptimizationLevel = OptimizationLevel.O1,
    val backend: BackendType = BackendType.TINYCC,
    val gc: GCType = GCType.BOEHM,
    val useColor: Boolean = true,
    val verbose: Boolean = false,
    val benchmark: Boolean = false,
    val quiet: Boolean = false,
    val checkOnly: Boolean = false,
    val runAfterBuild: Boolean = true,
    val executableDir: String = "."
)

class CompilerConfigBuilder {
    var projectRoot: String = "."
    var entryFile: String = "main.amb"
    var isProject: Boolean = false
    var outputName: String? = null
    var emitC: String? = null
    var optimizationLevel: OptimizationLevel = OptimizationLevel.O1
    var backend: BackendType = BackendType.TINYCC
    var gc: GCType = GCType.BOEHM
    var useColor: Boolean = true
    var verbose: Boolean = false
    var benchmark: Boolean = false
    var quiet: Boolean = false
    var checkOnly: Boolean = false
    var runAfterBuild: Boolean = true
    var executableDir: String = "."

    fun build() = CompilerConfig(
        projectRoot = projectRoot,
        entryFile = entryFile,
        isProject = isProject,
        outputName = outputName,
        emitC = emitC,
        optimizationLevel = optimizationLevel,
        backend = backend,
        gc = gc,
        useColor = useColor,
        verbose = verbose,
        benchmark = benchmark,
        quiet = quiet,
        checkOnly = checkOnly,
        runAfterBuild = runAfterBuild,
        executableDir = executableDir
    )
}

fun compilerConfig(block: CompilerConfigBuilder.() -> Unit): CompilerConfig =
    CompilerConfigBuilder().apply(block).build()
