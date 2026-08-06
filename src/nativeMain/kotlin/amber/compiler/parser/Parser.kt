package amber.compiler.parser

import amber.compiler.ast.ArrayLiteralExpression
import amber.compiler.ast.AssignmentExpression
import amber.compiler.ast.BinaryExpression
import amber.compiler.ast.BlockStatement
import amber.compiler.ast.CallExpression
import amber.compiler.ast.CatchExpression
import amber.compiler.ast.EnumDeclaration
import amber.compiler.ast.ErrorNode
import amber.compiler.ast.Expression
import amber.compiler.ast.ExpressionStatement
import amber.compiler.ast.FunctionDeclaration
import amber.compiler.ast.IdentifierExpression
import amber.compiler.ast.IfStatement
import amber.compiler.ast.ImportStatement
import amber.compiler.ast.IndexAccessExpression
import amber.compiler.ast.IsExpression
import amber.compiler.ast.LiteralExpression
import amber.compiler.ast.MemberAccessExpression
import amber.compiler.ast.PanicExpression
import amber.compiler.ast.Parameter
import amber.compiler.ast.Program
import amber.compiler.ast.ReturnStatement
import amber.compiler.ast.Statement
import amber.compiler.ast.UnaryExpression
import amber.compiler.ast.VariableDeclaration
import amber.compiler.ast.WhileStatement
import amber.compiler.diagnostic.DiagnosticSeverity
import amber.compiler.diagnostic.SyntaxError
import amber.compiler.lexer.Lexer
import amber.compiler.lexer.Token

private class ParserRecoveryException : RuntimeException()

class Parser(private val tokens: List<Token>, private val filePath: String) {
    private val state = ParserState()
    val errors = mutableListOf<SyntaxError>()

    private fun peek(): Token =
        tokens.getOrElse(state.currentIndex) { Token.EOF() }.let(::normalizeToken)

    private fun consume(): Token {
        val token = peek()
        if (token !is Token.EOF) state.currentIndex++
        return token
    }

    private fun normalizeToken(token: Token): Token {
        return if (token is Token.Unknown && token.value == ".") {
            Token.Separator(".", token.line, token.column)
        } else {
            token
        }
    }

    private fun reportError(message: String, line: Int = peek().line, column: Int = peek().column) {
        val finalMessage =
            if (peek() is Token.EOF && (message.contains("'EOF'") || message.contains("'eof'"))) {
                message.replace("'EOF'", "end of file", ignoreCase = true)
            } else {
                message
            }
        errors.add(SyntaxError(filePath, line, column, finalMessage.lowercase()))
    }

    private fun Token.value(): String = when (this) {
        is Token.Identifier -> this.value
        is Token.Keyword -> this.value
        is Token.StringLiteral -> this.value
        is Token.NumberLiteral -> this.value
        is Token.BooleanLiteral -> this.value
        is Token.NullLiteral -> this.value
        is Token.Operator -> this.value
        is Token.Separator -> this.value
        is Token.Comment -> this.value
        is Token.Whitespace -> this.value
        is Token.Newline -> this.value
        is Token.Unknown -> this.value
        is Token.EOF -> "EOF"
        is Token.CharLiteral -> this.value
    }

    private fun expectToken(expectedValue: String, errorMessage: String? = null): Token {
        val actual = peek()
        if (actual.value() == expectedValue) return consume()

        reportError(errorMessage ?: "expected '$expectedValue' but got '${actual.value()}'")
        throw ParserRecoveryException()
    }

    private fun expectIdentifier(): Token.Identifier {
        val token = peek()
        if (token is Token.Identifier) return consume() as Token.Identifier

        reportError("expected identifier but got '${token.value()}'")
        throw ParserRecoveryException()
    }

    private fun skipNewlines() {
        while (peek() is Token.Newline) consume()
    }

    private fun synchronize() {
        consume()
        while (peek() !is Token.EOF) {
            if (tokens.getOrNull(state.currentIndex - 1) is Token.Newline) return
            val token = peek()
            if (token is Token.Keyword) {
                when (token.value) {
                    "func", "val", "var", "if", "while", "return", "use" -> return
                }
            }
            consume()
        }
    }

