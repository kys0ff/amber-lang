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
    }

    fun emitIntrinsics() {
        StandardLibrary.intrinsics.values.forEach { intrinsic ->
            intrinsic.cImplementation?.let {
                writer.writeLine(it)
                writer.writeLine()
            }
        }
    }
}
