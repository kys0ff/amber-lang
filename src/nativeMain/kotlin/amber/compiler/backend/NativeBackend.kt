package amber.compiler.backend

import amber.compiler.CompilerConfig
import amber.util.Logger

/**
 * Interface for native code compilers (TCC, LLVM, etc.)
 */
interface NativeBackend {
    fun compile(cCode: String, outputPath: String, config: CompilerConfig, logger: Logger? = null): NativeCompileResult
}

sealed class NativeCompileResult {
    data class Success(val outputPath: String) : NativeCompileResult()
    data class Failure(
        val message: String,
        val diagnostics: List<NativeDiagnostic> = emptyList()
    ) : NativeCompileResult()
}

data class NativeDiagnostic(
    val message: String,
    val severity: NativeDiagnosticSeverity = NativeDiagnosticSeverity.ERROR,
    val file: String? = null,
    val line: Int? = null
)

enum class NativeDiagnosticSeverity {
    ERROR, WARNING, INFO
}
