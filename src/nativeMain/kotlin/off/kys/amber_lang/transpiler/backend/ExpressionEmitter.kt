package off.kys.amber_lang.transpiler.backend

import off.kys.amber_lang.runtime.RuntimeProvider
import off.kys.amber_lang.transpiler.ast.ArrayLiteralExpression
import off.kys.amber_lang.transpiler.ast.AssignmentExpression
import off.kys.amber_lang.transpiler.ast.BinaryExpression
import off.kys.amber_lang.transpiler.ast.CallExpression
import off.kys.amber_lang.transpiler.ast.Expression
import off.kys.amber_lang.transpiler.ast.IdentifierExpression
import off.kys.amber_lang.transpiler.ast.IndexAccessExpression
import off.kys.amber_lang.transpiler.ast.IsExpression
import off.kys.amber_lang.transpiler.ast.LiteralExpression
import off.kys.amber_lang.transpiler.ast.MemberAccessExpression
import off.kys.amber_lang.transpiler.ast.UnaryExpression
import off.kys.amber_lang.transpiler.type.Symbol
import off.kys.amber_lang.transpiler.type.Type

class ExpressionEmitter(
    private val writer: CodeWriter,
    private val expressionTypes: Map<Expression, Type>,
    private val resolvedSymbols: Map<Expression, Symbol>,
    private val symbolEmitter: SymbolEmitter,
    private val runtimeProvider: RuntimeProvider
) {
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
                emit(expression.callee)
                writer.write("(")
                expression.arguments.forEachIndexed { index, arg ->
                    emit(arg)
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
                // For now, we don't have full runtime type info in C
                writer.write("1") 
            }
            is IndexAccessExpression -> {
                emit(expression.target)
                writer.write("[")
                emit(expression.index)
                writer.write("]")
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
