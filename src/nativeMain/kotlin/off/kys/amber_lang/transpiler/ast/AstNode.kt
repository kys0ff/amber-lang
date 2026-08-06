package off.kys.amber_lang.transpiler.ast

sealed class AstNode(
    open val line: Int = -1,
    open val column: Int = -1,
    open val length: Int? = null
) {
    // This allows the generator (and any future tools) to crawl the tree
    abstract val children: List<AstNode>
}

// Program structure
class Program(
    val statements: List<Statement>,
    override val line: Int = -1,
    override val column: Int = -1
) : AstNode(line, column) {
    override val children: List<AstNode> get() = statements
}

sealed class Statement(override val line: Int = -1, override val column: Int = -1) : AstNode(line, column)

class ImportStatement(
    val path: String,
    val asName: IdentifierExpression?,
    override val line: Int = -1,
    override val column: Int = -1,
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOfNotNull(asName)
}

class Parameter(
    val name: IdentifierExpression,
    val typeAnnotation: String?,
    val defaultValue: Expression?,
    override val line: Int = -1,
    override val column: Int = -1,
) : AstNode(line, column) {
    override val children: List<AstNode> get() = listOfNotNull(name, defaultValue)
}

class FunctionDeclaration(
    val name: IdentifierExpression,
    val parameters: List<Parameter>,
    val returnTypeAnnotation: String?,
    val body: BlockStatement?,
    val isIntrinsic: Boolean = false,
    override val line: Int = -1,
    override val column: Int = -1,
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOf(name) + parameters + listOfNotNull(body)
}

class VariableDeclaration(
    val name: IdentifierExpression,
    val typeAnnotation: String?,
    val initializer: Expression?,
    val isMutable: Boolean,
    val isIntrinsic: Boolean = false,
    override val line: Int = -1,
    override val column: Int = -1,
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOfNotNull(name, initializer)
}

class EnumDeclaration(
    val name: IdentifierExpression,
    val variants: List<IdentifierExpression>,
    override val line: Int = -1,
    override val column: Int = -1,
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOf(name) + variants
}

class IfStatement(
    val condition: Expression,
    val thenBranch: BlockStatement,
    val elseBranch: BlockStatement?,
    override val line: Int = -1,
    override val column: Int = -1,
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOfNotNull(condition, thenBranch, elseBranch)
}

class WhileStatement(
    val condition: Expression,
    val body: BlockStatement,
    override val line: Int = -1,
    override val column: Int = -1,
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOf(condition, body)
}

class ReturnStatement(
    val value: Expression?,
    override val line: Int = -1,
    override val column: Int = -1
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOfNotNull(value)
}

class BlockStatement(
    val statements: List<Statement>,
    override val line: Int = -1,
    override val column: Int = -1
) : Statement(line, column) {
    override val children: List<AstNode> get() = statements
}

class ExpressionStatement(
    val expression: Expression,
    override val line: Int = -1,
    override val column: Int = -1
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOf(expression)
}

sealed class Expression(override val line: Int = -1, override val column: Int = -1) : AstNode(line, column)

class BinaryExpression(
    val left: Expression,
    val operator: String,
    val right: Expression,
    override val line: Int = -1,
    override val column: Int = -1,
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOf(left, right)
}

class IsExpression(
    val left: Expression,
    val typeName: String,
    override val line: Int = -1,
    override val column: Int = -1
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOf(left)
}

class UnaryExpression(
    val operator: String,
    val operand: Expression,
    override val line: Int = -1,
    override val column: Int = -1,
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOf(operand)
}

class LiteralExpression(
    val value: Any?,
    override val line: Int = -1,
    override val column: Int = -1
) : Expression(line, column) {
    override val children: List<AstNode> get() = emptyList()
}

class IdentifierExpression(
    val name: String,
    override val line: Int = -1,
    override val column: Int = -1,
    val isSynthetic: Boolean = false
) : Expression(line, column) {
    override val children: List<AstNode> get() = emptyList()
}

class CallExpression(
    val callee: Expression,
    val arguments: List<Expression>,
    override val line: Int = -1,
    override val column: Int = -1,
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOf(callee) + arguments
}

class PanicExpression(
    val message: Expression?,
    val isFatal: Boolean = false,
    override val line: Int = -1,
    override val column: Int = -1
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOfNotNull(message)
}

class CatchExpression(
    val target: Expression,
    val errorVarName: String,
    val errorVarType: String?,
    val body: BlockStatement,
    override val line: Int = -1,
    override val column: Int = -1
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOf(target, body)
}

class IndexAccessExpression(
    val target: Expression,
    val index: Expression,
    override val line: Int = -1,
    override val column: Int = -1,
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOf(target, index)
}

class ArrayLiteralExpression(
    val elements: List<Expression>,
    override val line: Int = -1,
    override val column: Int = -1,
) : Expression(line, column) {
    override val children: List<AstNode> get() = elements
}

class AssignmentExpression(
    val target: IdentifierExpression,
    val value: Expression,
    override val line: Int = -1,
    override val column: Int = -1,
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOf(target, value)
}

class MemberAccessExpression(
    val target: Expression,
    val member: IdentifierExpression,
    override val line: Int = -1,
    override val column: Int = -1,
) : Expression(line, column) {
    override val children: List<AstNode> get() = listOf(target, member)
}

class ErrorNode(
    val message: String,
    override val line: Int = -1,
    override val column: Int = -1
) : Expression(line, column) {
    override val children: List<AstNode> get() = emptyList()
}
