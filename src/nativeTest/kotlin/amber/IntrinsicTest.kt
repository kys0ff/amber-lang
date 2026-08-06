package amber

import amber.compiler.CompilerConfig
import amber.compiler.Transpiler
import amber.compiler.diagnostic.DiagnosticSeverity
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
        
        assertTrue(result.diagnostics.any { it.message.contains("intrinsic functions are only allowed in the core standard library") }, 
            "Should fail when intrinsic is used outside stdlib")
    }

    @Test
    fun testIntrinsicInStdLibPasses() {
        val source = """
            intrinsic func my_intrinsic()
        """.trimIndent()

        val result = transpile(source, "/usr/local/lib/std/core.amb")
        
        assertFalse(result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }, 
            "Should pass when intrinsic is used in stdlib. Errors: ${result.diagnostics}")
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
        
        println("TEST ERRORS: " + result.diagnostics.joinToString { it.message })
        
        assertTrue(result.diagnostics.isNotEmpty(), "Should have errors when intrinsic has a body")
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
        
        assertFalse(code.contains("void __am_my_intrinsic() {"), "Intrinsic function declaration should not be in generated code")
        assertTrue(code.contains("__am_my_intrinsic()"), "Call to intrinsic should be generated")
    }

    @Test
    fun testIntrinsicVariableOutsideStdLibFails() {
        val source = """
            intrinsic val my_const: number = 10
        """.trimIndent()

        val result = transpile(source, "/tmp/test.amb")
        
        assertTrue(result.diagnostics.any { it.message.contains("intrinsic variables are only allowed in the core standard library") }, 
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

        val result = transpile(source, "/tmp/test.amb")
        
        if (result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            println("ERRORS: " + result.diagnostics.joinToString { it.message })
        }
        
        assertFalse(result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }, 
            "Should resolve println and to_string from library. Errors: ${result.diagnostics}")
        
        val code = result.code ?: ""
        assertTrue(code.contains("__amber_rt_println"), "Should use __amber_rt_println for println")
    }

    private fun transpile(source: String, filePath: String) = Transpiler(
        CompilerConfig(
            projectRoot = "/tmp",
            entryFile = filePath,
            executableDir = "/home/kys0adam/IdeaProjects/amber-lang"
        )
    ).transpile(source, filePath)
}
