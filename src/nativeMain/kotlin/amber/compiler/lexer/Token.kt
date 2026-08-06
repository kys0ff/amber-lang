package amber.compiler.lexer

sealed class Token(open val line: Int = -1, open val column: Int = -1) {
    data class EOF(override val line: Int = -1, override val column: Int = -1) : Token(line, column)
    
    data class Identifier(val value: String, override val line: Int = -1, override val column: Int = -1) :
        Token(line, column)

    data class Keyword(val value: String, override val line: Int = -1, override val column: Int = -1) :
        Token(line, column)

    data class StringLiteral(val value: String, override val line: Int = -1, override val column: Int = -1) :
        Token(line, column)

    data class CharLiteral(val value: String, override val line: Int = -1, override val column: Int = -1) :
        Token(line, column)

    data class NumberLiteral(val value: String, override val line: Int = -1, override val column: Int = -1) :
        Token(line, column)

    data class BooleanLiteral(val value: String, override val line: Int = -1, override val column: Int = -1) :
        Token(line, column)

    data class NullLiteral(val value: String, override val line: Int = -1, override val column: Int = -1) :
        Token(line, column)

    data class Operator(val value: String, override val line: Int = -1, override val column: Int = -1) :
        Token(line, column)

    data class Separator(val value: String, override val line: Int = -1, override val column: Int = -1) :
        Token(line, column)

    data class Comment(val value: String, override val line: Int = -1, override val column: Int = -1) :
        Token(line, column)

    data class Whitespace(val value: String, override val line: Int = -1, override val column: Int = -1) :
        Token(line, column)

    data class Newline(val value: String, override val line: Int = -1, override val column: Int = -1) :
        Token(line, column)

    data class Unknown(val value: String, override val line: Int = -1, override val column: Int = -1) :
        Token(line, column)
}
