package amber.compiler.backend

import amber.compiler.ast.Program
import amber.compiler.symbol.Symbol
import amber.compiler.type.Type
import amber.compiler.ast.Expression

interface Backend {
    fun generate(
        program: Program,
        expressionTypes: Map<Expression, Type>,
        resolvedSymbols: Map<Expression, Symbol>
    ): String
}
