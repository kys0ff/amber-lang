package off.kys.amber_lang.transpiler.backend

class SymbolEmitter(private val userPrefix: String = "a_") {
    private var tempCounter = 0

    fun mangle(name: String, moduleNamespace: String? = null): String {
        val prefix = if (moduleNamespace != null) "${userPrefix}${moduleNamespace}_" else userPrefix
        return "$prefix$name"
    }

    fun nextTemp(): String = "${userPrefix}tmp_${tempCounter++}"

    fun runtimeHelper(name: String): String = "amber_rt_$name"
}