    fun parseProgram(): Pair<Program, List<SyntaxError>> {
        val statements = mutableListOf<Statement>()
        val startLine = peek().line
        val startColumn = peek().column

        skipNewlines()
        while (peek() !is Token.EOF) {
            try {
                statements.add(parseStatement())
            } catch (_: ParserRecoveryException) {
                synchronize()
            }
            skipNewlines()
        }
        return Pair(Program(statements, startLine, startColumn), errors)
    }

    private fun parseStatement(): Statement {
        val statement = when (val token = peek()) {
            is Token.Keyword -> {
                when (token.value) {
                    "intrinsic" -> {
                        consume()
                        val next = peek()
                        if (next is Token.Keyword) {
                            when (next.value) {
                                "func" -> parseFunctionDeclaration(isIntrinsic = true)
                                "val", "var" -> parseVariableDeclaration(isIntrinsic = true)
                                else -> {
                                    reportError("expected 'func', 'val', or 'var' after 'intrinsic' but got '${next.value}'")
                                    throw ParserRecoveryException()
                                }
                            }
                        } else {
                            reportError("expected 'func', 'val', or 'var' after 'intrinsic' but got '${next.value()}'")
                            throw ParserRecoveryException()
                        }
                    }

                    "func" -> parseFunctionDeclaration()
                    "enum" -> parseEnumDeclaration()
                    "val", "var" -> parseVariableDeclaration()
                    "if" -> parseIfStatement()
                    "while" -> parseWhileStatement()
                    "return" -> parseReturnStatement()
                    "use" -> parseImportStatement()
                    else -> parseExpressionStatement()
                }
            }

            is Token.Separator -> {
                when (token.value) {
                    "{" -> parseBlockStatement()
                    "}" -> {
                        reportError("unexpected closing brace '}'")
                        consume()
                        throw ParserRecoveryException()
                    }

                    else -> parseExpressionStatement()
                }
            }

            else -> parseExpressionStatement()
        }
        skipNewlines()
        return statement
    }

    private fun parseImportStatement(): ImportStatement {
        val useKeyword = consume()
        val pathToken = peek()
        if (pathToken !is Token.StringLiteral) {
            reportError("expected string literal for import path")
            throw ParserRecoveryException()
        }
        consume()

        var asName: IdentifierExpression? = null
        if (peek().value() == "as") {
            consume()
            val aliasToken = expectIdentifier()
            asName = IdentifierExpression(aliasToken.value, aliasToken.line, aliasToken.column)
        }
        return ImportStatement(pathToken.value, asName, useKeyword.line, useKeyword.column)
    }

    private fun parseBlockStatement(): BlockStatement {
        skipNewlines()
        val openBrace = expectToken("{")
        val statements = mutableListOf<Statement>()
        skipNewlines()

        while (peek().value() != "}") {
            if (peek() is Token.EOF) {
                reportError("unclosed block statement, expected '}'")
                return BlockStatement(statements, openBrace.line, openBrace.column)
            }
            try {
                statements.add(parseStatement())
            } catch (_: ParserRecoveryException) {
                synchronize()
            }
            skipNewlines()
        }
        expectToken("}")
        return BlockStatement(statements, openBrace.line, openBrace.column)
    }

    private fun parseFunctionDeclaration(isIntrinsic: Boolean = false): FunctionDeclaration {
        val funcKeyword = consume()
        val name = parseIdentifierExpression()
        skipNewlines()
        expectToken("(")
        val parameters = mutableListOf<Parameter>()
        skipNewlines()
        if (peek().value() != ")") {
            parameters.add(parseParameter())
            skipNewlines()
            while (peek().value() == ",") {
                consume()
                skipNewlines()
                parameters.add(parseParameter())
                skipNewlines()
            }
        }
        expectToken(")")

        skipNewlines()
        val returnTypeAnnotation = parseOptionalTypeAnnotation()
        val body = if (isIntrinsic) {
            if (peek().value() == "{") {
                reportError("intrinsic functions cannot have a body")
                throw ParserRecoveryException()
            }
            null
        } else {
            parseBlockStatement()
        }
        return FunctionDeclaration(
            name,
            parameters,
            returnTypeAnnotation,
            body,
            isIntrinsic,
            funcKeyword.line,
            funcKeyword.column
        )
    }

