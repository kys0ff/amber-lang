package off.kys.amber_lang.transpiler.backend

import off.kys.amber_lang.runtime.RuntimeProvider
import off.kys.amber_lang.transpiler.ast.Expression
import off.kys.amber_lang.transpiler.ast.FunctionDeclaration
import off.kys.amber_lang.transpiler.ast.Program
import off.kys.amber_lang.transpiler.ast.VariableDeclaration
import off.kys.amber_lang.transpiler.type.Symbol
import off.kys.amber_lang.transpiler.type.Type

class CBackend(
    private val expressionTypes: Map<Expression, Type>,
    private val resolvedSymbols: Map<Expression, Symbol>,
    private val runtimeProvider: RuntimeProvider
) : Backend {

    override fun generate(program: Program): String {
        val writer = CodeWriter()
        val symbolEmitter = SymbolEmitter()
        val typeMapper = CTypeMapper()
        val runtimeEmitter = RuntimeEmitter(writer)
        val expressionEmitter = ExpressionEmitter(writer, expressionTypes, resolvedSymbols, symbolEmitter, runtimeProvider)
        val statementEmitter = StatementEmitter(writer, expressionEmitter, symbolEmitter, typeMapper, expressionTypes, resolvedSymbols)

        runtimeEmitter.emitHeaders()
        runtimeEmitter.emitTypedefs()
        runtimeEmitter.emitIntrinsics()

        // Separate declarations from executable statements
        val functions = program.statements.filterIsInstance<FunctionDeclaration>().filter { !it.isIntrinsic }
        val variables = program.statements.filterIsInstance<VariableDeclaration>().filter { !it.isIntrinsic }

        functions.forEach { statementEmitter.emit(it) }
        variables.forEach { statementEmitter.emit(it, declarationOnly = true, isTopLevel = true) }

        writer.writeLine("int main(int argc, char** argv) {")
        writer.indent()
        writer.writeLine("GC_INIT();")
        
        program.statements.forEach { stmt ->
            when (stmt) {
                is VariableDeclaration -> if (!stmt.isIntrinsic) statementEmitter.emit(stmt, isTopLevel = true)
                is FunctionDeclaration -> {} // Already emitted
                else -> statementEmitter.emit(stmt)
            }
        }
        
        writer.writeLine("return 0;")
        writer.dedent()
        writer.writeLine("}")

        return writer.toString()
    }
}
