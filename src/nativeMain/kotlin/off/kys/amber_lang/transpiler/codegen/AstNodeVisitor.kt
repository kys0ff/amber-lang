package off.kys.amber_lang.transpiler.codegen

import off.kys.amber_lang.transpiler.ast.*

interface AstNodeVisitor<T> {
    fun visitProgram(program: Program): T
    fun visitBlockStatement(block: BlockStatement): T
    fun visitVariableDeclaration(declaration: VariableDeclaration): T
    fun visitEnumDeclaration(declaration: EnumDeclaration): T
    fun visitLiteralExpression(literal: LiteralExpression): T
    fun visitIdentifierExpression(identifier: IdentifierExpression): T
    fun visitExpressionStatement(statement: ExpressionStatement): T
    fun visitBinaryExpression(expr: BinaryExpression): T
    fun visitUnaryExpression(expr: UnaryExpression): T
    fun visitAssignmentExpression(expr: AssignmentExpression): T
    fun visitIfStatement(stmt: IfStatement): T
    fun visitWhileStatement(stmt: WhileStatement): T
    fun visitFunctionDeclaration(func: FunctionDeclaration): T
    fun visitCallExpression(call: CallExpression): T
    fun visitReturnStatement(stmt: ReturnStatement): T
    fun visitImportStatement(stmt: ImportStatement): T
    fun visitMemberAccessExpression(expr: MemberAccessExpression): T
    fun visitPanicExpression(expr: PanicExpression): T
    fun visitCatchExpression(expr: CatchExpression): T
    fun visitErrorNode(errorNode: ErrorNode): T
}
