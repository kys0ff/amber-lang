package amber.compiler.type

import amber.compiler.ast.AstNode
import amber.compiler.ast.CallExpression

class TypeCompatibilityChecker(private val errorReporter: (node: AstNode, message: String, suggestion: String?) -> Unit) {

    fun checkAssignmentCompatibility(
        targetType: Type,
        valueType: Type,
        node: AstNode,
        targetName: String? = null,
    ): Boolean {
        if (targetType == Type.Any || valueType == Type.Error || valueType == Type.Nothing) return true
        if (targetType == valueType) return true

        if (targetType is Type.Unsafe && valueType == targetType.innerType) return true
        if (targetType is Type.Unsafe && valueType is Type.Unsafe && valueType.innerType == Type.Any) return true

        if (targetType is Type.ArrayList && valueType is Type.ArrayList) {
            if (targetType.elementType == Type.Any) return true
        }
        if (targetType is Type.List && valueType is Type.List) {
            if (targetType.elementType == Type.Any) return true
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
        if (leftType == Type.Error || rightType == Type.Error) return Type.Error
        if (leftType == Type.Nothing || rightType == Type.Nothing) return Type.Nothing

        return when (operator) {
            "+" -> {
                if (leftType is Type.ArrayList && (leftType.elementType == rightType || leftType.elementType == Type.Any)) {
                    leftType
                } else if (leftType == Type.String || rightType == Type.String) {
                    Type.String
                } else if (leftType == Type.Number && rightType == Type.Number) {
                    Type.Number
                } else {
                    errorReporter(
                        node,
                        "invalid operands for '+': expected (num, num), (string, any), or (array_list<T>, T), got ($leftType, $rightType)",
                        "use num for math, string for concatenation, or append to array_list"
                    )
                    Type.Error
                }
            }

            "-", "*", "/", "%" -> {
                if (leftType == Type.Number && rightType == Type.Number) {
                    Type.Number
                } else {
                    errorReporter(
                        node,
                        "operator '$operator' expects num, but got $leftType and $rightType",
                        "ensure both operands evaluate to num"
                    )
                    Type.Error
                }
            }

            "==", "!=" -> {
                if (leftType == rightType || leftType == Type.Any || rightType == Type.Any) {
                    Type.Boolean
                } else {
                    errorReporter(
                        node,
                        "comparison error: cannot compare $leftType with $rightType",
                        "compare only compatible types"
                    )
                    Type.Error
                }
            }

            "<", ">", "<=", ">=" -> {
                if (leftType == Type.Number && rightType == Type.Number) {
                    Type.Boolean
                } else {
                    errorReporter(
                        node,
                        "operator '$operator' only works on num, got $leftType and $rightType",
                        "convert operands to num before comparing"
                    )
                    Type.Error
                }
            }

            "&&", "||" -> {
                if (leftType == Type.Boolean && rightType == Type.Boolean) {
                    Type.Boolean
                } else {
                    errorReporter(
                        node,
                        "logical operator '$operator' expects bool, got $leftType and $rightType",
                        "use bool expressions for logical operations"
                    )
                    Type.Error
                }
            }

            else -> {
                errorReporter(
                    node,
                    "unknown binary operator: '$operator'",
                    "check the operator or your keyboard's health"
                )
                Type.Error
            }
        }
    }

    fun checkUnaryOperatorCompatibility(operator: String, operandType: Type, node: AstNode): Type {
        if (operandType == Type.Error) return Type.Error
        if (operandType == Type.Nothing) return Type.Nothing

        return when (operator) {
            "-" -> {
                if (operandType == Type.Number) {
                    Type.Number
                } else {
                    errorReporter(
                        node,
                        "negation expects a num, but got $operandType",
                        "place '-' only before num values"
                    )
                    Type.Error
                }
            }

            "!" -> {
                if (operandType == Type.Boolean) {
                    Type.Boolean
                } else {
                    errorReporter(
                        node,
                        "logical NOT expects a bool, but got $operandType",
                        "place '!' only before bool expressions"
                    )
                    Type.Error
                }
            }

            else -> {
                errorReporter(
                    node,
                    "unknown unary operator: '$operator'",
                    "check for typos"
                )
                Type.Error
            }
        }
    }

    fun checkConditionType(conditionType: Type, node: AstNode, statementType: String): Boolean {
        if (conditionType == Type.Error) return false
        if (conditionType != Type.Boolean) {
            errorReporter(
                node,
                "$statementType condition must be bool, but got $conditionType",
                "use an expression that evaluates to true or false"
            )
            return false
        }
        return true
    }

    fun checkFunctionCallArguments(call: CallExpression, calleeType: Type.Function, argTypes: List<Type>): Boolean {
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
            if (argType != Type.Error && argType != Type.Nothing && argType != expected && expected != Type.Any) {
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
        if (expectedReturnType == Type.Any || returnedType == Type.Error || returnedType == Type.Nothing) return true
        if (returnedType == expectedReturnType) return true
        if (expectedReturnType is Type.Unsafe && returnedType == expectedReturnType.innerType) return true
        if (expectedReturnType is Type.Unsafe && returnedType is Type.Unsafe && returnedType.innerType == Type.Any) return true

        errorReporter(
            node,
            "return type mismatch: expected $expectedReturnType, but got $returnedType",
            "return a value that matches the function's signature"
        )
        return false
    }
}
