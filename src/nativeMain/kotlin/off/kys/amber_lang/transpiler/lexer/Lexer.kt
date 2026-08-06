package off.kys.amber_lang.transpiler.lexer

class Lexer(private val source: String) {
    private val state = LexerState()

    private val keywords = setOf(
        "func", "val", "var", "if", "else", "while", "for", "return", "use", "as", "is", "intrinsic", "panic", "or", "catch", "enum"
    )
    private val booleanLiterals = setOf("true", "false")
    private val nullLiteral = "null"
    private val twoCharOperators = setOf("++", "--", "+=", "-=", "*=", "/=", "%=", "==", "!=", "<=", ">=", "&&", "||")
    private val singleCharOperators = setOf("+", "-", "*", "/", "%", "=", "!", "<", ">")
    private val operatorStarters = setOf('+', '-', '*', '/', '%', '=', '!', '<', '>', '&', '|')

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        var token = nextToken()
        while (token !is Token.EOF) {
            if (token !is Token.Whitespace && token !is Token.Comment) {
                tokens.add(token)
            }
            token = nextToken()
        }
        tokens.add(token) // Keep EOF in the list for the parser to use its location
        return tokens
    }

    fun nextToken(): Token {
        skipWhitespaceAndComments()

        if (state.position >= source.length) {
            return Token.EOF(state.line, state.column)
        }

        val startLine = state.line
        val startColumn = state.column
        val actualChar = source[state.position]

        // Handle newlines, as they are significant for statement termination
        if (actualChar == '\n') {
            state.position++
            state.line++
            state.column = 1
            return Token.Newline(value = "\n", line = startLine, column = startColumn)
        }

        // Handle operators
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
            '(', ')', '{', '}', '[', ']', ',', ';', ':' -> {
                state.position++
                state.column++
                return Token.Separator(actualChar.toString(), startLine, startColumn)
            }
            '"' -> return readString(startLine, startColumn)
            '\'' -> return readChar(startLine, startColumn)
        }

        // Handle numbers
        if (actualChar.isDigit()) {
            return readNumber(startLine, startColumn)
        }

        // Handle identifiers and keywords
        if (actualChar.isLetter() || actualChar == '_') {
            return readIdentifierOrKeyword(startLine, startColumn)
        }

        // Unknown character
        state.position++
        state.column++
        return Token.Unknown(actualChar.toString(), startLine, startColumn)
    }

    private fun skipWhitespaceAndComments() {
        while (state.position < source.length) {
            when (source[state.position]) {
                ' ', '\t', '\r' -> { // Only skip these whitespaces
                    state.position++
                    state.column++
                }
                '#' -> { // Single-line comment (hash)
                    while (state.position < source.length && source[state.position] != '\n') {
                        state.position++
                        state.column++
                    }
                }
                '/' -> { // Potential single-line comment (double slash)
                    if (state.position + 1 < source.length && source[state.position + 1] == '/') {
                        while (state.position < source.length && source[state.position] != '\n') {
                            state.position++
                            state.column++
                        }
                    } else return // Not a comment, but an operator
                }
                else -> return // Stop skipping if it's not a skippable whitespace or comment start
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
            state.position++ // Consume the dot
            state.column++
            while (state.position < source.length && source[state.position].isDigit()) {
                state.position++
                state.column++
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
        state.position++ // Consume the opening quote
        state.column++
        while (state.position < source.length && source[state.position] != '"') {
            // Handle escaped quotes if necessary
            if (source[state.position] == '\\' && state.position + 1 < source.length) {
                state.position++ // Consume the escape character
                state.column++
            }
            state.position++
            state.column++
        }
        if (state.position < source.length && source[state.position] == '"') {
            state.position++ // Consume the closing quote
            state.column++
        }
        // The value should not include the quotes
        return Token.StringLiteral(source.substring(start + 1, state.position - 1), startLine, startColumn)
    }

    private fun readChar(startLine: Int, startColumn: Int): Token {
        val start = state.position
        state.position++ // Consume opening '
        state.column++

        val contentStart = state.position

        // Consume until we find the closing quote or end of line/file
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
            state.position++ // Consume closing '
            state.column++

            // If content length is not 1 (and it's not a valid escape sequence),
            // we can still return a CharLiteral but the TypeChecker will flag it,
            // OR we return Unknown to trigger a syntax error.
            return Token.CharLiteral(content, startLine, startColumn)
        }

        // Unclosed character literal
        return Token.Unknown(source.substring(start, state.position), startLine, startColumn)
    }
}
