package off.kys.amber_lang.transpiler.type

import off.kys.amber_lang.transpiler.ast.AstNode
import off.kys.amber_lang.transpiler.ast.CallExpression

class TypeCompatibilityChecker(private val errorReporter: (node: AstNode, message: String, suggestion: String?) -> Unit) {

    fun checkAssignmentCompatibility(
        targetType: Type,
        valueType: Type,
        node: AstNode,
        targetName: String? = null,
    ): Boolean {
        if (targetType == Type.AnyType || valueType == Type.ErrorType) return true
        if (targetType == valueType) return true

        if (targetType is Type.UnsafeType && valueType == targetType.innerType) return true
        if (targetType is Type.UnsafeType && valueType is Type.UnsafeType && valueType.innerType == Type.AnyType) return true

        if (targetType is Type.ArrayListType && valueType is Type.ArrayListType) {
            if (targetType.elementType == Type.AnyType) return true
        }
        if (targetType is Type.ListType && valueType is Type.ListType) {
            if (targetType.elementType == Type.AnyType) return true
        }

        val namePart = targetName?.let { "variable '$it' of " } ?: ""
        errorReporter(
            node,
            "type mismatch: cannot assign $valueType to ${namePart}type $targetType",
            "make sure the types match or use a type cast"
        )
        return false
    }

    fun checkBinaryOperatorCompatibility(leftType: Type, operator: String, rightType: Type, node: AstNode): Type {
        // silence cascading errors if one side is already broken
        if (leftType == Type.ErrorType || rightType == Type.ErrorType) return Type.ErrorType

        return when (operator) {
            "+" -> {
                if (leftType is Type.ArrayListType && (leftType.elementType == rightType || leftType.elementType == Type.AnyType)) {
                    leftType
                } else if (leftType == Type.StringType || rightType == Type.StringType) {
                    Type.StringType
                } else if (leftType == Type.NumberType && rightType == Type.NumberType) {
                    Type.NumberType
                } else {
                    errorReporter(
                        node,
                        "invalid operands for '+': expected (num, num), (str, any), or (array_list<T>, T), got ($leftType, $rightType)",
                        "use numbers for math, string for concatenation, or append to array_list"
                    )
                    Type.ErrorType
                }
            }

            "-", "*", "/", "%" -> {
                if (leftType == Type.NumberType && rightType == Type.NumberType) {
                    Type.NumberType
                } else {
                    errorReporter(
                        node,
                        "operator '$operator' expects numbers, but got $leftType and $rightType",
                        "ensure both operands evaluate to numbers"
                    )
                    Type.ErrorType
                }
            }

            "==", "!=" -> {
                if (leftType == rightType || leftType == Type.AnyType || rightType == Type.AnyType) {
                    Type.BooleanType
                } else {
                    errorReporter(
                        node,
                        "comparison error: cannot compare $leftType with $rightType",
                        "compare only compatible types"
                    )
                    Type.ErrorType
                }
            }

            "<", ">", "<=", ">=" -> {
                if (leftType == Type.NumberType && rightType == Type.NumberType) {
                    Type.BooleanType
                } else {
                    errorReporter(
                        node,
                        "operator '$operator' only works on numbers, got $leftType and $rightType",
                        "convert operands to numbers before comparing"
                    )
                    Type.ErrorType
                }
            }

            "&&", "||" -> {
                if (leftType == Type.BooleanType && rightType == Type.BooleanType) {
                    Type.BooleanType
                } else {
                    errorReporter(
                        node,
                        "logical operator '$operator' expects booleans, got $leftType and $rightType",
                        "use boolean expressions for logical operations"
                    )
                    Type.ErrorType
                }
            }

            else -> {
                errorReporter(
                    node,
                    "unknown binary operator: '$operator'",
                    "check the operator or your keyboard's health"
                )
                Type.ErrorType
            }
        }
    }

    fun checkUnaryOperatorCompatibility(operator: String, operandType: Type, node: AstNode): Type {
        if (operandType == Type.ErrorType) return Type.ErrorType

        return when (operator) {
            "-" -> {
                if (operandType == Type.NumberType) {
                    Type.NumberType
                } else {
                    errorReporter(
                        node,
                        "negation expects a number, but got $operandType",
                        "place '-' only before numeric values"
                    )
                    Type.ErrorType
                }
            }

            "!" -> {
                if (operandType == Type.BooleanType) {
                    Type.BooleanType
                } else {
                    errorReporter(
                        node,
                        "logical NOT expects a boolean, but got $operandType",
                        "place '!' only before boolean expressions"
                    )
                    Type.ErrorType
                }
            }

            else -> {
                errorReporter(
                    node,
                    "unknown unary operator: '$operator'",
                    "check for typos"
                )
                Type.ErrorType
            }
        }
    }

    fun checkConditionType(conditionType: Type, node: AstNode, statementType: String): Boolean {
        if (conditionType == Type.ErrorType) return false
        if (conditionType != Type.BooleanType) {
            errorReporter(
                node,
                "$statementType condition must be boolean, but got $conditionType",
                "use an expression that evaluates to true or false"
            )
            return false
        }
        return true
    }

    fun checkFunctionCallArguments(call: CallExpression, calleeType: Type.FunctionType, argTypes: List<Type>): Boolean {
        val minArgs = calleeType.parameterTypes.zip(calleeType.hasDefaultValues).count { (_, hasDef) -> !hasDef }
        val maxArgs = calleeType.parameterTypes.size

        if (argTypes.size !in minArgs..maxArgs) {
            val expected = if (minArgs == maxArgs) "$minArgs" else "$minArgs-$maxArgs"
            errorReporter(
                call,
                "argument count mismatch: expected $expected, but got ${argTypes.size}",
                "provide the missing arguments or check the function definition"
            )
            return false
        }

        var allCompatible = true
        argTypes.forEachIndexed { i, argType ->
            val expected = calleeType.parameterTypes[i]
            if (argType != Type.ErrorType && argType != expected && expected != Type.AnyType) {
                errorReporter(
                    call.arguments[i],
                    "argument ${i + 1} type mismatch: expected $expected, got $argType",
                    "pass a value that matches the parameter type"
                )
                allCompatible = false
            }
        }
        return allCompatible
    }

    fun checkReturnType(returnedType: Type, expectedReturnType: Type, node: AstNode): Boolean {
        if (expectedReturnType == Type.AnyType || returnedType == Type.ErrorType) return true
        if (returnedType == expectedReturnType) return true
        if (expectedReturnType is Type.UnsafeType && returnedType == expectedReturnType.innerType) return true
        if (expectedReturnType is Type.UnsafeType && returnedType is Type.UnsafeType && returnedType.innerType == Type.AnyType) return true

        errorReporter(
            node,
            "return type mismatch: expected $expectedReturnType, but got $returnedType",
            "return a value that matches the function's signature"
        )
        return false
    }
}