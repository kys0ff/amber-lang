package amber.compiler.lexer

data class LexerState(
    var position: Int = 0,
    var line: Int = 1,
    var column: Int = 1
)
