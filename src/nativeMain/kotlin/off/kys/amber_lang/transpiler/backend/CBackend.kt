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
        val declarations = program.statements.filter {
            (it is FunctionDeclaration && !it.isIntrinsic) ||
            (it is VariableDeclaration && !it.isIntrinsic)
        }
        val executable = program.statements.filter { it !is FunctionDeclaration && it !is VariableDeclaration }

        declarations.forEach { statementEmitter.emit(it) }

        writer.writeLine("int main(int argc, char** argv) {")
        writer.indent()
        writer.writeLine("GC_INIT();")
        executable.forEach { statementEmitter.emit(it) }
        writer.writeLine("return 0;")
        writer.dedent()
        writer.writeLine("}")

        return writer.toString()
    }
}
