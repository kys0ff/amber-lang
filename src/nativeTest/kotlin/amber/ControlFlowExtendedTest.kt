package amber

import amber.compiler.Transpiler
import amber.compiler.compilerConfig
import amber.compiler.diagnostic.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertTrue

class ControlFlowExtendedTest {

    private fun transpile(source: String) = Transpiler(
        compilerConfig {
            projectRoot = "/tmp"
            entryFile = "/tmp/test.amb"
            executableDir = "/home/kys0adam/IdeaProjects/amber-lang"
        }
    ).transpile(source, "/tmp/test.amb")

    private fun assertNoErrors(source: String) {
        val result = transpile(source)
        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, 
            "Expected no errors, but got: ${result.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }}")
    }

    @Test
    fun testNestedForAndWhileWithBreakContinue() {
        val source = """
            use "core:io"
            func main() {
                var i = 0
                while (i < 5) {
                    for (j in [1, 2, 3]) {
                        if (j == 2) { continue }
                        if (i == 3) { break }
                        io.println("i: " + i + ", j: " + j)
                    }
                    i = i + 1
                }
            }
        """.trimIndent()
        assertNoErrors(source)
    }

    @Test
    fun testCatchWithBreakInsideLoop() {
        val source = """
            use "core:list"
            func main() {
                val l = [1, 2, 3]
                var i = 0
                while (true) {
                    val item: num = list.get_or_err(l, i) or catch(_) { break }
                    i = i + 1
                }
            }
        """.trimIndent()
        assertNoErrors(source)
    }

    @Test
    fun testCatchWithContinueInsideLoop() {
        val source = """
            use "core:list"
            func main() {
                val l = [1, 2, 3]
                var i = 0
                while (i < 10) {
                    val item: num = list.get_or_err(l, i) or catch(_) { 
                        i = i + 1
                        continue 
                    }
                    i = i + 1
                }
            }
        """.trimIndent()
        assertNoErrors(source)
    }

    @Test
    fun testNestedLoopsWithReturn() {
        val source = """
            func find(matrix: list<list<num>>, target: num): bool {
                for (row in matrix) {
                    for (v in row) {
                        if (v == target) { return true }
                    }
                }
                return false
            }
        """.trimIndent()
        assertNoErrors(source)
    }

    @Test
    fun testForLoopWithIndexAndBreak() {
        val source = """
            use "core:io"
            func main() {
                val items = ["a", "b", "c"]
                for (i, item in items) {
                    if (i == 1) { break }
                    io.println(item)
                }
            }
        """.trimIndent()
        assertNoErrors(source)
    }

    @Test
    fun testStringIndexingInference() {
        val source = """
            use "core:io"
            val name = "Amber"
            val fl = name[0]
        """.trimIndent()
        assertNoErrors(source)
    }

    @Test
    fun testStringIndexingWithReadln() {
        val source = """
            use "core:io"
            val name = io.readln()
            val fl = name[0]
        """.trimIndent()
        assertNoErrors(source)
    }

    @Test
    fun testLoopWithContinueAndBreak() {
        val source = """
            use "core:io"
            func main() {
                var i = 0
                while (i < 10) {
                    i = i + 1
                    if (i < 5) { continue }
                    if (i > 8) { break }
                    io.println("i: " + i)
                }
            }
        """.trimIndent()
        assertNoErrors(source)
    }

    @Test
    fun testForLoopWithIndexAndReturn() {
        val source = """
            func find_first_even(nums: list<num>): num {
                for (i, n in nums) {
                    if (n % 2 == 0) { return i }
                }
                return -1
            }
        """.trimIndent()
        assertNoErrors(source)
    }

    @Test
    fun testOrPanicWithCustomMessage() {
        val source = """
            use "core:list"
            func main() {
                val l = [1]
                val _ = list.get_or_err(l, 10) or panic "Custom Error"
            }
        """.trimIndent()
        assertNoErrors(source)
    }

    @Test
    fun testOrPanicDiverging() {
        val source = """
            use "core:list"
            func test(): num {
                val l = [1]
                return list.get_or_err(l, 0) or panic
            }
        """.trimIndent()
        assertNoErrors(source)
    }
}
