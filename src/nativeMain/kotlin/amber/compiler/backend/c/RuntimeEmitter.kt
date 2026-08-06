package amber.compiler.backend.c

import amber.runtime.BoehmGC
import amber.runtime.GarbageCollector
import amber.runtime.StandardLibrary

class RuntimeEmitter(
    private val writer: CodeWriter,
    private val gc: GarbageCollector = BoehmGC
) {
    fun emitHeaders() {
        writer.writeLine("#include <stdio.h>")
        writer.writeLine("#include <stdlib.h>")
        writer.writeLine("#include <string.h>")
        writer.writeLine("#include <stdbool.h>")
        writer.writeLine("#include <math.h>")
        writer.writeLine("#include <stdint.h>")
        if (gc.header.isNotEmpty()) {
            writer.writeLine(gc.header)
        }
        writer.writeLine()
    }

    fun emitTypedefs() {
        // Type Descriptor
        writer.writeLine("typedef struct {")
        writer.indent()
        writer.writeLine("const char* name;")
        writer.writeLine("int32_t id;")
        writer.dedent()
        writer.writeLine("} __amber_type_t;")
        writer.writeLine()

        // Object Header (for boxed types and classes)
        writer.writeLine("typedef struct {")
        writer.indent()
        writer.writeLine("__amber_type_t* type;")
        writer.dedent()
        writer.writeLine("} __amber_header_t;")
        writer.writeLine()

        // Boxed Double
        writer.writeLine("typedef struct {")
        writer.indent()
        writer.writeLine("__amber_header_t header;")
        writer.writeLine("double value;")
        writer.dedent()
        writer.writeLine("} __amber_box_double_t;")
        writer.writeLine()

        // List/Array type
        writer.writeLine("typedef struct {")
        writer.indent()
        writer.writeLine("__amber_header_t header;")
        writer.writeLine("void** data;")
        writer.writeLine("int32_t length;")
        writer.writeLine("int32_t capacity;")
        writer.dedent()
        writer.writeLine("} __amber_list_t;")
        writer.writeLine()

        // Result type (for error handling)
        writer.writeLine("struct AMBER_RESULT {")
        writer.indent()
        writer.writeLine("void* value;")
        writer.writeLine("int32_t has_error;")
        writer.writeLine("const char* error_message;")
        writer.dedent()
        writer.writeLine("};")
        writer.writeLine()

        writer.writeLine("static struct AMBER_RESULT __amber_rt_last_error = { NULL, 0, NULL };")
        writer.writeLine()
        
        // Built-in Type Descriptors (Declarations)
        writer.writeLine("extern __amber_type_t __amber_type_double;")
        writer.writeLine("extern __amber_type_t __amber_type_string;")
        writer.writeLine("extern __amber_type_t __amber_type_bool;")
        writer.writeLine()
    }

    fun emitIntrinsics() {
        writer.writeLine("static inline void* __amber_rt_alloc(size_t size) {")
        writer.indent()
        writer.writeLine("void* ptr = ${gc.malloc("size")};")
        writer.writeLine("if (!ptr) { fprintf(stderr, \"Out of memory\\n\"); exit(1); }")
        writer.writeLine("return ptr;")
        writer.dedent()
        writer.writeLine("}")
        writer.writeLine()

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
                __amber_box_double_t* p = (__amber_box_double_t*)__amber_rt_alloc(sizeof(__amber_box_double_t));
                p->header.type = &__amber_type_double;
                p->value = d;
                return p;
            }

            static inline double __amber_rt_unbox_double(void* p) {
                if (!p) return 0.0;
                // Basic safety check could be added here
                return ((__amber_box_double_t*)p)->value;
            }
            
            static inline int __amber_rt_is_error(struct AMBER_RESULT res) {
                return res.has_error;
            }
            
            static inline void* __amber_rt_unwrap(struct AMBER_RESULT res) {
                return res.value;
            }
            
            static inline int __amber_rt_is_type(void* obj, __amber_type_t* type) {
                if (!obj) return 0;
                __amber_header_t* header = (__amber_header_t*)obj;
                return header->type == type;
            }

            static inline void* __amber_rt_as_type(void* obj, __amber_type_t* type) {
                if (__amber_rt_is_type(obj, type)) return obj;
                // We'll call the intrinsic panic if available, or a simple version here
                fprintf(stderr, "ClassCastException: object is not of type %s\n", type->name);
                exit(1);
                return NULL;
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

    fun emitDefinitions() {
        writer.writeLine("__amber_type_t __amber_type_double = { \"Number\", 1 };")
        writer.writeLine("__amber_type_t __amber_type_string = { \"String\", 2 };")
        writer.writeLine("__amber_type_t __amber_type_bool = { \"Boolean\", 3 };")
        writer.writeLine()
    }
}
