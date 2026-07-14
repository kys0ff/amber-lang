package off.kys.amber_lang.transpiler.type

sealed class Type {
    object NumberType : Type()
    object StringType : Type()
    object BooleanType : Type()
    object UnitType : Type() // For functions that don't return a value, or statements
    object ErrorType : Type() // For when a type cannot be determined or is invalid
    object AnyType : Type() // New type for 'any'
    object CharType : Type()

    data class ArrayListType(val elementType: Type) : Type()
    data class ListType(val elementType: Type) : Type()
    data class UnsafeType(val innerType: Type) : Type()

    data class FunctionType(val parameterTypes: List<Type>, val hasDefaultValues: List<Boolean>, val returnType: Type) : Type()
    data class ModuleType(val exportedSymbols: Map<String, Symbol>) : Type() // New type for modules

    final override fun toString(): String = when (this) {
        NumberType -> "number"
        StringType -> "string"
        BooleanType -> "boolean"
        UnitType -> "unit"
        ErrorType -> "error"
        AnyType -> "any" // Add AnyType to toString
        CharType -> "char"
        is ArrayListType -> "array_list<$elementType>"
        is ListType -> "${elementType}[]"
        is UnsafeType -> "${innerType}!"
        is FunctionType -> "(${parameterTypes.joinToString(", ")}) -> $returnType"
        is ModuleType -> "module { ${exportedSymbols.keys.joinToString(", ")} }"
    }
}
