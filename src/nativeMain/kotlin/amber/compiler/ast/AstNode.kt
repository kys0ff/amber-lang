package amber.compiler.ast

sealed interface AstNode {
    val line: Int
    val column: Int
    val length: Int?
    val docstring: String?
    val children: List<AstNode>
}

abstract class BaseAstNode(
    override val line: Int = -1,
    override val column: Int = -1,
    override val length: Int? = null,
    override val docstring: String? = null
) : AstNode

class Program(
    val statements: List<Statement>,
    line: Int = -1,
    column: Int = -1,
    length: Int? = null,
    docstring: String? = null
) : BaseAstNode(line, column, length, docstring) {
    override val children: List<AstNode> get() = statements
}

class Parameter(
    val name: IdentifierExpression,
    val typeAnnotation: String?,
    val defaultValue: Expression?,
    line: Int = -1,
    column: Int = -1,
    length: Int? = null,
    docstring: String? = null
) : BaseAstNode(line, column, length, docstring) {
    override val children: List<AstNode> get() = listOfNotNull(name, defaultValue)
}
