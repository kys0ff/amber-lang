package off.kys.amber_lang.transpiler.backend

import off.kys.amber_lang.transpiler.type.Type

class CTypeMapper {
    fun map(type: Type): String = when (type) {
        Type.NumberType -> "double"
        Type.StringType -> "char*"
        Type.BooleanType -> "int"
        Type.UnitType -> "void"
        Type.CharType -> "char"
        Type.AnyType -> "void*"
        is Type.ArrayListType -> "__amber_list_t*"
        is Type.ListType -> "__amber_list_t*"
        is Type.FunctionType -> "void*" // Simplified for now
        is Type.EnumType -> "int"
        else -> "void*"
    }
}
