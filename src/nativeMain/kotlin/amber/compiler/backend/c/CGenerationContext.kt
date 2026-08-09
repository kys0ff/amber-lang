package amber.compiler.backend.c

import amber.compiler.ast.Expression
import amber.compiler.ast.IsExpression
import amber.compiler.symbol.Symbol
import amber.compiler.type.Type
import amber.runtime.RuntimeProvider

class CGenerationContext(
    val writer: CodeWriter,
    val expressionTypes: Map<Expression, Type>,
    val resolvedSymbols: Map<Expression, Symbol>,
    val resolvedIsTypes: Map<IsExpression, Type>,
    val symbolEmitter: SymbolEmitter,
    val typeMapper: CTypeMapper,
    val runtimeProvider: RuntimeProvider
)
