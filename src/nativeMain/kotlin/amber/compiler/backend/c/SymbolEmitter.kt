package amber.compiler.backend.c

class SymbolEmitter(private val userPrefix: String = "__am_") {
    private var tempCounter = 0

    fun mangle(name: String, moduleNamespace: String? = null): String {
        val cleanNamespace = moduleNamespace?.replace('.', '_')
        val prefix = if (cleanNamespace != null) "${userPrefix}${cleanNamespace}_" else userPrefix
        return "$prefix$name"
    }

    fun mangleExtension(name: String, receiverType: amber.compiler.type.Type, moduleNamespace: String? = null): String {
        val typeName = receiverType.toString().replace("[]", "_list").replace("<", "_").replace(">", "_").replace("!", "_err").replace(" ", "_")
        return mangle("ext_${typeName}_$name", moduleNamespace)
    }

    fun nextTemp(): String = "${userPrefix}tmp_${tempCounter++}"

    fun mangleStruct(name: String, moduleNamespace: String? = null): String {
        return "struct_" + mangle(name, moduleNamespace)
    }

    fun runtimeHelper(name: String): String = "__amber_rt_$name"
}
