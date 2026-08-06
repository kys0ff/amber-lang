package off.kys.amber_lang.transpiler.backend

class RuntimeEmitter(private val writer: CodeWriter) {
    fun emitHeaders() {
        writer.writeLine("#include <stdio.h>")
        writer.writeLine("#include <stdlib.h>")
        writer.writeLine("#include <string.h>")
        writer.writeLine("#include <stdbool.h>")
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
        writer.writeLine("} amber_list_t;")
        writer.writeLine()
    }

    fun emitBuiltins() {
        // Example builtin: echo
        writer.writeLine("void amber_rt_echo(char* str) {")
        writer.indent()
        writer.writeLine("printf(\"%s\\n\", str);")
        writer.dedent()
        writer.writeLine("}")
        writer.writeLine()

        writer.writeLine("char* amber_rt_str_concat(char* s1, char* s2) {")
        writer.indent()
        writer.writeLine("int len1 = strlen(s1);")
        writer.writeLine("int len2 = strlen(s2);")
        writer.writeLine("char* res = (char*)GC_MALLOC(len1 + len2 + 1);")
        writer.writeLine("strcpy(res, s1);")
        writer.writeLine("strcat(res, s2);")
        writer.writeLine("return res;")
        writer.dedent()
        writer.writeLine("}")
        writer.writeLine()
    }
}
