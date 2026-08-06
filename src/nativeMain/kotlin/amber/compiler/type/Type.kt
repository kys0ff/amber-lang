package amber.compiler.type

import amber.compiler.symbol.Symbol

sealed interface Type {
    object Number : Type { override fun toString() = "num" }
    object String : Type { override fun toString() = "string" }
    object Boolean : Type { override fun toString() = "bool" }
    object Unit : Type { override fun toString() = "unit" }
    object Error : Type { override fun toString() = "error" }
    object Any : Type { override fun toString() = "any" }
    object Char : Type { override fun toString() = "char" }
    object Nothing : Type { override fun toString() = "nothing" }

    data class ArrayList(val elementType: Type) : Type {
        override fun toString() = "array_list<$elementType>"
    }
    
    data class List(val elementType: Type) : Type {
        override fun toString() = "${elementType}[]"
    }
    
    data class Unsafe(val innerType: Type) : Type {
        override fun toString() = "${innerType}!"
    }

    data class Function(
        val parameterTypes: kotlin.collections.List<Type>,
        val hasDefaultValues: kotlin.collections.List<kotlin.Boolean>,
        val returnType: Type,
        val isParameterMutated: kotlin.collections.List<kotlin.Boolean> = parameterTypes.map { false }
    ) : Type {
        override fun toString() = "(${parameterTypes.joinToString(", ")}) -> $returnType"
    }
    
    data class Module(val exportedSymbols: Map<kotlin.String, Symbol>) : Type {
        override fun toString() = "module { ${exportedSymbols.keys.joinToString(", ")} }"
    }
    
    data class Enum(
        val name: kotlin.String,
        val variants: kotlin.collections.List<kotlin.String>,
        val moduleNamespace: kotlin.String? = null
    ) : Type {
        override fun toString() = name
    }
    
    data class EnumNamespace(val enumType: Enum) : Type {
        override fun toString() = "namespace $enumType"
    }
}
