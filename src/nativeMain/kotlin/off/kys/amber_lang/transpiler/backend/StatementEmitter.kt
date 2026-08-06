package off.kys.amber_lang.transpiler.backend

import off.kys.amber_lang.transpiler.ast.BlockStatement
import off.kys.amber_lang.transpiler.ast.EnumDeclaration
import off.kys.amber_lang.transpiler.ast.Expression
import off.kys.amber_lang.transpiler.ast.ExpressionStatement
import off.kys.amber_lang.transpiler.ast.FunctionDeclaration
import off.kys.amber_lang.transpiler.ast.IfStatement
import off.kys.amber_lang.transpiler.ast.ImportStatement
import off.kys.amber_lang.transpiler.ast.ReturnStatement
import off.kys.amber_lang.transpiler.ast.Statement
import off.kys.amber_lang.transpiler.ast.VariableDeclaration
import off.kys.amber_lang.transpiler.ast.WhileStatement
import off.kys.amber_lang.transpiler.type.Type

class StatementEmitter(
    private val writer: CodeWriter,
    private val expressionEmitter: ExpressionEmitter,
    private val symbolEmitter: SymbolEmitter,
    private val typeMapper: CTypeMapper,
    private val expressionTypes: Map<Expression, Type>
) {
    fun emit(statement: Statement) {
        when (statement) {
            is BlockStatement -> {
                writer.writeLine("{")
                writer.indent()
                statement.statements.forEach { emit(it) }
                writer.dedent()
                writer.writeLine("}")
            }
            is VariableDeclaration -> {
                val type = expressionTypes[statement.initializer] ?: Type.AnyType
                val cType = typeMapper.map(type)
                writer.write("    ".repeat(0)) // indent handled by writeLine usually
                // Actually, I'll just use writer.write for the parts and writeLine for the end
                writer.write("    ".repeat(0)) // dummy
                val name = symbolEmitter.mangle(statement.name.name)
                writer.write("${cType} ${name}")
                if (statement.initializer != null) {
                    writer.write(" = ")
                    expressionEmitter.emit(statement.initializer)
                }
                writer.writeLine(";")
            }
            is FunctionDeclaration -> {
                // Function declarations at top level or nested?
                // C doesn't support nested functions easily (extensions exist), 
                // but Amber might have them. For now, assume top level.
                val returnType = Type.UnitType // Should get from symbol table
                val cReturnType = typeMapper.map(returnType)
                val name = symbolEmitter.mangle(statement.name.name)
                writer.write("${cReturnType} ${name}(")
                statement.parameters.forEachIndexed { index, param ->
                    val paramType = Type.AnyType // Should get from symbol table
                    writer.write("${typeMapper.map(paramType)} ${symbolEmitter.mangle(param.name.name)}")
                    if (index < statement.parameters.size - 1) writer.write(", ")
                }
                writer.writeLine(") {")
                writer.indent()
                statement.body?.statements?.forEach { emit(it) }
                writer.dedent()
                writer.writeLine("}")
            }
            is ExpressionStatement -> {
                writer.write("    ".repeat(0))
                expressionEmitter.emit(statement.expression)
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
                writer.write("return")
                if (statement.value != null) {
                    writer.write(" ")
                    expressionEmitter.emit(statement.value)
                }
                writer.writeLine(";")
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
            is ImportStatement -> {
                // Imports are handled during semantic analysis, no C code needed
            }
        }
    }
}
