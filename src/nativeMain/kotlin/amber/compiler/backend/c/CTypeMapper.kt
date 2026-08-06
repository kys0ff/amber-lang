package amber.compiler.backend.c

import amber.compiler.type.Type

class CTypeMapper {
    fun map(type: Type): String = when (type) {
        Type.Number -> "double"
        Type.String -> "char*"
        Type.Boolean -> "int"
        Type.Unit -> "void"
        Type.Char -> "char"
        Type.Any -> "void*"
        Type.Nothing -> "void"
        Type.Error -> "void*"
        is Type.ArrayList -> "__amber_list_t*"
        is Type.List -> "__amber_list_t*"
        is Type.Unsafe -> "struct AMBER_RESULT"
        is Type.Function -> "void*"
        is Type.Enum -> "int"
        is Type.EnumNamespace -> "int"
        is Type.Module -> "void*"
    }

    fun mapParameter(type: Type, isMutated: Boolean): String {
        val base = map(type)
        return if (isMutated && isPrimitive(type)) {
            "$base*"
        } else {
            base
        }
    }

    private fun isPrimitive(type: Type): Boolean {
        return type == Type.Number || type == Type.Boolean || type == Type.String || type == Type.Char
    }
}
