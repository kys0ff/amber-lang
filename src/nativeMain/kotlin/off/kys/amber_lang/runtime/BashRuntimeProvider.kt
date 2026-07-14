package off.kys.amber_lang.runtime

import off.kys.amber_lang.transpiler.type.Symbol
import off.kys.amber_lang.transpiler.type.Type

class BashRuntimeProvider : RuntimeProvider {
    private data class Intrinsic(
        val name: String,
        val type: Type.FunctionType,
        val platformName: String? = null,
        val isHelper: Boolean = false,
        val dependencies: List<String> = emptyList(),
        val implementation: ((String) -> String)? = null
    )

    private val intrinsics = listOf(
        Intrinsic(
            name = "to_string",
            type = Type.FunctionType(listOf(Type.AnyType), listOf(false), Type.StringType),
            platformName = "to_string",
            isHelper = true,
            implementation = { prefix -> """
${prefix}to_string() {
    local val=${'$'}1
    local tag=${'$'}{val%%:*}
    local data=${'$'}{val#*:}
    case ${'$'}tag in
        arr)
            local arr_name=${'$'}data
            local -n items=${'$'}arr_name
            local result="["
            local first=true
            for i in "${'$'}{!items[@]}"; do
                if [ "${'$'}first" = true ]; then
                    first=false
                else
                    result+=", "
                fi
                result+="${'$'}(${prefix}to_string "${'$'}{items[${'$'}i]}")"
            done
            result+="]"
            echo "${'$'}result"
            ;;
        bool)
            if [ "${'$'}data" = "0" ]; then echo "true"; else echo "false"; fi
            ;;
        *)
            echo "${'$'}data"
            ;;
    esac
}
""" }
        ),
        Intrinsic(
            name = "println",
            type = Type.FunctionType(listOf(Type.AnyType), listOf(false), Type.UnitType),
            platformName = "echo",
            dependencies = listOf("to_string")
        ),
        Intrinsic(
            name = "print",
            type = Type.FunctionType(listOf(Type.AnyType), listOf(false), Type.UnitType),
            platformName = "echo -n",
            dependencies = listOf("to_string")
        ),
        Intrinsic(
            name = "readln",
            type = Type.FunctionType(emptyList(), emptyList(), Type.StringType),
            platformName = "readln",
            isHelper = true,
            implementation = { prefix -> """
${prefix}readln() {
    local line
    read -r line
    echo "${'$'}line"
}
""" }
        ),
        Intrinsic(
            name = "fail",
            type = Type.FunctionType(listOf(Type.StringType), listOf(false), Type.UnsafeType(Type.AnyType)),
            platformName = "fail_helper",
            isHelper = true,
            implementation = { prefix -> """
${prefix}fail_helper() {
    echo "err:${'$'}1"
}
""" }
        )
    )

    private val builtInFunctions = intrinsics
        .filter { it.name !in setOf("println", "print", "readln", "to_string") }
        .associate {
            it.name to Symbol(it.name, it.type, isIntrinsic = true)
        }

    private val allIntrinsicFunctions = intrinsics
        .associate {
            it.name to Symbol(it.name, it.type, isIntrinsic = true)
        }

    private val platformNames = intrinsics.filter { it.platformName != null }
        .associate { it.name to it.platformName!! }

    private val runtimeHelpers = intrinsics.filter { it.isHelper }
        .map { it.name }.toSet()

    private val builtInNames = setOf("echo", "bc", "printf", "sed", "awk")

    override fun getBuiltInSymbols(): Map<String, Symbol> = builtInFunctions

    override fun getAllIntrinsicSymbols(): Map<String, Symbol> = allIntrinsicFunctions

    override fun getBuiltInNames(): Set<String> = builtInNames

    override fun getPlatformName(name: String): String? = platformNames[name]

    override fun isRuntimeHelper(name: String): Boolean = runtimeHelpers.contains(name)

    override fun getRuntimeSource(prefix: String, usedNames: Set<String>): String {
        val toInclude = mutableSetOf<String>()
        val queue = mutableListOf<String>()
        queue.addAll(usedNames)

        while (queue.isNotEmpty()) {
            val next = queue.removeAt(0)
            if (toInclude.add(next)) {
                intrinsics.find { it.name == next }?.dependencies?.forEach {
                    queue.add(it)
                }
            }
        }

        return intrinsics.filter { it.implementation != null && toInclude.contains(it.name) }
            .joinToString("\n") { it.implementation!!.invoke(prefix) }
    }
}
