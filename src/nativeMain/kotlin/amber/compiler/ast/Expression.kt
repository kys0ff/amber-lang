package amber.compiler.ast

sealed class Expression(
    line: Int = -1,
    column: Int = -1
) : BaseAstNode(line, column)

class BinaryExpression(
    val left: Expression,
    val operator: String,
    val right: Expression,
    line: Int = -1,
    column: Int = -1,
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOf(left, right)
}

class IsExpression(
    val left: Expression,
    val typeName: String,
    line: Int = -1,
    column: Int = -1
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOf(left)
}

class UnaryExpression(
    val operator: String,
    val operand: Expression,
    line: Int = -1,
    column: Int = -1,
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOf(operand)
}

class LiteralExpression(
    val value: Any?,
    line: Int = -1,
    column: Int = -1
) : Expression(line, column) {
    override val children: List<AstNode> get() = emptyList()
}

class IdentifierExpression(
    val name: String,
    line: Int = -1,
    column: Int = -1,
    val isSynthetic: Boolean = false
) : Expression(line, column) {
    override val children: List<AstNode> get() = emptyList()
}

class CallExpression(
    val callee: Expression,
    val arguments: List<Expression>,
    line: Int = -1,
    column: Int = -1,
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOf(callee) + arguments
}

class PanicExpression(
    val message: Expression?,
    val isFatal: Boolean = false,
    line: Int = -1,
    column: Int = -1
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOfNotNull(message)
}

class CatchExpression(
    val target: Expression,
    val errorVarName: String,
    val errorVarType: String?,
    val body: BlockStatement,
    line: Int = -1,
    column: Int = -1
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOf(target, body)
}

class IndexAccessExpression(
    val target: Expression,
    val index: Expression,
    line: Int = -1,
    column: Int = -1,
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOf(target, index)
}

class ArrayLiteralExpression(
    val elements: List<Expression>,
    line: Int = -1,
    column: Int = -1,
) : Expression(line, column) {
    override val children: List<AstNode> get() = elements
}

class AssignmentExpression(
    val target: IdentifierExpression,
    val value: Expression,
    line: Int = -1,
    column: Int = -1,
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOf(target, value)
}

class MemberAccessExpression(
    val target: Expression,
    val member: IdentifierExpression,
    line: Int = -1,
    column: Int = -1,
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOf(target, member)
}

class StringTemplateExpression(
    val segments: List<Expression>,
    line: Int = -1,
    column: Int = -1,
) : Expression(line, column) {
    override val children: List<AstNode> get() = segments
}

class ErrorNode(
    val message: String,
    line: Int = -1,
    column: Int = -1
) : Expression(line, column) {
    override val children: List<AstNode> get() = emptyList()
}
