package off.kys.amber_lang

import off.kys.amber_lang.transpiler.Transpiler
import kotlin.test.Test
import kotlin.test.assertTrue

class ReproductionTest {

    @Test
    fun reproducesDoubleLocalAndMasking() {
        val source = """
            use "core:io"
            use "core:str"
            func value(param1: any = 12): string {
                if(param1 is number) {
                    io.println("${'$'}param1 is a number")
                }
                io.println(param1)
                return "Hi" + " and " + str.to_string(true)
            }
            io.println(value())
        """.trimIndent()

        val result = transpile(source)
        if (result.code == null) {
            val errorMsgs = result.errors.joinToString { "${it.severity}: ${it.message}" }
            assertTrue(false, "ReproductionTest failed to transpile: $errorMsgs")
        }
        val code = result.code!!
        println("Generated code:\n$code")

        // Check for basic C structure
        assertTrue(code.contains("int main("), "Should contain main function")
        assertTrue(code.contains("a_value("), "Should contain a_value function")
    }

    @Test
    fun testTrailingValErrorMessage() {
        val source = "val"
        val result = transpile(source)
        
        assertTrue(result.errors.isNotEmpty(), "Should have errors")
        val error = result.errors[0]
        println("Error message: $error")
        
        // Check fields directly instead of string representation
        assertTrue(error.line >= 0 && error.column >= 0, "Error should not have negative line/column")
        assertTrue(error.message.contains("expected identifier but got end of file"), "Error message should be improved")
        assertTrue(error.line == 1 && error.column == 4, "Error should be at line 1, column 4 (after 'val')")
    }

    private fun transpile(source: String) = Transpiler(
        sourceCode = source,
        currentFilePath = "/tmp/test.amb",
        projectRoot = "/tmp",
        executableDir = "/home/kys0adam/IdeaProjects/amber-lang"
    ).transpile()
}
