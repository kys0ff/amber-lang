package off.kys.amber_lang

import off.kys.amber_lang.transpiler.Transpiler
import kotlin.test.Test
import kotlin.test.assertFalse
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
            println("ERRORS in ReproductionTest: " + result.errors.joinToString { "${it.severity}: ${it.message}" })
        }
        val code = result.code!!
        println("Generated code:\n$code")

        // Check for double local
        assertFalse(code.contains("local     local"), "Should not contain double local declaration")
        
        // Check for masking return values (local and assignment on same line)
        // This regex looks for 'local' followed by an assignment that contains a command substitution '$('
        val maskingRegex = Regex("""local\s+\w+=[^ \n]*\$\(""")
        assertFalse(maskingRegex.containsMatchIn(code), "Should not mask return values by assigning local variables with command substitution on the same line")

        // Check for indentation of the second local declaration in the "declare and assign" pattern
        assertTrue(code.contains(Regex(""" {4}local __amber_tmp_""")), "Temporary variables should be declared with correct indentation")
        assertTrue(code.contains(Regex("""\n {4}__amber_tmp_""")), "Temporary variables should be assigned on a new line with correct indentation")
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
        executableDir = "/home/kys0adam/IdeaProjects/amber-lang/build/bin/linuxX64/debugExecutable"
    ).transpile()
}
