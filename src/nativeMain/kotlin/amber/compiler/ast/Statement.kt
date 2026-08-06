package amber.compiler.ast

sealed class Statement(
    line: Int = -1,
    column: Int = -1
) : BaseAstNode(line, column)

class ImportStatement(
    val path: String,
    val asName: IdentifierExpression?,
    line: Int = -1,
    column: Int = -1,
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOfNotNull(asName)
}

class FunctionDeclaration(
    val name: IdentifierExpression,
    val parameters: List<Parameter>,
    val returnTypeAnnotation: String?,
    val body: BlockStatement?,
    val isIntrinsic: Boolean = false,
    line: Int = -1,
    column: Int = -1,
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOf(name) + parameters + listOfNotNull(body)
}

class VariableDeclaration(
    val name: IdentifierExpression,
    val typeAnnotation: String?,
    val initializer: Expression?,
    val isMutable: Boolean,
    val isIntrinsic: Boolean = false,
    line: Int = -1,
    column: Int = -1,
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOfNotNull(name, initializer)
}

class EnumDeclaration(
    val name: IdentifierExpression,
    val variants: List<IdentifierExpression>,
    line: Int = -1,
    column: Int = -1,
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOf(name) + variants
}

class IfStatement(
    val condition: Expression,
    val thenBranch: BlockStatement,
    val elseBranch: BlockStatement?,
    line: Int = -1,
    column: Int = -1,
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOfNotNull(condition, thenBranch, elseBranch)
}

class WhileStatement(
    val condition: Expression,
    val body: BlockStatement,
    line: Int = -1,
    column: Int = -1,
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOf(condition, body)
}

class ReturnStatement(
    val value: Expression?,
    line: Int = -1,
    column: Int = -1
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOfNotNull(value)
}

class BlockStatement(
    val statements: List<Statement>,
    line: Int = -1,
    column: Int = -1
) : Statement(line, column) {
    override val children: List<AstNode> get() = statements
}

class StructField(
    val name: IdentifierExpression,
    val typeAnnotation: String?,
    val defaultValue: Expression?,
    line: Int = -1,
    column: Int = -1,
) : BaseAstNode(line, column) {
    override val children: List<AstNode> get() = listOfNotNull(name, defaultValue)
}

class StructDeclaration(
    val name: IdentifierExpression,
    val fields: List<StructField>,
    line: Int = -1,
    column: Int = -1,
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOf(name) + fields
}

class ExpressionStatement(
    val expression: Expression,
    line: Int = -1,
    column: Int = -1
) : Statement(line, column) {
    override val children: List<AstNode> get() = listOf(expression)
}
