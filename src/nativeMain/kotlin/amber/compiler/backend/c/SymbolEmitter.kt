package amber.compiler.backend.c

class SymbolEmitter(private val userPrefix: String = "__am_") {
    private var tempCounter = 0

    fun mangle(name: String, moduleNamespace: String? = null): String {
        val cleanNamespace = moduleNamespace?.replace('.', '_')
        val prefix = if (cleanNamespace != null) "${userPrefix}${cleanNamespace}_" else userPrefix
        return "$prefix$name"
    }

    fun nextTemp(): String = "${userPrefix}tmp_${tempCounter++}"

    fun runtimeHelper(name: String): String = "__amber_rt_$name"
}
