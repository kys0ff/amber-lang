package off.kys.amber_lang.transpiler.backend

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import off.kys.amber_lang.interop.tcc.TCC_OUTPUT_EXE
import off.kys.amber_lang.interop.tcc.tcc_add_file
import off.kys.amber_lang.interop.tcc.tcc_add_include_path
import off.kys.amber_lang.interop.tcc.tcc_add_library
import off.kys.amber_lang.interop.tcc.tcc_add_sysinclude_path
import off.kys.amber_lang.interop.tcc.tcc_compile_string
import off.kys.amber_lang.interop.tcc.tcc_delete
import off.kys.amber_lang.interop.tcc.tcc_new
import off.kys.amber_lang.interop.tcc.tcc_output_file
import off.kys.amber_lang.interop.tcc.tcc_set_error_func
import off.kys.amber_lang.interop.tcc.tcc_set_lib_path
import off.kys.amber_lang.interop.tcc.tcc_set_options
import off.kys.amber_lang.interop.tcc.tcc_set_output_type

class TccCompiler(
    private val libsRoot: String
) {
    @OptIn(ExperimentalForeignApi::class)
    fun compile(cCode: String, outputPath: String): TccResult {
        val s = tcc_new() ?: return TccResult.Failure("Failed to create TCC state")
        val errors = mutableListOf<String>()

        val errorCallback = staticCFunction { _: COpaquePointer?, msg: CPointer<ByteVar>? ->
            val errorMsg = msg?.toKString() ?: "Unknown TCC error"
            // We can't easily capture into the mutableList here because it's a staticCFunction
            // and we don't have a way to pass the list easily without a stable ref.
            println("TCC Error: $errorMsg")
        }

        try {
            tcc_set_error_func(s, null, errorCallback)
            tcc_set_lib_path(s, libsRoot)
            tcc_set_options(s, "-Wl,-rpath,${libsRoot}")
            tcc_set_output_type(s, TCC_OUTPUT_EXE)
            
            tcc_add_sysinclude_path(s, "${libsRoot}/include/tcc")
            tcc_add_include_path(s, "${libsRoot}/include/gc")
            tcc_add_file(s, "${libsRoot}/libgc.so")
            tcc_add_library(s, "m")
            
            if (tcc_compile_string(s, cCode) == -1) {
                return TccResult.Failure("Compilation failed")
            }
            
            if (tcc_output_file(s, outputPath) == -1) {
                return TccResult.Failure("Failed to write output file: $outputPath")
            }
            
            return TccResult.Success(outputPath)
        } catch (e: Exception) {
            return TccResult.Failure(e.message ?: "Unknown exception during compilation")
        } finally {
            tcc_delete(s)
        }
    }
}

sealed class TccResult {
    data class Success(val outputPath: String) : TccResult()
    data class Failure(val message: String) : TccResult()
}
