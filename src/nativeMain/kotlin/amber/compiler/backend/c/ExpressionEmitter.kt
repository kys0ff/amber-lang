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
import amber.compiler.ast.PanicExpression
import amber.compiler.ast.ReturnStatement
import amber.compiler.ast.UnaryExpression
import amber.compiler.ast.VariableDeclaration
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
                    } else if (paramType == Type.Any && argType == Type.Boolean) {
                        writer.write("__amber_rt_box_bool(")
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
                val descriptor = when (expression.typeName) {
                    "num" -> "&__amber_type_double"
                    "string" -> "&__amber_type_string"
                    "bool" -> "&__amber_type_bool"
                    else -> "NULL"
                }
                writer.write("__amber_rt_is_type(")
                emit(expression.left)
                writer.write(", $descriptor)")
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

    private fun emitSymbol(symbol: Symbol) {
        val platformName = runtimeProvider.getPlatformName(symbol)
        if (platformName != null) {
            writer.write(symbolEmitter.runtimeHelper(platformName))
        } else {
            writer.write(symbolEmitter.mangle(symbol.name, symbol.namespace))
        }
    }
}
