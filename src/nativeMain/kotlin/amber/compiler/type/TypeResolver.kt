package amber.compiler.type

import amber.compiler.ast.AstNode
import amber.compiler.symbol.SymbolTable

/**
 * Translates string-based type names from the parser into actual Type objects.
 */
class TypeResolver(private val errorReporter: (node: AstNode, message: String, suggestion: String?) -> Unit) {

    fun resolveType(typeName: String, node: AstNode, scope: SymbolTable? = null): Type {
        if (typeName.endsWith("!")) {
            val innerTypeStr = typeName.substring(0, typeName.length - 1)
            val innerType = resolveType(innerTypeStr, node, scope)
            return Type.Unsafe(innerType)
        }
        if (typeName.endsWith("[]")) {
            val elementTypeStr = typeName.substring(0, typeName.length - 2)
            val elementType = resolveType(elementTypeStr, node, scope)
            if (elementType is Type.Enum) {
                errorReporter(node, "enums cannot be used in lists", "enums are currently not supported in lists")
            }
            return Type.List(elementType)
        }
        if (typeName.contains("<") && typeName.endsWith(">")) {
            val baseType = typeName.substringBefore("<").trim()
            val elementTypeStr = typeName.substringAfter("<").substringBeforeLast(">").trim()
            val elementType = resolveType(elementTypeStr, node, scope)

            if (elementType is Type.Enum && (baseType == "list" || baseType == "array_list")) {
                errorReporter(node, "enums cannot be used in lists", "enums are currently not supported in lists")
            }

            return when (baseType) {
                "array_list" -> Type.ArrayList(elementType)
                "list" -> Type.List(elementType)
                else -> {
                    errorReporter(node, "unknown generic type '$baseType'", null)
                    Type.Error
                }
            }
        }
        val builtinType = when (typeName) {
            "num" -> Type.Number
            "string" -> Type.String
            "bool" -> Type.Boolean
            "any" -> Type.Any
            else -> null
        }
        if (builtinType != null) return builtinType

        val symbol = scope?.resolve(typeName)
        if (symbol?.type is Type.EnumNamespace) {
            return (symbol.type as Type.EnumNamespace).enumType
        }

        errorReporter(
            node,
            "unknown type '$typeName'",
            "check for typos or make sure you aren't trying to use a class that doesn't exist yet"
        )
        return Type.Error
    }
}
