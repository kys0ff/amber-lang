package off.kys.amber_lang.transpiler.backend

import off.kys.amber_lang.runtime.StandardLibrary

class RuntimeEmitter(private val writer: CodeWriter) {
    fun emitHeaders() {
        writer.writeLine("#include <stdio.h>")
        writer.writeLine("#include <stdlib.h>")
        writer.writeLine("#include <string.h>")
        writer.writeLine("#include <stdbool.h>")
        writer.writeLine("#include <math.h>")
        writer.writeLine("#include <gc.h>")
        writer.writeLine()
    }

    fun emitTypedefs() {
        writer.writeLine("typedef struct {")
        writer.indent()
        writer.writeLine("void** data;")
        writer.writeLine("int length;")
        writer.writeLine("int capacity;")
        writer.dedent()
        writer.writeLine("} __amber_list_t;")
        writer.writeLine()

        writer.writeLine("struct AMBER_RESULT {")
        writer.indent()
        writer.writeLine("void* value;")
        writer.writeLine("int has_error;")
        writer.writeLine("const char* error_message;")
        writer.dedent()
        writer.writeLine("};")
        writer.writeLine()
        
        writer.writeLine("static struct AMBER_RESULT __amber_rt_last_error = { NULL, 0, NULL };")
        writer.writeLine()
    }

    fun emitIntrinsics() {
        writer.writeLine("""
            static inline struct AMBER_RESULT __amber_rt_result_success(void* val) {
                struct AMBER_RESULT res = { val, 0, NULL };
                return res;
            }

            static inline struct AMBER_RESULT __amber_rt_result_error(const char* msg) {
                struct AMBER_RESULT res = { NULL, 1, msg };
                __amber_rt_last_error = res;
                return res;
            }

            static inline void* __amber_rt_box_double(double d) {
                double* p = (double*)GC_MALLOC(sizeof(double));
                if (p) *p = d;
                return p;
            }

            static inline double __amber_rt_unbox_double(void* p) {
                return p ? *(double*)p : 0.0;
            }
            
            static inline int __amber_rt_is_error(struct AMBER_RESULT res) {
                return res.has_error;
            }
            
            static inline void* __amber_rt_unwrap(struct AMBER_RESULT res) {
                return res.value;
            }
        """.trimIndent())
        writer.writeLine()
        
        StandardLibrary.intrinsics.values.forEach { intrinsic ->
            intrinsic.cImplementation?.let {
                writer.writeLine(it)
                writer.writeLine()
            }
        }
    }
}
