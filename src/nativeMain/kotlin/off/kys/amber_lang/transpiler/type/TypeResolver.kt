package off.kys.amber_lang.transpiler.type

import off.kys.amber_lang.transpiler.ast.AstNode

/**
 * translates string-based type names from the parser into actual Type objects.
 */
class TypeResolver(private val errorReporter: (node: AstNode, message: String, suggestion: String?) -> Unit) {

    fun resolveType(typeName: String, node: AstNode): Type {
        if (typeName.endsWith("!")) {
            val innerTypeStr = typeName.substring(0, typeName.length - 1)
            val innerType = resolveType(innerTypeStr, node)
            return Type.UnsafeType(innerType)
        }
        if (typeName.endsWith("[]")) {
            val elementTypeStr = typeName.substring(0, typeName.length - 2)
            val elementType = resolveType(elementTypeStr, node)
            return Type.ListType(elementType)
        }
        if (typeName.contains("<") && typeName.endsWith(">")) {
            val baseType = typeName.substringBefore("<").trim()
            val elementTypeStr = typeName.substringAfter("<").substringBeforeLast(">").trim()
            val elementType = resolveType(elementTypeStr, node)
            return when (baseType) {
                "array_list" -> Type.ArrayListType(elementType)
                "list" -> Type.ListType(elementType)
                else -> {
                    errorReporter(node, "unknown generic type '$baseType'", null)
                    Type.ErrorType
                }
            }
        }
        return when (typeName) {
            "number", "num", "Int" -> Type.NumberType
            "string", "str" -> Type.StringType
            "boolean", "bool" -> Type.BooleanType
            "unit" -> Type.UnitType
            "any" -> Type.AnyType
            "char" -> Type.CharType
            else -> {
                errorReporter(
                    node,
                    "unknown type '$typeName'",
                    "check for typos or make sure you aren't trying to use a class that doesn't exist yet"
                )
                Type.ErrorType
            }
        }
    }
}