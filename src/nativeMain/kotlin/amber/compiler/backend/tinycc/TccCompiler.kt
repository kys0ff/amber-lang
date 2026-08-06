package amber.compiler.backend.tinycc

import amber.compiler.CompilerConfig
import amber.compiler.OptimizationLevel
import amber.compiler.backend.NativeBackend
import amber.compiler.backend.NativeCompileResult
import amber.compiler.backend.NativeDiagnostic
import amber.compiler.backend.NativeDiagnosticSeverity
import amber.interop.tcc.TCC_OUTPUT_EXE
import amber.interop.tcc.tcc_add_file
import amber.interop.tcc.tcc_add_include_path
import amber.interop.tcc.tcc_add_library
import amber.interop.tcc.tcc_add_sysinclude_path
import amber.interop.tcc.tcc_compile_string
import amber.interop.tcc.tcc_delete
import amber.interop.tcc.tcc_new
import amber.interop.tcc.tcc_output_file
import amber.interop.tcc.tcc_set_error_func
import amber.interop.tcc.tcc_set_lib_path
import amber.interop.tcc.tcc_set_options
import amber.interop.tcc.tcc_set_output_type
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString

class TccCompiler(
    private val runtimeRoot: String
) : NativeBackend {

    @OptIn(ExperimentalForeignApi::class)
    override fun compile(
        cCode: String,
        outputPath: String,
        config: CompilerConfig,
        logger: amber.util.Logger?
    ): NativeCompileResult {
        val s = tcc_new() ?: return NativeCompileResult.Failure("failed to create TCC state")
        val diagnostics = mutableListOf<NativeDiagnostic>()
        val diagnosticsRef = StableRef.create(diagnostics)

        val errorCallback = staticCFunction { opaque: COpaquePointer?, msg: CPointer<ByteVar>? ->
            val errorMsg = msg?.toKString() ?: "unknown TCC error"
            val collectedDiagnostics = opaque?.asStableRef<MutableList<NativeDiagnostic>>()?.get()

            val severity = when {
                errorMsg.contains("warning:", ignoreCase = true) -> NativeDiagnosticSeverity.WARNING
                errorMsg.contains("info:", ignoreCase = true) -> NativeDiagnosticSeverity.INFO
                else -> NativeDiagnosticSeverity.ERROR
            }

            collectedDiagnostics?.add(NativeDiagnostic(errorMsg, severity))
            Unit
        }

        try {
            tcc_set_error_func(s, diagnosticsRef.asCPointer(), errorCallback)

            // TCC runtime path (for libtcc1.a)
            tcc_set_lib_path(s, "${runtimeRoot}/tcc")
            tcc_set_output_type(s, TCC_OUTPUT_EXE)

            // Set optimization level
            val optFlag = when (config.optimizationLevel) {
                OptimizationLevel.O0 -> "-O0"
                OptimizationLevel.O1 -> "-O1"
                OptimizationLevel.O2 -> "-O2"
                OptimizationLevel.O3 -> "-O3"
            }
            tcc_set_options(s, optFlag)

            // Include paths
            tcc_add_sysinclude_path(s, "${runtimeRoot}/tcc/include")
            tcc_add_include_path(s, "${runtimeRoot}/gc/include")
            tcc_add_include_path(s, "${runtimeRoot}/gc/include/gc")

            if (tcc_compile_string(s, cCode) == -1) {
                return NativeCompileResult.Failure("compilation failed", diagnostics.toList())
            }

            // Explicitly add static libraries after compiling string to resolve symbols
            tcc_add_file(s, "${runtimeRoot}/gc/libgc.a")

            // Standard math library
            tcc_add_library(s, "m")

            // Required for libgc
            tcc_add_library(s, "pthread")
            tcc_add_library(s, "dl")

            if (config.verbose) {
                logger?.debug("Linking with libgc: ${runtimeRoot}/gc/libgc.a")
            }

            if (tcc_output_file(s, outputPath) == -1) {
                return NativeCompileResult.Failure(
                    "failed to write output file: $outputPath",
                    diagnostics.toList()
                )
            }

            return NativeCompileResult.Success(outputPath)
        } catch (e: Exception) {
            return NativeCompileResult.Failure(
                message = "unknown exception during compilation${e.message?.let { ": $it" }}",
                diagnostics = diagnostics.toList()
            )
        } finally {
            diagnosticsRef.dispose()
            tcc_delete(s)
        }
    }
}
