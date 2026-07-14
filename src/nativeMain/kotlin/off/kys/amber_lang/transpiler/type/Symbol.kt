package off.kys.amber_lang.transpiler.type

/**
 * represents a named entity in the source code.
 */
class Symbol(
    val name: String,
    var type: Type,
    val isMutable: Boolean = false,
    val inferableParameterIndices: MutableSet<Int> = mutableSetOf(),
    var isUsed: Boolean = false,
    val isIntrinsic: Boolean = false,
    val line: Int = -1,
    val column: Int = -1
)