    private fun parseEnumDeclaration(): EnumDeclaration {
        val startToken = expectToken("enum")
        val name = parseIdentifierExpression()
        expectToken("{")
        skipNewlines()
        val variants = mutableListOf<IdentifierExpression>()
        while (peek() !is Token.EOF && (peek() !is Token.Separator || (peek() as Token.Separator).value != "}")) {
            variants.add(parseIdentifierExpression())
            val hadNewlineBefore = peek() is Token.Newline
            skipNewlines()
            if (peek() is Token.Separator && (peek() as Token.Separator).value == ",") {
                val commaToken = consume()
                val hadNewlineAfter = peek() is Token.Newline
                if (hadNewlineBefore || hadNewlineAfter) {
                    errors.add(
                        SyntaxError(
                            filePath,
                            commaToken.line,
                            commaToken.column,
                            "unnecessary comma in enum declaration",
                            DiagnosticSeverity.WARNING
                        )
                    )
                }
                skipNewlines()
            }
        }
        expectToken("}")
        return EnumDeclaration(name, variants, startToken.line, startToken.column)
    }

    private fun parseParameter(): Parameter {
        val name = parseIdentifierExpression()
        val typeAnnotation = parseOptionalTypeAnnotation()
        var defaultValue: Expression? = null
        skipNewlines()
        if (peek().value() == "=") {
            consume()
            skipNewlines()
            defaultValue = parseExpression()
        }
        return Parameter(name, typeAnnotation, defaultValue, name.line, name.column)
    }

    private fun parseVariableDeclaration(isIntrinsic: Boolean = false): VariableDeclaration {
        val keywordToken = consume()
        val isMutable = keywordToken.value() == "var"
        val name = parseIdentifierExpression()
        val typeAnnotation = parseOptionalTypeAnnotation()
        var initializer: Expression? = null
        skipNewlines()
        if (peek().value() == "=") {
            consume()
            skipNewlines()
            initializer = parseExpression()
        }
        return VariableDeclaration(
            name,
            typeAnnotation,
            initializer,
            isMutable,
            isIntrinsic,
            keywordToken.line,
            keywordToken.column
        )
    }

    private fun parseIfStatement(): IfStatement {
        val ifKeyword = consume()
        skipNewlines()
        expectToken("(")
        skipNewlines()
        val condition = parseExpression()
        skipNewlines()
        expectToken(")")
        val thenBranch = parseBlockStatement()
        var elseBranch: BlockStatement? = null
        skipNewlines()
        if (peek().value() == "else") {
            consume()
            elseBranch = parseBlockStatement()
        }
        return IfStatement(condition, thenBranch, elseBranch, ifKeyword.line, ifKeyword.column)
    }

    private fun parseWhileStatement(): WhileStatement {
        val whileKeyword = consume()
        skipNewlines()
        expectToken("(")
        skipNewlines()
        val condition = parseExpression()
        skipNewlines()
        expectToken(")")
        val body = parseBlockStatement()
        return WhileStatement(condition, body, whileKeyword.line, whileKeyword.column)
    }

    private fun parseReturnStatement(): ReturnStatement {
        val returnKeyword = consume()
        val value = if (peek() !is Token.EOF && peek() !is Token.Newline && peek().value() != "}") {
            parseExpression()
        } else null
        return ReturnStatement(value, returnKeyword.line, returnKeyword.column)
    }

    private fun parseExpressionStatement(): ExpressionStatement {
        val expr = parseExpression()
        return ExpressionStatement(expr, expr.line, expr.column)
    }

    internal fun parseExpression(): Expression = parseAssignmentExpression()

    private fun parseAssignmentExpression(): Expression {
        val left = parseBinaryExpression(0)
        val peekVal = peek().value()
        if (peekVal in listOf("=", "+=", "-=", "*=", "/=", "%=")) {
            val operator = consume().value()
            skipNewlines()
            val right = parseAssignmentExpression()
            if (left is IdentifierExpression) {
                val finalValue = if (operator == "=") right else {
                    BinaryExpression(left, operator.dropLast(1), right, left.line, left.column)
                }
                return AssignmentExpression(left, finalValue, left.line, left.column)
            } else {
                reportError("invalid assignment target")
                throw ParserRecoveryException()
            }
        }
        return left
    }

