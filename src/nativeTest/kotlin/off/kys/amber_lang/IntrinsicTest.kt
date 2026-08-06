package off.kys.amber_lang

import off.kys.amber_lang.transpiler.Severity
import off.kys.amber_lang.transpiler.Transpiler
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntrinsicTest {

    @Test
    fun testIntrinsicOutsideStdLibFails() {
        val source = """
            intrinsic func my_intrinsic()
        """.trimIndent()

        val result = transpile(source, "/tmp/test.amb")
        
        assertTrue(result.errors.any { it.message.contains("intrinsic functions are only allowed in the core standard library") }, 
            "Should fail when intrinsic is used outside stdlib")
    }

    @Test
    fun testIntrinsicInStdLibPasses() {
        val source = """
            intrinsic func my_intrinsic()
        """.trimIndent()

        // Mocking a path that TypeChecker considers stdlib: filePath.contains("/lib/std/")
        val result = transpile(source, "/usr/local/lib/std/core.amb")
        
        assertFalse(result.errors.any { it.severity == Severity.ERROR }, 
            "Should pass when intrinsic is used in stdlib. Errors: ${result.errors}")
    }

    @Test
    fun testIntrinsicWithBodyFails() {
        val source = """
            use "core:io"
            intrinsic func my_intrinsic() {
                io.println("hi")
            }
        """.trimIndent()

        val result = transpile(source, "/usr/local/lib/std/core.amb")
        
        // Let's print the errors to see what's happening
        println("TEST ERRORS: " + result.errors.joinToString { it.message })
        
        assertTrue(result.errors.isNotEmpty(), "Should have errors when intrinsic has a body")
    }

    @Test
    fun testIntrinsicSkipsCodeGen() {
        val source = """
            intrinsic func my_intrinsic()
            
            func main() {
                my_intrinsic()
            }
        """.trimIndent()

        val result = transpile(source, "/usr/local/lib/std/core.amb")
        val code = result.code ?: ""
        println("CODE FOR INTRINSIC TEST:\n$code")
        
        assertFalse(code.contains("void a_my_intrinsic() {"), "Intrinsic function declaration should not be in generated code")
        assertTrue(code.contains("a_my_intrinsic()"), "Call to intrinsic should be generated")
    }

    @Test
    fun testIntrinsicVariableOutsideStdLibFails() {
        val source = """
            intrinsic val my_const: number = 10
        """.trimIndent()

        val result = transpile(source, "/tmp/test.amb")
        
        assertTrue(result.errors.any { it.message.contains("intrinsic variables are only allowed in the core standard library") }, 
            "Should fail when intrinsic variable is used outside stdlib")
    }

    @Test
    fun testGlobalBuiltinsResolvedFromLibrary() {
        val source = """
            use "core:io"
            use "core:str"
            io.println("hello")
            val s = str.to_string(123)
        """.trimIndent()

        // We need to provide the correct executableDir so it can find lib/std
        val result = transpile(source, "/tmp/test.amb")
        
        if (result.errors.any { it.severity == Severity.ERROR }) {
            println("ERRORS: " + result.errors.joinToString { it.message })
        }
        
        assertFalse(result.errors.any { it.severity == Severity.ERROR }, 
            "Should resolve println and to_string from library. Errors: ${result.errors}")
        
        val code = result.code ?: ""
        assertTrue(code.contains("amber_rt_echo"), "Should use amber_rt_echo for println")
    }

    private fun transpile(source: String, filePath: String) = Transpiler(
        sourceCode = source,
        currentFilePath = filePath,
        projectRoot = "/tmp",
        executableDir = "/home/kys0adam/IdeaProjects/amber-lang"
    ).transpile()
}
