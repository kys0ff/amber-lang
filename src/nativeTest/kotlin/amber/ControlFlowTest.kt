package amber

import amber.compiler.Transpiler
import amber.compiler.compilerConfig
import amber.compiler.diagnostic.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertTrue

class ControlFlowTest {

    private fun transpile(source: String) = Transpiler(
        compilerConfig {
            projectRoot = "/tmp"
            entryFile = "/tmp/test.amb"
            executableDir = "/home/kys0adam/IdeaProjects/amber-lang"
        }
    ).transpile(source, "/tmp/test.amb")

    private fun assertNoErrors(source: String) {
        val result = transpile(source)
        if (result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            println("Source:\n$source")
            println("Errors: ${result.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }}")
        }
        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, 
            "Expected no errors, but got: ${result.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }}")
    }

    private fun assertHasError(source: String, messagePart: String) {
        val result = transpile(source)
        if (!result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR && it.message.contains(messagePart.lowercase()) }) {
            println("Source:\n$source")
            println("Expected error containing '$messagePart', but got: ${result.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }}")
        }
        assertTrue(result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR && it.message.contains(messagePart.lowercase()) }, 
            "Expected error containing '$messagePart', but got: ${result.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }}")
    }

    @Test
    fun testBreakInsideWhile() {
        assertNoErrors("""
            func main() {
                while (true) {
                    break
                }
            }
        """.trimIndent())
    }

    @Test
    fun testBreakOutsideLoop() {
        assertHasError("""
            func main() {
                break
            }
        """.trimIndent(), "only allowed inside a loop")
    }

    @Test
    fun testContinueInsideWhile() {
        assertNoErrors("""
            func main() {
                while (true) {
                    continue
                }
            }
        """.trimIndent())
    }

    @Test
    fun testContinueOutsideLoop() {
        assertHasError("""
            func main() {
                continue
            }
        """.trimIndent(), "only allowed inside a loop")
    }

    @Test
    fun testReturnInsideLoop() {
        assertNoErrors("""
            func test(): num {
                while (true) {
                    return 1
                }
            }
        """.trimIndent())
    }

    @Test
    fun testForLoopInferred() {
        assertNoErrors("""
            func main(items: list<string>) {
                for (item in items) {
                    val s: string = item
                }
            }
        """.trimIndent())
    }

    @Test
    fun testForLoopExplicitType() {
        assertNoErrors("""
            func main(items: list<string>) {
                for (item: string in items) {
                    val s: string = item
                }
            }
        """.trimIndent())
    }

    @Test
    fun testForLoopTypeMismatch() {
        assertHasError("""
            func main(items: list<string>) {
                for (item: num in items) {
                }
            }
        """.trimIndent(), "type mismatch")
    }

    @Test
    fun testForLoopWithIndex() {
        assertNoErrors("""
            func main(items: list<string>) {
                for (i, item in items) {
                    val index: num = i
                    val s: string = item
                }
            }
        """.trimIndent())
    }

    @Test
    fun testForLoopExplicitIndexType() {
        assertNoErrors("""
            func main(items: list<string>) {
                for (i: num, item in items) {
                }
            }
        """.trimIndent())
    }

    @Test
    fun testForLoopInvalidIndexType() {
        assertHasError("""
            func main(items: list<string>) {
                for (i: string, item in items) {
                }
            }
        """.trimIndent(), "loop index must be of type 'num'")
    }

    @Test
    fun testForLoopString() {
        assertNoErrors("""
            func main(s: string) {
                for (c in s) {
                    val char: char = c
                }
            }
        """.trimIndent())
    }

    @Test
    fun testForLoopInvalidIterable() {
        assertHasError("""
            func main(n: num) {
                for (item in n) {
                }
            }
        """.trimIndent(), "expects list or string")
    }

    @Test
    fun testForLoopScope() {
        assertHasError("""
            func main(items: list<string>) {
                for (item in items) {
                }
                val s = item
            }
        """.trimIndent(), "undefined identifier 'item'")
    }

    @Test
    fun testForLoopShadowing() {
        assertNoErrors("""
            func main(items: list<string>) {
                val item = 1
                for (item in items) {
                    val s: string = item
                }
                val n: num = item
            }
        """.trimIndent())
    }

    @Test
    fun testListGetOrErr() {
        assertNoErrors("""
            use "core:list"
            func main(items: list<num>) {
                val item = list.get_or_err(items, 0) or panic
            }
        """.trimIndent())
    }

    @Test
    fun testStringGetOrErr() {
        assertNoErrors("""
            use "core:str"
            func main(s: string) {
                val c = str.get_or_err(s, 0) or panic
                val char: char = c
            }
        """.trimIndent())
    }

    @Test
    fun testListIndexOutOfBoundsPanics() {
        // We can't easily test for panic in unit tests without running the binary
        // but we can at least verify it compiles.
        assertNoErrors("""
            func main() {
                val l = [1, 2, 3]
                val _ = l[10]
            }
        """.trimIndent())
    }
}