    private fun parseBinaryExpression(minPrecedence: Int = 0): Expression {
        var left = parseUnaryExpression()
        while (true) {
            val token = peek()
            if (token is Token.Keyword && token.value == "is") {
                val precedence = getPrecedence("is")
                if (precedence < minPrecedence) break
                consume()
                val typeNameToken = expectIdentifier()
                left = IsExpression(left, typeNameToken.value, left.line, left.column)
                continue
            }

            if (token !is Token.Operator) break
            val op = token.value
            if (op in listOf("=", "+=", "-=", "*=", "/=", "%=")) break

            val precedence = getPrecedence(op)
            if (precedence == 0) break
            if (precedence < minPrecedence) break

            consume()
            skipNewlines()
            val right = parseBinaryExpression(precedence + 1)
            left = BinaryExpression(left, op, right, left.line, left.column)
        }
        return left
    }

    private fun parseUnaryExpression(): Expression {
        val token = peek()
        if (token is Token.Operator && token.value in listOf("-", "!", "++", "--")) {
            val op = consume() as Token.Operator
            skipNewlines()
            val operand = parseUnaryExpression()
            if (op.value in listOf("++", "--")) {
                return createNumericUpdateExpression(operand, op)
            }
            return UnaryExpression(op.value, operand, op.line, op.column)
        }
        return parsePrimaryExpression()
    }

    private fun getPrecedence(operator: String): Int {
        return when (operator) {
            "||" -> 2
            "&&" -> 3
            "==", "!=", "is" -> 4
            "<", ">", "<=", ">=" -> 5
            "+", "-" -> 6
            "*", "/", "%" -> 7
            else -> 0
        }
    }

    private fun createNumericUpdateExpression(
        target: Expression,
        operatorToken: Token.Operator
    ): AssignmentExpression {
        if (target !is IdentifierExpression) {
            reportError("invalid assignment target", operatorToken.line, operatorToken.column)
            throw ParserRecoveryException()
        }

        val mathOperator = if (operatorToken.value == "++") "+" else "-"
        val updatedValue = BinaryExpression(
            target,
            mathOperator,
            LiteralExpression(1, operatorToken.line, operatorToken.column),
            target.line,
            target.column
        )
        return AssignmentExpression(target, updatedValue, target.line, target.column)
    }

