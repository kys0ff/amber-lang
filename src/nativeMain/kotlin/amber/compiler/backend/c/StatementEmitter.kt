package amber.compiler.backend.c

import amber.compiler.ast.*
import amber.compiler.symbol.Symbol
import amber.compiler.type.Type

class StatementEmitter(
    private val writer: CodeWriter,
    private val expressionEmitter: ExpressionEmitter,
    private val symbolEmitter: SymbolEmitter,
    private val typeMapper: CTypeMapper,
    private val expressionTypes: Map<Expression, Type>,
    private val resolvedSymbols: Map<Expression, Symbol>
) {
    private val returnTypeStack = mutableListOf<Type>()

    fun emit(statement: Statement, declarationOnly: Boolean = false, isTopLevel: Boolean = false) {
        when (statement) {
            is BlockStatement -> {
                writer.writeLine("{")
                writer.indent()
                statement.statements.forEach { emit(it) }
                writer.dedent()
                writer.writeLine("}")
            }
            is VariableDeclaration -> {
                val symbol = resolvedSymbols[statement.name]
                val type = expressionTypes[statement.initializer] ?: symbol?.type ?: Type.Any
                val cType = typeMapper.map(type)
                val name = symbol?.let { symbolEmitter.mangle(it.name, it.namespace) } ?: symbolEmitter.mangle(statement.name.name)
                
                if (declarationOnly) {
                    writer.writeLine("${cType} ${name};")
                } else {
                    if (isTopLevel) {
                        if (statement.initializer != null) {
                            writer.write("${name} = ")
                            expressionEmitter.emit(statement.initializer)
                            writer.writeLine(";")
                        }
                    } else {
                        writer.write("${cType} ${name}")
                        if (statement.initializer != null) {
                            writer.write(" = ")
                            expressionEmitter.emit(statement.initializer)
                        }
                        writer.writeLine(";")
                    }
                }
            }
            is FunctionDeclaration -> {
                val symbol = resolvedSymbols[statement.name]
                val returnType = (symbol?.type as? Type.Function)?.returnType ?: Type.Unit
                
                val cReturnType = typeMapper.map(returnType)
                val name = symbol?.let { symbolEmitter.mangle(it.name, it.namespace) } ?: symbolEmitter.mangle(statement.name.name)
                writer.write("${cReturnType} ${name}(")
                statement.parameters.forEachIndexed { index, param ->
                    val paramSymbol = resolvedSymbols[param.name]
                    val paramType = paramSymbol?.type ?: Type.Any
                    val paramName = paramSymbol?.let { symbolEmitter.mangle(it.name, it.namespace) } ?: symbolEmitter.mangle(param.name.name)
                    writer.write("${typeMapper.map(paramType)} ${paramName}")
                    if (index < statement.parameters.size - 1) writer.write(", ")
                }
                writer.writeLine(") {")
                writer.indent()
                returnTypeStack.add(returnType)
                statement.body?.statements?.forEachIndexed { index, bodyStmt ->
                    val isLast = index == statement.body.statements.size - 1
                    if (isLast && bodyStmt is ExpressionStatement && returnType != Type.Unit) {
                        emit(ReturnStatement(bodyStmt.expression, bodyStmt.line, bodyStmt.column))
                    } else {
                        emit(bodyStmt)
                    }
                }
                returnTypeStack.removeAt(returnTypeStack.size - 1)
                writer.dedent()
                writer.writeLine("}")
            }
            is ExpressionStatement -> {
                val expr = statement.expression
                val currentReturnType = returnTypeStack.lastOrNull()
                
                if (expr is PanicExpression && !expr.isFatal && currentReturnType is Type.Unsafe) {
                    writer.write("return ")
                    expressionEmitter.emit(expr)
                } else {
                    expressionEmitter.emit(expr)
                }
                writer.writeLine(";")
            }
            is IfStatement -> {
                writer.write("if (")
                expressionEmitter.emit(statement.condition)
                writer.writeLine(") {")
                writer.indent()
                emit(statement.thenBranch)
                writer.dedent()
                writer.writeLine("}")
                if (statement.elseBranch != null) {
                    writer.writeLine("else {")
                    writer.indent()
                    emit(statement.elseBranch)
                    writer.dedent()
                    writer.writeLine("}")
                }
            }
            is WhileStatement -> {
                writer.write("while (")
                expressionEmitter.emit(statement.condition)
                writer.writeLine(") {")
                writer.indent()
                emit(statement.body)
                writer.dedent()
                writer.writeLine("}")
            }
            is ReturnStatement -> {
                val currentReturnType = returnTypeStack.lastOrNull()
                if (currentReturnType is Type.Unsafe) {
                    val exprType = statement.value?.let { expressionTypes[it] }
                    if (exprType is Type.Unsafe || exprType == Type.Nothing) {
                        writer.write("return ")
                        statement.value?.let { expressionEmitter.emit(it) } ?: writer.write("__amber_rt_result_error(\"null return\")")
                        writer.writeLine(";")
                    } else {
                        writer.write("return __amber_rt_result_success(")
                        if (statement.value != null) {
                            if (exprType == Type.Number) {
                                writer.write("__amber_rt_box_double(")
                                expressionEmitter.emit(statement.value)
                                writer.write(")")
                            } else {
                                expressionEmitter.emit(statement.value)
                            }
                        } else {
                            writer.write("NULL")
                        }
                        writer.writeLine(");")
                    }
                } else {
                    writer.write("return")
                    if (statement.value != null && currentReturnType != Type.Unit) {
                        writer.write(" ")
                        expressionEmitter.emit(statement.value)
                    }
                    writer.writeLine(";")
                }
            }
            is EnumDeclaration -> {
                writer.writeLine("enum ${symbolEmitter.mangle(statement.name.name)} {")
                writer.indent()
                statement.variants.forEachIndexed { index, variant ->
                    writer.writeLine("${symbolEmitter.mangle(variant.name)}${if (index < statement.variants.size - 1) "," else ""}")
                }
                writer.dedent()
                writer.writeLine("};")
            }
            is ImportStatement -> {}
        }
    }
}
