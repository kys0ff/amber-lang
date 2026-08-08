package amber.compiler.symbol

import amber.compiler.type.Type

/**
 * Represents a named entity in the source code.
 */
class Symbol(
    val name: String,
    var type: Type,
    val isMutable: Boolean = false,
    var isMutated: Boolean = false,
    var isParameter: Boolean = false,
    var isInitialized: Boolean = false,
    val isExplicitlyTyped: Boolean = false,
    val inferableParameterIndices: MutableSet<Int> = mutableSetOf(),
    var isUsed: Boolean = false,
    val isIntrinsic: Boolean = false,
    val isExtension: Boolean = false,
    val line: Int = -1,
    val column: Int = -1,
    val namespace: String? = null
) {
    val qualifiedName: String
        get() = if (namespace != null) "$namespace.$name" else name
}
