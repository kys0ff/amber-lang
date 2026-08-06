package amber.compiler.backend.c

import amber.compiler.ast.*
import amber.compiler.backend.Backend
import amber.compiler.symbol.Symbol
import amber.compiler.type.Type
import amber.runtime.RuntimeProvider
import amber.runtime.GarbageCollector
import amber.runtime.BoehmGC
import amber.runtime.NoGC

class CBackend(
    private val expressionTypes: Map<Expression, Type>,
    private val resolvedSymbols: Map<Expression, Symbol>,
    private val runtimeProvider: RuntimeProvider,
    private val gc: GarbageCollector = BoehmGC
) : Backend {

    override fun generate(
        program: Program,
        expressionTypes: Map<Expression, Type>,
        resolvedSymbols: Map<Expression, Symbol>
    ): String {
        val writer = CodeWriter()
        val symbolEmitter = SymbolEmitter()
        val typeMapper = CTypeMapper()
        val runtimeEmitter = RuntimeEmitter(writer, gc)
        val expressionEmitter = ExpressionEmitter(writer, expressionTypes, resolvedSymbols, symbolEmitter, runtimeProvider)
        val statementEmitter = StatementEmitter(writer, expressionEmitter, symbolEmitter, typeMapper, expressionTypes, resolvedSymbols)

        runtimeEmitter.emitHeaders()
        runtimeEmitter.emitTypedefs()
        runtimeEmitter.emitIntrinsics()
        runtimeEmitter.emitDefinitions()

        val functions = program.statements.filterIsInstance<FunctionDeclaration>().filter { !it.isIntrinsic }
        val variables = program.statements.filterIsInstance<VariableDeclaration>().filter { !it.isIntrinsic }

        functions.forEach { statementEmitter.emit(it) }
        variables.forEach { statementEmitter.emit(it, declarationOnly = true, isTopLevel = true) }

        writer.writeLine("int main(int argc, char** argv) {")
        writer.indent()
        if (gc.initCall.isNotEmpty()) {
            writer.writeLine(gc.initCall)
        }
        
        program.statements.forEach { stmt ->
            when (stmt) {
                is VariableDeclaration -> if (!stmt.isIntrinsic) statementEmitter.emit(stmt, isTopLevel = true)
                is FunctionDeclaration -> {}
                else -> statementEmitter.emit(stmt)
            }
        }
        
        writer.writeLine("return 0;")
        writer.dedent()
        writer.writeLine("}")

        return writer.toString()
    }
    
    fun generate(program: Program): String = generate(program, expressionTypes, resolvedSymbols)
}
