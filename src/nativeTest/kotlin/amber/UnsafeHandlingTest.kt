package amber

import amber.compiler.compilerConfig
import amber.compiler.Transpiler
import amber.compiler.diagnostic.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnsafeHandlingTest {

    private fun transpile(source: String) = Transpiler(
        compilerConfig {
            projectRoot = "/tmp"
            entryFile = "/tmp/test.amb"
            executableDir = "/home/kys0adam/IdeaProjects/amber-lang"
        }
    ).transpile(source, "/tmp/test.amb")

    @Test
    fun testUnhandledUnsafeCallFails() {
        val source = """
            func f(): string! {
                panic "err"
            }
            val x = f()
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.any { it.message.contains("unhandled unsafe call") }, "Should report unhandled unsafe call")
    }

    @Test
    fun testHandledUnsafeCallWithCatchPasses() {
        val source = """
            func f(): string! {
                return "ok"
            }
            val x = f() or catch(_) { "default" }
        """.trimIndent()
        val result = transpile(source)
        assertFalse(result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }, "Should not have errors when unsafe call is handled with 'or catch'")
    }

    @Test
    fun testHandledUnsafeCallWithPanicPasses() {
        val source = """
            func f(): string! {
                return "ok"
            }
            val x = f() or panic
        """.trimIndent()
        val result = transpile(source)
        assertFalse(result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }, "Should not have errors when unsafe call is handled with 'or panic'")
    }
}
