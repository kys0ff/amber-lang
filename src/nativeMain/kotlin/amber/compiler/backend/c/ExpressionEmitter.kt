package amber.compiler.backend.c

import amber.compiler.ast.ArrayLiteralExpression
import amber.compiler.ast.AssignmentExpression
import amber.compiler.ast.BinaryExpression
import amber.compiler.ast.CallExpression
import amber.compiler.ast.CatchExpression
import amber.compiler.ast.Expression
import amber.compiler.ast.ExpressionStatement
import amber.compiler.ast.IdentifierExpression
import amber.compiler.ast.IndexAccessExpression
import amber.compiler.ast.IsExpression
import amber.compiler.ast.LiteralExpression
import amber.compiler.ast.MemberAccessExpression
import amber.compiler.ast.NamedArgumentExpression
import amber.compiler.ast.PanicExpression
import amber.compiler.ast.ReturnStatement
import amber.compiler.ast.StringTemplateExpression
import amber.compiler.ast.UnaryExpression
import amber.compiler.ast.VariableDeclaration
import amber.compiler.symbol.Symbol
import amber.compiler.type.Type
import amber.runtime.RuntimeProvider

class ExpressionEmitter(
    private val writer: CodeWriter,
    private val expressionTypes: Map<Expression, Type>,
    private val resolvedSymbols: Map<Expression, Symbol>,
    private val resolvedIsTypes: Map<IsExpression, Type> = emptyMap(),
    private val symbolEmitter: SymbolEmitter,
    private val runtimeProvider: RuntimeProvider
) {
    private val typeMapper = CTypeMapper()

    fun emit(expression: Expression, expectedType: Type? = null) {
        val actualType = expressionTypes[expression]
        
        // Boxing: primitive -> any
        if (expectedType == Type.Any && actualType != null && actualType != Type.Any && actualType != Type.Error && actualType != Type.Nothing) {
            if (actualType == Type.Number) {
                writer.write("__amber_rt_box_double(")
                emitRaw(expression)
                writer.write(")")
                return
            }
            if (actualType == Type.Boolean) {
                writer.write("__amber_rt_box_bool(")
                emitRaw(expression)
                writer.write(")")
                return
            }
            if (actualType == Type.String) {
                writer.write("__amber_rt_box_string(")
                emitRaw(expression)
                writer.write(")")
                return
            }
            if (actualType is Type.Enum) {
                writer.write("__amber_rt_box_enum(")
                emitRaw(expression)
                writer.write(", &${symbolEmitter.mangle("type_${actualType.name}", actualType.moduleNamespace)})")
                return
            }
            if (actualType is Type.Struct) {
                val mangledName = symbolEmitter.mangle(actualType.name, actualType.namespace)
                writer.write("__amber_rt_box_$mangledName(")
                emitRaw(expression)
                writer.write(")")
                return
            }
        }
        
        // Unboxing: any -> primitive
        if (actualType == Type.Any && expectedType != null && expectedType != Type.Any && expectedType != Type.Error && expectedType != Type.Nothing) {
            if (expectedType == Type.Number) {
                writer.write("__amber_rt_unbox_double(")
                emitRaw(expression)
                writer.write(")")
                return
            }
            if (expectedType == Type.Boolean) {
                writer.write("__amber_rt_unbox_bool(")
                emitRaw(expression)
                writer.write(")")
                return
            }
            if (expectedType == Type.String) {
                writer.write("__amber_rt_unbox_string(")
                emitRaw(expression)
                writer.write(")")
                return
            }
            if (expectedType is Type.Enum) {
                writer.write("__amber_rt_unbox_enum(")
                emitRaw(expression)
                writer.write(")")
                return
            }
            if (expectedType is Type.Struct) {
                val mangledName = symbolEmitter.mangle(expectedType.name, expectedType.namespace)
                writer.write("__amber_rt_unbox_$mangledName(")
                emitRaw(expression)
                writer.write(")")
                return
            }
        }
        
        emitRaw(expression)
    }

    private fun emitRaw(expression: Expression) {
        when (expression) {
            is LiteralExpression -> {
                when (val value = expression.value) {
                    is String -> writer.write("\"${value.replace("\"", "\\\"")}\"")
                    is Number -> writer.write(value.toString())
                    is Boolean -> writer.write(if (value) "1" else "0")
                    null -> writer.write("NULL")
                    else -> writer.write(value.toString())
                }
            }
            is IdentifierExpression -> {
                val symbol = resolvedSymbols[expression]
                if (symbol != null) {
                    emitSymbol(symbol)
                } else {
                    writer.write(symbolEmitter.mangle(expression.name))
                }
            }
            is BinaryExpression -> {
                val leftType = expressionTypes[expression.left]
                val rightType = expressionTypes[expression.right]
                if (expression.operator == "+" && (leftType == Type.String || rightType == Type.String)) {
                    writer.write(symbolEmitter.runtimeHelper("str_concat"))
                    writer.write("(")
                    emitSegment(expression.left)
                    writer.write(", ")
                    emitSegment(expression.right)
                    writer.write(")")
                } else if (expression.operator == "+" && (leftType is Type.ArrayList || leftType is Type.List)) {
                    writer.write("({ ")
                    writer.write("${typeMapper.map(leftType)} _l = ")
                    emit(expression.left)
                    writer.write("; ")
                    writer.write(symbolEmitter.runtimeHelper("list_push"))
                    writer.write("(_l, ")
                    emit(expression.right, Type.Any)
                    writer.write("); _l; })")
                } else if ((expression.operator == "==" || expression.operator == "!=") && leftType == Type.String) {
                    if (expression.operator == "!=") writer.write("!")
                    writer.write("strcmp(")
                    emit(expression.left)
                    writer.write(", ")
                    emit(expression.right)
                    writer.write(") == 0")
                } else {
                    writer.write("(")
                    emit(expression.left)
                    writer.write(" ${expression.operator} ")
                    emit(expression.right)
                    writer.write(")")
                }
            }
            is CallExpression -> {
                val calleeType = expressionTypes[expression.callee]
                if (calleeType is Type.Struct) {
                    emitStructConstruction(expression, calleeType)
                    return
                }

                val calleeSymbol = resolvedSymbols[expression.callee]
                val functionType = calleeSymbol?.type as? Type.Function
                
                emit(expression.callee)
                writer.write("(")
                expression.arguments.forEachIndexed { index, arg ->
                    val paramType = functionType?.parameterTypes?.getOrNull(index) ?: Type.Any
                    val isMutated = functionType?.isParameterMutated?.getOrNull(index) ?: false
                    
                    if (isMutated && isPrimitive(paramType)) {
                        writer.write("&")
                    }
                    
                    emit(arg, paramType)
                    
                    if (index < expression.arguments.size - 1) {
                        writer.write(", ")
                    }
                }
                writer.write(")")
            }
            is UnaryExpression -> {
                writer.write(expression.operator)
                emit(expression.operand)
            }
            is ArrayLiteralExpression -> {
                writer.write(symbolEmitter.runtimeHelper("create_list"))
                writer.write("(")
                writer.write(expression.elements.size.toString())
                expression.elements.forEach { arg ->
                    writer.write(", ")
                    emit(arg, Type.Any)
                }
                writer.write(")")
            }
            is AssignmentExpression -> {
                val target = expression.target
                if (target is IdentifierExpression) {
                    val symbol = resolvedSymbols[target]
                    val name = symbol?.let { symbolEmitter.mangle(it.name, it.namespace) } ?: symbolEmitter.mangle(target.name)
                    if (symbol != null && symbol.isParameter && symbol.isMutated && isPrimitive(symbol.type)) {
                        writer.write("(*$name)")
                    } else {
                        writer.write(name)
                    }
                    writer.write(" = ")
                    val targetType = expressionTypes[target] ?: Type.Any
                    emit(expression.value, targetType)
                } else if (target is IndexAccessExpression) {
                    val targetType = expressionTypes[target.target]
                    if (targetType is Type.List || targetType is Type.ArrayList || targetType == Type.Any) {
                        writer.write(symbolEmitter.runtimeHelper("list_set"))
                        writer.write("(")
                        emit(target.target)
                        writer.write(", ")
                        emit(target.index)
                        writer.write(", ")
                        emit(expression.value, Type.Any)
                        writer.write(")")
                    } else {
                        emitRaw(target)
                        writer.write(" = ")
                        val valType = expressionTypes[target] ?: Type.Any
                        emit(expression.value, valType)
                    }
                } else {
                    emitRaw(target)
                    writer.write(" = ")
                    val targetType = expressionTypes[target] ?: Type.Any
                    emit(expression.value, targetType)
                }
            }
            is MemberAccessExpression -> {
                val targetType = expressionTypes[expression.target]
                if (targetType is Type.EnumNamespace) {
                    val enumType = targetType.enumType
                    writer.write(symbolEmitter.mangle("${enumType.name}_${expression.member.name}", enumType.moduleNamespace))
                    return
                }

                val symbol = resolvedSymbols[expression]
                if (symbol != null) {
                    emitSymbol(symbol)
                } else {
                    emit(expression.target)
                    writer.write(".")
                    writer.write(expression.member.name)
                }
            }
            is StringTemplateExpression -> {
                emitStringTemplate(expression.segments)
            }
            is NamedArgumentExpression -> {
                emit(expression.value)
            }
            is IsExpression -> {
                val targetType = resolvedIsTypes[expression]
                val descriptor = when (targetType) {
                    is Type.Number -> "&__amber_type_double"
                    is Type.String -> "&__amber_type_string"
                    is Type.Boolean -> "&__amber_type_bool"
                    is Type.List, is Type.ArrayList -> "&__amber_type_list"
                    is Type.Enum -> "&${symbolEmitter.mangle("type_${targetType.name}", targetType.moduleNamespace)}"
                    is Type.Struct -> "&${symbolEmitter.mangle("type_${targetType.name}", targetType.namespace)}"
                    else -> "NULL"
                }
                writer.write("__amber_rt_is_type(")
                emit(expression.left, Type.Any)
                writer.write(", $descriptor)")
            }
            is IndexAccessExpression -> {
                val targetType = expressionTypes[expression.target]
                if (targetType is Type.List || targetType is Type.ArrayList || targetType == Type.Any) {
                    writer.write(symbolEmitter.runtimeHelper("list_get"))
                    writer.write("(")
                    emit(expression.target)
                    writer.write(", ")
                    emit(expression.index)
                    writer.write(")")
                } else {
                    emit(expression.target)
                    writer.write("[")
                    emit(expression.index)
                    writer.write("]")
                }
            }
            is PanicExpression -> {
                if (expression.isFatal) {
                    writer.write("__amber_rt_panic(")
                    if (expression.message != null) emit(expression.message) else writer.write("\"panic\"")
                    writer.write(")")
                } else {
                    writer.write("__amber_rt_result_error(")
                    if (expression.message != null) emit(expression.message) else writer.write("\"error\"")
                    writer.write(")")
                }
            }
            is CatchExpression -> {
                val targetType = expressionTypes[expression.target]
                if (targetType !is Type.Unsafe && targetType != Type.Error) {
                    emit(expression.target)
                    return
                }
                val innerType = if (targetType is Type.Unsafe) targetType.innerType else Type.Any
                val cInnerType = typeMapper.map(innerType)
                
                val resVar = symbolEmitter.nextTemp()
                val finalResVar = symbolEmitter.nextTemp()

                writer.write("({ ")
                writer.write("struct AMBER_RESULT $resVar = ")
                emit(expression.target)
                writer.write("; ")
                writer.write("$cInnerType $finalResVar; ")
                writer.write("if (__amber_rt_is_error($resVar)) { ")
                
                if (expression.errorVarName != "_" && expression.errorVarName.isNotEmpty()) {
                    writer.write("const char* ${symbolEmitter.mangle(expression.errorVarName)} = $resVar.error_message; ")
                }
                
                expression.body.statements.forEachIndexed { index, stmt ->
                    val isLast = index == expression.body.statements.size - 1
                    if (isLast) {
                        if (stmt is ExpressionStatement) {
                            writer.write("$finalResVar = ")
                            emit(stmt.expression)
                            writer.write("; ")
                        } else if (stmt is ReturnStatement && stmt.value != null) {
                            val valType = expressionTypes[stmt.value]
                            if (valType is Type.Unsafe || valType == Type.Nothing) {
                                writer.write("return ")
                                emit(stmt.value)
                                writer.write("; ")
                            } else {
                                writer.write("$finalResVar = ")
                                emit(stmt.value)
                                writer.write("; ")
                            }
                        } else if (stmt is VariableDeclaration) {
                            val vSymbol = resolvedSymbols[stmt.name]
                            val vType = expressionTypes[stmt.initializer] ?: vSymbol?.type ?: Type.Any
                            val vName = vSymbol?.let { symbolEmitter.mangle(it.name, it.namespace) } ?: symbolEmitter.mangle(stmt.name.name)
                            writer.write("${typeMapper.map(vType)} $vName")
                            if (stmt.initializer != null) {
                                writer.write(" = ")
                                emit(stmt.initializer)
                            }
                            writer.write("; ")
                            writer.write("$finalResVar = $vName; ")
                        } else {
                            writer.write("/* unsupported last stmt in catch */ ")
                        }
                    } else {
                        if (stmt is ExpressionStatement) {
                            emit(stmt.expression)
                            writer.write("; ")
                        } else if (stmt is VariableDeclaration) {
                            val vSymbol = resolvedSymbols[stmt.name]
                            val vType = expressionTypes[stmt.initializer] ?: vSymbol?.type ?: Type.Any
                            val vName = vSymbol?.let { symbolEmitter.mangle(it.name, it.namespace) } ?: symbolEmitter.mangle(stmt.name.name)
                            writer.write("${typeMapper.map(vType)} $vName")
                            if (stmt.initializer != null) {
                                writer.write(" = ")
                                emit(stmt.initializer)
                            }
                            writer.write("; ")
                        }
                    }
                }
                
                writer.write("} else { ")
                writer.write("$finalResVar = ")
                if (innerType == Type.Number) {
                    writer.write("__amber_rt_unbox_double(__amber_rt_unwrap($resVar))")
                } else {
                    writer.write("(${cInnerType})__amber_rt_unwrap($resVar)")
                }
                writer.write("; ")
                writer.write("} ")
                writer.write("$finalResVar; ")
                writer.write("})")
            }
            else -> writer.write("/* unsupported expression: ${expression::class.simpleName} */")
        }
    }

    private fun isPrimitive(type: Type): Boolean {
        return type == Type.Number || type == Type.Boolean || type == Type.String || type == Type.Char || type is Type.Enum || type is Type.Struct || type is Type.Unsafe
    }

    private fun emitSymbol(symbol: Symbol) {
        val platformName = runtimeProvider.getPlatformName(symbol)
        if (platformName != null) {
            writer.write(symbolEmitter.runtimeHelper(platformName))
        } else {
            val name = symbolEmitter.mangle(symbol.name, symbol.namespace)
            if (symbol.isParameter && symbol.isMutated && isPrimitive(symbol.type)) {
                writer.write("(*$name)")
            } else {
                writer.write(name)
            }
        }
    }

    private fun emitStringTemplate(segments: List<Expression>) {
        if (segments.isEmpty()) {
            writer.write("\"\"")
            return
        }
        if (segments.size == 1) {
            emitSegment(segments[0])
            return
        }
        writer.write("__amber_rt_str_concat(")
        emitSegment(segments[0])
        writer.write(", ")
        emitStringTemplate(segments.drop(1))
        writer.write(")")
    }

    private fun emitStructConstruction(call: CallExpression, structType: Type.Struct) {
        val mangledName = symbolEmitter.mangle(structType.name, structType.namespace)
        writer.write("({ ")
        writer.write("$mangledName __am_tmp = {0}; ")

        val fieldMap = structType.fields
        val supplied = mutableMapOf<String, Expression>()

        var positionalIndex = 0
        call.arguments.forEach { arg ->
            if (arg is NamedArgumentExpression) {
                supplied[arg.name.name] = arg.value
            } else {
                val fieldName = fieldMap.keys.toList().getOrNull(positionalIndex++)
                if (fieldName != null) {
                    supplied[fieldName] = arg
                }
            }
        }

        fieldMap.values.forEach { field ->
            writer.write("__am_tmp.${field.name} = ")
            val expr = supplied[field.name]
            if (expr != null) {
                emit(expr, field.type)
            } else if (field.defaultValue != null) {
                emit(field.defaultValue, field.type)
            } else {
                writer.write("0")
            }
            writer.write("; ")
        }

        writer.write("__am_tmp; })")
    }

    private fun emitSegment(expr: Expression) {
        val type = expressionTypes[expr] ?: Type.Any
        if (type == Type.String) {
            emit(expr)
        } else {
            writer.write("__amber_rt_to_string(")
            emit(expr, Type.Any)
            writer.write(")")
        }
    }
}
