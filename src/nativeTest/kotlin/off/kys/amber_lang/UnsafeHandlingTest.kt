package off.kys.amber_lang

import off.kys.amber_lang.transpiler.Transpiler
import off.kys.amber_lang.transpiler.Severity
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class UnsafeHandlingTest {

    private fun transpile(source: String) = Transpiler(
        sourceCode = source,
        currentFilePath = "/tmp/test.amb",
        projectRoot = "/tmp",
        executableDir = "."
    ).transpile()

    @Test
    fun testUnhandledUnsafeCallFails() {
        val source = """
            func f(): string! {
                panic "err"
            }
            val x = f()
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.errors.any { it.message.contains("unhandled unsafe call") }, "Should report unhandled unsafe call")
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
        assertFalse(result.errors.any { it.severity == Severity.ERROR }, "Should not have errors when unsafe call is handled with 'or catch'")
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
        assertFalse(result.errors.any { it.severity == Severity.ERROR }, "Should not have errors when unsafe call is handled with 'or panic'")
    }
}
