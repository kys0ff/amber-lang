package amber.compiler.backend.c

import amber.compiler.ast.*
import amber.compiler.symbol.Symbol
import amber.compiler.type.Type
import amber.runtime.RuntimeProvider

class ExpressionEmitter(
    private val writer: CodeWriter,
    private val expressionTypes: Map<Expression, Type>,
    private val resolvedSymbols: Map<Expression, Symbol>,
    private val symbolEmitter: SymbolEmitter,
    private val runtimeProvider: RuntimeProvider
) {
    private val typeMapper = CTypeMapper()

    fun emit(expression: Expression) {
        when (expression) {
            is LiteralExpression -> {
                val value = expression.value
                when (value) {
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
                if (expression.operator == "+" && leftType == Type.String) {
                    writer.write(symbolEmitter.runtimeHelper("str_concat"))
                    writer.write("(")
                    emit(expression.left)
                    writer.write(", ")
                    emit(expression.right)
                    writer.write(")")
                } else {
                    writer.write("(")
                    emit(expression.left)
                    writer.write(" ${expression.operator} ")
                    emit(expression.right)
                    writer.write(")")
                }
            }
            is CallExpression -> {
                val calleeSymbol = resolvedSymbols[expression.callee]
                val functionType = calleeSymbol?.type as? Type.Function
                
                emit(expression.callee)
                writer.write("(")
                expression.arguments.forEachIndexed { index, arg ->
                    val paramType = functionType?.parameterTypes?.getOrNull(index) ?: Type.Any
                    val argType = expressionTypes[arg]
                    
                    if (paramType == Type.Any && argType == Type.Number) {
                        writer.write("__amber_rt_box_double(")
                        emit(arg)
                        writer.write(")")
                    } else {
                        emit(arg)
                    }
                    
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
                    emit(arg)
                }
                writer.write(")")
            }
            is AssignmentExpression -> {
                val symbol = resolvedSymbols[expression.target]
                val name = symbol?.let { symbolEmitter.mangle(it.name, it.namespace) } ?: symbolEmitter.mangle(expression.target.name)
                writer.write(name)
                writer.write(" = ")
                emit(expression.value)
            }
            is MemberAccessExpression -> {
                val symbol = resolvedSymbols[expression]
                if (symbol != null) {
                    emitSymbol(symbol)
                } else {
                    emit(expression.target)
                    writer.write(".")
                    writer.write(expression.member.name)
                }
            }
            is IsExpression -> {
                writer.write("1") 
            }
            is IndexAccessExpression -> {
                emit(expression.target)
                writer.write("[")
                emit(expression.index)
                writer.write("]")
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
                val innerType = if (targetType is Type.Unsafe) targetType.innerType else Type.Any
                val cInnerType = typeMapper.map(innerType)

                writer.write("({ ")
                writer.write("struct AMBER_RESULT __res = ")
                emit(expression.target)
                writer.write("; ")
                writer.write("${cInnerType} __final_res; ")
                writer.write("if (__amber_rt_is_error(__res)) { ")
                
                if (expression.errorVarName != "_" && expression.errorVarName.isNotEmpty()) {
                    writer.write("const char* ${symbolEmitter.mangle(expression.errorVarName)} = __res.error_message; ")
                }
                
                expression.body.statements.forEachIndexed { index, stmt ->
                    val isLast = index == expression.body.statements.size - 1
                    if (isLast) {
                        if (stmt is ExpressionStatement) {
                            writer.write("__final_res = ")
                            emit(stmt.expression)
                            writer.write("; ")
                        } else if (stmt is ReturnStatement && stmt.value != null) {
                            val valType = expressionTypes[stmt.value]
                            if (valType is Type.Unsafe || valType == Type.Nothing) {
                                writer.write("return ")
                                emit(stmt.value)
                                writer.write("; ")
                            } else {
                                writer.write("__final_res = ")
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
                            writer.write("__final_res = $vName; ")
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
                writer.write("__final_res = ")
                if (innerType == Type.Number) {
                    writer.write("__amber_rt_unbox_double(__amber_rt_unwrap(__res))")
                } else {
                    writer.write("(${cInnerType})__amber_rt_unwrap(__res)")
                }
                writer.write("; ")
                writer.write("} ")
                writer.write("__final_res; ")
                writer.write("})")
            }
            else -> writer.write("/* unsupported expression: ${expression::class.simpleName} */")
        }
    }

    private fun emitSymbol(symbol: Symbol) {
        val platformName = runtimeProvider.getPlatformName(symbol)
        if (platformName != null) {
            writer.write(symbolEmitter.runtimeHelper(platformName))
        } else {
            writer.write(symbolEmitter.mangle(symbol.name, symbol.namespace))
        }
    }
}
