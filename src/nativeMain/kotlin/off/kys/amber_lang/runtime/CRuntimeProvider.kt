package off.kys.amber_lang.runtime

import off.kys.amber_lang.transpiler.type.Symbol
import off.kys.amber_lang.transpiler.type.Type

class CRuntimeProvider : RuntimeProvider {
    override fun getBuiltInSymbols(): Map<String, Symbol> {
        return mapOf(
            "echo" to Symbol("echo", Type.FunctionType(listOf(Type.StringType), listOf(false), Type.UnitType), isIntrinsic = true),
            "to_string" to Symbol("to_string", Type.FunctionType(listOf(Type.AnyType), listOf(false), Type.StringType), isIntrinsic = true),
            "str_concat" to Symbol("str_concat", Type.FunctionType(listOf(Type.StringType, Type.StringType), listOf(false, false), Type.StringType), isIntrinsic = true)
        )
    }

    override fun getAllIntrinsicSymbols(): Map<String, Symbol> = getBuiltInSymbols()

    override fun getBuiltInNames(): Set<String> = setOf("echo", "to_string", "str_concat")

    override fun getPlatformName(name: String): String? = when (name) {
        "echo" -> "echo"
        "to_string" -> "to_string"
        "str_concat" -> "str_concat"
        "println" -> "echo" // Map println directly for now if needed, but stdlib preferred
        else -> null
    }

    override fun isRuntimeHelper(name: String): Boolean = false

    override fun getRuntimeSource(prefix: String, usedNames: Set<String>): String = ""
}