    private fun parsePrimaryExpression(): Expression {
        var expr: Expression = when (val token = consume()) {
            is Token.StringLiteral -> parseStringLiteral(token)
            is Token.NumberLiteral -> {
                val value = token.value.toDoubleOrNull() ?: token.value.toIntOrNull()
                LiteralExpression(value, token.line, token.column)
            }

            is Token.BooleanLiteral -> LiteralExpression(
                token.value.toBooleanStrictOrNull(),
                token.line,
                token.column
            )

            is Token.NullLiteral -> LiteralExpression(null, token.line, token.column)
            is Token.Identifier -> IdentifierExpression(token.value, token.line, token.column)
            is Token.CharLiteral -> {
                val content = token.value
                if (content.length != 1) {
                    reportError(
                        "invalid character literal: '$content'. char must have exactly one character",
                        token.line,
                        token.column
                    )
                    LiteralExpression('\u0000', token.line, token.column)
                } else {
                    LiteralExpression(content[0], token.line, token.column)
                }
            }

            is Token.Keyword -> {
                if (token.value == "panic") {
                    val message =
                        if (peek() !is Token.EOF && peek() !is Token.Newline && peek().value() != "}" && peek().value() != ")" && peek().value() != "," && peek().value() != "or") {
                            parseExpression()
                        } else null
                    PanicExpression(message, isFatal = false, token.line, token.column)
                } else {
                    reportError("unexpected token '${token.value()}'")
                    throw ParserRecoveryException()
                }
            }

            is Token.Separator -> {
                when (token.value) {
                    "(" -> {
                        skipNewlines()
                        val inner = parseExpression()
                        skipNewlines()
                        expectToken(")")
                        inner
                    }
                    "[" -> {
                        val elements = mutableListOf<Expression>()
                        skipNewlines()
                        if (peek().let { it is Token.Separator && it.value == "]" }) {
                            consume()
                        } else {
                            while (true) {
                                elements.add(parseExpression())
                                skipNewlines()
                                if (peek().let { it is Token.Separator && it.value == "," }) {
                                    consume()
                                    skipNewlines()
                                    if (peek().let { it is Token.Separator && it.value == "]" }) {
                                        consume()
                                        break
                                    }
                                } else {
                                    expectToken("]")
                                    break
                                }
                            }
                        }
                        ArrayLiteralExpression(elements, token.line, token.column)
                    }
                    else -> {
                        reportError("unexpected token '${token.value()}'")
                        throw ParserRecoveryException()
                    }
                }
            }

            else -> {
                reportError("unexpected token '${token.value()}'")
                throw ParserRecoveryException()
            }
        }

        while (true) {
            when (val next = peek()) {
                is Token.Separator -> {
                    when (next.value) {
                        "." -> {
                            consume()
                            val member = expectIdentifier()
                            expr = MemberAccessExpression(
                                expr,
                                IdentifierExpression(member.value, member.line, member.column),
                                expr.line,
                                expr.column
                            )
                        }
                        "(" -> {
                            expr = parseCallExpression(expr)
                        }
                        "[" -> {
                            consume()
                            skipNewlines()
                            val index = parseExpression()
                            skipNewlines()
                            expectToken("]")
                            expr = IndexAccessExpression(expr, index, expr.line, expr.column)
                        }
                        else -> break
                    }
                }

                is Token.Operator if next.value in listOf("++", "--") -> {
                    expr = createNumericUpdateExpression(expr, consume() as Token.Operator)
                }

                is Token.Keyword if next.value == "or" -> {
                    val orToken = consume()
                    when (val afterOr = peek()) {
                        is Token.Keyword -> {
                            when (afterOr.value) {
                                "panic" -> {
                                    consume()
                                    val panicMessage = IdentifierExpression(
                                        "__amber_err",
                                        afterOr.line,
                                        afterOr.column,
                                        isSynthetic = true
                                    )
                                    val panicBody = BlockStatement(
                                        listOf(
                                            ExpressionStatement(
                                                PanicExpression(
                                                    panicMessage,
                                                    isFatal = true,
                                                    afterOr.line,
                                                    afterOr.column
                                                ), afterOr.line, afterOr.column
                                            )
                                        ),
                                        afterOr.line, afterOr.column
                                    )
                                    expr = CatchExpression(
                                        expr,
                                        "__amber_err",
                                        "string",
                                        panicBody,
                                        orToken.line,
                                        orToken.column
                                    )
                                }
                                "catch" -> {
                                    consume()
                                    expectToken("(")
                                    val catchVar = expectIdentifier()
                                    val catchVarName = catchVar.value
                                    var catchVarType: String? = null
                                    if (peek().value() == ":") {
                                        consume()
                                        catchVarType = parseType()
                                    }
                                    expectToken(")")
                                    val body = parseBlockStatement()
                                    expr = CatchExpression(
                                        expr,
                                        catchVarName,
                                        catchVarType,
                                        body,
                                        orToken.line,
                                        orToken.column
                                    )
                                }
                                else -> {
                                    reportError("expected 'panic' or 'catch' after 'or'")
                                    throw ParserRecoveryException()
                                }
                            }
                        }
                        else -> {
                            reportError("expected 'panic' or 'catch' after 'or'")
                            throw ParserRecoveryException()
                        }
                    }
                }

                else -> break
            }
        }
        return expr
    }

