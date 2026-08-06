package amber

import amber.compiler.CompilerConfig
import amber.compiler.Transpiler
import kotlin.test.Test
import kotlin.test.assertTrue

class ReproductionTest {

    @Test
    fun reproducesDoubleLocalAndMasking() {
        val source = $$"""
            use "core:io"
            use "core:str"
            func value(param1: any = 12): string {
                if(param1 is number) {
                    io.println("$param1 is a number")
                }
                io.println(param1)
                return "Hi" + " and " + str.to_string(true)
            }
            io.println(value())
        """.trimIndent()

        val result = transpile(source)
        if (result.code == null) {
            val errorMsgs = result.diagnostics.joinToString { "${it.severity}: ${it.message}" }
            assertTrue(false, "ReproductionTest failed to transpile: $errorMsgs")
        }
        val code = result.code!!
        println("Generated code:\n$code")

        assertTrue(code.contains("int main("), "Should contain main function")
        assertTrue(code.contains("__am_value("), "Should contain __am_value function")
    }

    @Test
    fun testTrailingValErrorMessage() {
        val source = "val"
        val result = transpile(source)
        
        assertTrue(result.diagnostics.isNotEmpty(), "Should have errors")
        val error = result.diagnostics[0]
        println("Error message: $error")
        
        assertTrue(error.line >= 0 && error.column >= 0, "Error should not have negative line/column")
        assertTrue(error.message.contains("expected identifier but got end of file"), "Error message should be improved")
        assertTrue(error.line == 1 && error.column == 4, "Error should be at line 1, column 4 (after 'val')")
    }

    private fun transpile(source: String) = Transpiler(
        CompilerConfig(
            projectRoot = "/tmp",
            entryFile = "/tmp/test.amb",
            executableDir = "/home/kys0adam/IdeaProjects/amber-lang"
        )
    ).transpile(source, "/tmp/test.amb")
}
