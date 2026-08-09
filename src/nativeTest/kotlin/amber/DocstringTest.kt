package amber

import amber.compiler.ast.FunctionDeclaration
import amber.compiler.ast.StructDeclaration
import amber.compiler.ast.VariableDeclaration
import amber.compiler.lexer.Lexer
import amber.compiler.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals

class DocstringTest {

    @Test
    fun testFunctionDocstring() {
        val source = """
            ## This is a function docstring
            # continued
            func foo() {}
        """.trimIndent()
        
        val lexer = Lexer(source)
        val tokens = lexer.tokenize(keepTrivia = true)
        val parser = Parser(tokens, "test.amb")
        val (program, errors) = parser.parseProgram()
        
        assertEquals(0, errors.size, "Errors: $errors")
        val func = program.statements[0] as FunctionDeclaration
        assertEquals("This is a function docstring\ncontinued", func.docstring)
    }

    @Test
    fun testStructAndFieldDocstrings() {
        val source = """
            ## Struct doc
            struct S {
                ## Field doc
                x: num
            }
        """.trimIndent()
        
        val lexer = Lexer(source)
        val tokens = lexer.tokenize(keepTrivia = true)
        val parser = Parser(tokens, "test.amb")
        val (program, _) = parser.parseProgram()
        
        val struct = program.statements[0] as StructDeclaration
        assertEquals("Struct doc", struct.docstring)
        assertEquals("Field doc", struct.fields[0].docstring)
    }

    @Test
    fun testVariableDocstring() {
        val source = """
            ## Var doc
            val x = 1
        """.trimIndent()
        
        val lexer = Lexer(source)
        val tokens = lexer.tokenize(keepTrivia = true)
        val parser = Parser(tokens, "test.amb")
        val (program, _) = parser.parseProgram()
        
        val variable = program.statements[0] as VariableDeclaration
        assertEquals("Var doc", variable.docstring)
    }

    @Test
    fun testDocstringWithNewlines() {
        val source = """
            ## Line 1
            
            ## Line 2
            func foo() {}
        """.trimIndent()
        
        val lexer = Lexer(source)
        val tokens = lexer.tokenize(keepTrivia = true)
        val parser = Parser(tokens, "test.amb")
        val (program, _) = parser.parseProgram()
        
        val func = program.statements[0] as FunctionDeclaration
        assertEquals("Line 1\nLine 2", func.docstring)
    }
}