    private fun parseStringLiteral(token: Token.StringLiteral): Expression {
        val value = token.value
        if (!value.contains('$')) return LiteralExpression(value, token.line, token.column)

        val segments = mutableListOf<Expression>()
        var lastIndex = 0
        var i = 0
        while (i < value.length) {
            if (value[i] == '$') {
                if (i > 0 && value[i - 1] == '\\') {
                    i++
                    continue
                }

                if (i > lastIndex) {
                    val literal = value.substring(lastIndex, i).replace("\\$", "$")
                    segments.add(LiteralExpression(literal, token.line, token.column + lastIndex))
                }

                i++
                if (i < value.length && value[i] == '{') {
                    i++
                    val start = i
                    var braceCount = 1
                    var inString = false
                    while (i < value.length && braceCount > 0) {
                        val c = value[i]
                        if (c == '"' && (i == 0 || value[i - 1] != '\\')) inString = !inString
                        if (!inString) {
                            if (c == '{') braceCount++
                            else if (c == '}') braceCount--
                        }
                        if (braceCount > 0) i++
                    }
                    if (braceCount == 0) {
                        val exprStr = value.substring(start, i)
                        segments.add(
                            parseInterpolatedExpression(
                                exprStr,
                                token.line,
                                token.column + start
                            )
                        )
                        i++
                    } else {
                        reportError(
                            "Unclosed interpolation in string",
                            token.line,
                            token.column + i
                        )
                    }
                } else {
                    val start = i
                    while (i < value.length && (value[i].isLetterOrDigit() || value[i] == '_')) {
                        i++
                    }
                    if (i > start) {
                        val id = value.substring(start, i)
                        segments.add(IdentifierExpression(id, token.line, token.column + start))
                    } else {
                        segments.add(LiteralExpression("$", token.line, token.column + i - 1))
                    }
                }
                lastIndex = i
            } else {
                i++
            }
        }

        if (lastIndex < value.length) {
            val literal = value.substring(lastIndex).replace("\\$", "$")
            segments.add(LiteralExpression(literal, token.line, token.column + lastIndex))
        }

        if (segments.isEmpty()) return LiteralExpression("", token.line, token.column)
        if (segments.size == 1 && segments[0] is LiteralExpression) return segments[0]

        return segments.map {
            if (it is LiteralExpression && it.value is String) it
            else CallExpression(
                IdentifierExpression("to_string", it.line, it.column, isSynthetic = true),
                listOf(it),
                it.line,
                it.column
            )
        }.reduce { acc, expression ->
            BinaryExpression(acc, "+", expression, token.line, token.column)
        }
    }

    private fun parseInterpolatedExpression(exprStr: String, line: Int, column: Int): Expression {
        val lexer = Lexer(exprStr)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens, filePath)
        val expr = try {
            parser.parseExpression()
        } catch (e: Exception) {
            ErrorNode("Failed to parse interpolated expression: ${e.message}", line, column)
        }
        this.errors.addAll(parser.errors)
        return expr
    }

    private fun parseCallExpression(callee: Expression): CallExpression {
        val openParen = expectToken("(")
        val arguments = mutableListOf<Expression>()
        skipNewlines()
        if (peek().value() != ")") {
            arguments.add(parseExpression())
            skipNewlines()
            while (peek().value() == ",") {
                consume()
                skipNewlines()
                arguments.add(parseExpression())
                skipNewlines()
            }
        }
        expectToken(")")
        return CallExpression(callee, arguments, openParen.line, openParen.column)
    }

    private fun parseIdentifierExpression(): IdentifierExpression {
        val token = expectIdentifier()
        return IdentifierExpression(token.value, token.line, token.column)
    }

    private fun parseOptionalTypeAnnotation(): String? {
        skipNewlines()
        if (peek().value() == ":") {
            consume()
            skipNewlines()
            return parseType()
        }
        return null
    }

    private fun parseType(): String {
        skipNewlines()
        var typeStr = expectIdentifier().value

        skipNewlines()
        while (peek().value() == "[") {
            consume()
            skipNewlines()
            expectToken("]", "expected ']' after '[' in array type")
            typeStr += "[]"
            skipNewlines()
        }

        if (peek().value() == "<") {
            consume()
            val elementType = parseType()
            expectToken(">", "expected '>' after generic type")
            typeStr += "<$elementType>"
            skipNewlines()
        }

        if (peek().value() == "!") {
            consume()
            typeStr += "!"
        }

        return typeStr
    }
}
