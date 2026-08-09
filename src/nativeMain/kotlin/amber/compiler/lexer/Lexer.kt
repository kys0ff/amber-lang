package amber.compiler.lexer

class Lexer(private val source: String) {
    private val state = LexerState()

    private val keywords = setOf(
        "func", "val", "var", "if", "else", "while", "for", "return", "use", "as", "is", "intrinsic", "panic", "or", "catch", "enum", "struct", "break", "continue", "in", "extend"
    )
    private val booleanLiterals = setOf("true", "false")
    private val nullLiteral = "null"
    private val twoCharOperators = setOf("++", "--", "+=", "-=", "*=", "/=", "%=", "==", "!=", "<=", ">=", "&&", "||")
    private val singleCharOperators = setOf("+", "-", "*", "/", "%", "=", "!", "<", ">")
    private val operatorStarters = setOf('+', '-', '*', '/', '%', '=', '!', '<', '>', '&', '|')

    fun tokenize(keepTrivia: Boolean = false): List<Token> {
        val tokens = mutableListOf<Token>()
        var token = nextToken(keepTrivia)
        while (token !is Token.EOF) {
            if (keepTrivia || (token !is Token.Whitespace && token !is Token.Comment)) {
                tokens.add(token)
            }
            token = nextToken(keepTrivia)
        }
        tokens.add(token)
        return tokens
    }

    fun nextToken(keepTrivia: Boolean = false): Token {
        if (!keepTrivia) {
            skipWhitespaceAndComments()
        }

        if (state.position >= source.length) {
            return Token.EOF(state.line, state.column)
        }

        val startLine = state.line
        val startColumn = state.column
        val actualChar = source[state.position]

        if (keepTrivia) {
            when (actualChar) {
                ' ', '\t', '\r' -> {
                    val start = state.position
                    while (state.position < source.length && (source[state.position] == ' ' || source[state.position] == '\t' || source[state.position] == '\r')) {
                        state.position++
                        state.column++
                    }
                    return Token.Whitespace(source.substring(start, state.position), startLine, startColumn)
                }
                '\n' -> {
                    state.position++
                    state.line++
                    state.column = 1
                    return Token.Newline("\n", startLine, startColumn)
                }
                '/' -> {
                    if (state.position + 1 < source.length && source[state.position + 1] == '/') {
                        val start = state.position
                        while (state.position < source.length && source[state.position] != '\n') {
                            state.position++
                            state.column++
                        }
                        return Token.Comment(source.substring(start, state.position), startLine, startColumn)
                    }
                }
            }
        } else if (actualChar == '\n') {
            state.position++
            state.line++
            state.column = 1
            return Token.Newline(value = "\n", line = startLine, column = startColumn)
        }

        if (actualChar in operatorStarters) {
            if (state.position + 1 < source.length) {
                val twoCharOperator = "${actualChar}${source[state.position + 1]}"
                if (twoCharOperator in twoCharOperators) {
                    state.position += 2
                    state.column += 2
                    return Token.Operator(twoCharOperator, startLine, startColumn)
                }
            }

            val singleCharOperator = actualChar.toString()
            if (singleCharOperator in singleCharOperators) {
                state.position++
                state.column++
                return Token.Operator(singleCharOperator, startLine, startColumn)
            }
        }

        when (actualChar) {
            '(', ')', '{', '}', '[', ']', ',', ';', ':', '.' -> {
                state.position++
                state.column++
                return Token.Separator(actualChar.toString(), startLine, startColumn)
            }
            '"' -> return readString(startLine, startColumn)
            '\'' -> return readChar(startLine, startColumn)
        }

        if (actualChar.isDigit()) {
            return readNumber(startLine, startColumn)
        }

        if (actualChar.isLetter() || actualChar == '_') {
            return readIdentifierOrKeyword(startLine, startColumn)
        }

        state.position++
        state.column++
        return Token.Unknown(actualChar.toString(), startLine, startColumn)
    }

    private fun skipWhitespaceAndComments() {
        while (state.position < source.length) {
            when (source[state.position]) {
                ' ', '\t', '\r' -> {
                    state.position++
                    state.column++
                }
                '/' -> {
                    if (state.position + 1 < source.length && source[state.position + 1] == '/') {
                        while (state.position < source.length && source[state.position] != '\n') {
                            state.position++
                            state.column++
                        }
                    } else return
                }
                else -> return
            }
        }
    }

    private fun readNumber(startLine: Int, startColumn: Int): Token {
        val start = state.position
        while (state.position < source.length && source[state.position].isDigit()) {
            state.position++
            state.column++
        }
        if (state.position < source.length && source[state.position] == '.') {
            if (state.position + 1 < source.length && source[state.position + 1].isDigit()) {
                state.position++
                state.column++
                while (state.position < source.length && source[state.position].isDigit()) {
                    state.position++
                    state.column++
                }
            }
        }
        return Token.NumberLiteral(source.substring(start, state.position), startLine, startColumn)
    }

    private fun readIdentifierOrKeyword(startLine: Int, startColumn: Int): Token {
        val start = state.position
        while (state.position < source.length && (source[state.position].isLetterOrDigit() || source[state.position] == '_')) {
            state.position++
            state.column++
        }
        val value = source.substring(start, state.position)
        return when {
            keywords.contains(value) -> Token.Keyword(value, startLine, startColumn)
            booleanLiterals.contains(value) -> Token.BooleanLiteral(value, startLine, startColumn)
            value == nullLiteral -> Token.NullLiteral(value, startLine, startColumn)
            else -> Token.Identifier(value, startLine, startColumn)
        }
    }

    private fun readString(startLine: Int, startColumn: Int): Token {
        val start = state.position
        state.position++
        state.column++
        while (state.position < source.length && source[state.position] != '"') {
            if (source[state.position] == '\\' && state.position + 1 < source.length) {
                state.position++
                state.column++
            }
            state.position++
            state.column++
        }
        if (state.position < source.length && source[state.position] == '"') {
            state.position++
            state.column++
        }
        return Token.StringLiteral(source.substring(start + 1, state.position - 1), startLine, startColumn)
    }

    private fun readChar(startLine: Int, startColumn: Int): Token {
        val start = state.position
        state.position++
        state.column++

        val contentStart = state.position

        while (state.position < source.length && source[state.position] != '\'' && source[state.position] != '\n') {
            if (source[state.position] == '\\' && state.position + 1 < source.length) {
                state.position += 2
                state.column += 2
            } else {
                state.position++
                state.column++
            }
        }

        val content = source.substring(contentStart, state.position)

        if (state.position < source.length && source[state.position] == '\'') {
            state.position++
            state.column++
            return Token.CharLiteral(content, startLine, startColumn)
        }

        return Token.Unknown(source.substring(start, state.position), startLine, startColumn)
    }
}
