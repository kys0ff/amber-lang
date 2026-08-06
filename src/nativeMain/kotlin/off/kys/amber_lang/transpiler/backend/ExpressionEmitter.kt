package off.kys.amber_lang.transpiler.backend

import off.kys.amber_lang.runtime.RuntimeProvider
import off.kys.amber_lang.transpiler.ast.ArrayLiteralExpression
import off.kys.amber_lang.transpiler.ast.AssignmentExpression
import off.kys.amber_lang.transpiler.ast.BinaryExpression
import off.kys.amber_lang.transpiler.ast.CallExpression
import off.kys.amber_lang.transpiler.ast.CatchExpression
import off.kys.amber_lang.transpiler.ast.Expression
import off.kys.amber_lang.transpiler.ast.ExpressionStatement
import off.kys.amber_lang.transpiler.ast.IdentifierExpression
import off.kys.amber_lang.transpiler.ast.IndexAccessExpression
import off.kys.amber_lang.transpiler.ast.IsExpression
import off.kys.amber_lang.transpiler.ast.LiteralExpression
import off.kys.amber_lang.transpiler.ast.MemberAccessExpression
import off.kys.amber_lang.transpiler.ast.PanicExpression
import off.kys.amber_lang.transpiler.ast.ReturnStatement
import off.kys.amber_lang.transpiler.ast.UnaryExpression
import off.kys.amber_lang.transpiler.ast.VariableDeclaration
import off.kys.amber_lang.transpiler.type.Symbol
import off.kys.amber_lang.transpiler.type.Type

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
                if (expression.operator == "+" && leftType == Type.StringType) {
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
                val functionType = calleeSymbol?.type as? Type.FunctionType
                
                emit(expression.callee)
                writer.write("(")
                expression.arguments.forEachIndexed { index, arg ->
                    val paramType = functionType?.parameterTypes?.getOrNull(index) ?: Type.AnyType
                    val argType = expressionTypes[arg]
                    
                    if (paramType == Type.AnyType && argType == Type.NumberType) {
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
                // For native, this might need GC_malloc and initialization
                // Simplified for now: call a runtime helper
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
                // TODO: For now, we don't have full runtime type info in C
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
                val innerType = if (targetType is Type.UnsafeType) targetType.innerType else Type.AnyType
                val cInnerType = typeMapper.map(innerType)

                writer.write("({ ")
                writer.write("struct AMBER_RESULT __res = ")
                emit(expression.target)
                writer.write("; ")
                writer.write("${cInnerType} __final_res; ")
                writer.write("if (__amber_rt_is_error(__res)) { ")
                
                // Bind error variable if it has a name
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
                            // If returning an UnsafeType or Nothing, it's a real return (propagation)
                            if (valType is Type.UnsafeType || valType == Type.NothingType) {
                                writer.write("return ")
                                emit(stmt.value)
                                writer.write("; ")
                            } else {
                                // Otherwise it's a yield for the catch expression
                                writer.write("__final_res = ")
                                emit(stmt.value)
                                writer.write("; ")
                            }
                        } else if (stmt is VariableDeclaration) {
                            val vSymbol = resolvedSymbols[stmt.name]
                            val vType = expressionTypes[stmt.initializer] ?: vSymbol?.type ?: Type.AnyType
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
                            val vType = expressionTypes[stmt.initializer] ?: vSymbol?.type ?: Type.AnyType
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
                if (innerType == Type.NumberType) {
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
