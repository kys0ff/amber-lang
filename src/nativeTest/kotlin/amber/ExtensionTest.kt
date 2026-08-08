package amber

import amber.compiler.Transpiler
import amber.compiler.compilerConfig
import amber.compiler.diagnostic.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertTrue

class ExtensionTest {

    private fun transpile(source: String): amber.compiler.CompilationResult {
        val config = compilerConfig {
            entryFile = "/tmp/test.amb"
        }
        val transpiler = Transpiler(config)
        return transpiler.transpile(source, "/tmp/test.amb")
    }

    @Test
    fun testBasicExtension() {
        val source = """
            extend string {
                func hello(): string {
                    return "hello " + self
                }
            }
            
            func main() {
                val s = "amber"
                val res = s.hello()
            }
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "Errors: ${result.diagnostics}")
    }

    @Test
    fun testMutableExtension() {
        val source = """
            extend num[] {
                func add_one() {
                    self.push(1)
                }
            }
            
            func main() {
                var list = [1, 2]
                list.add_one()
            }
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "Errors: ${result.diagnostics}")
    }

    @Test
    fun testMutableExtensionFailure() {
        val source = """
            func main() {
                val immutable: num[] = [3, 4]
                immutable.push(5)
            }
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.any { it.message.contains("mutable") }, "Should fail with mutability error. Errors: ${result.diagnostics}")
    }

    @Test
    fun testAutomaticPrimitiveLoading() {
        val source = """
            func main() {
                val s = "amber"
                val n = s.len()
                
                val items: num[] = [1, 2, 3]
                val l = items.len()
            }
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "Errors: ${result.diagnostics}")
    }

    @Test
    fun testExplicitExtensionImport() {
        val source = """
            use "core:str.len"
            func main() {
                val s = "amber"
                val n = s.len()
            }
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "Errors: ${result.diagnostics}")
    }

    @Test
    fun testWildcardExtensionImport() {
        val source = """
            use "core:str.*"
            func main() {
                val s = "amber"
                val n = s.len()
                val b = s.starts_with("a")
            }
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "Errors: ${result.diagnostics}")
    }

    @Test
    fun testDeepMutationCheck() {
        val source = """
            extend num[] {
                func my_push(v: num) {
                    self.push(v)
                }
            }
            
            func main() {
                val list: num[] = [1]
                list.my_push(2)
            }
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.any { it.message.contains("mutable") }, "Should fail because my_push mutates self. Errors: ${result.diagnostics}")
    }

    @Test
    fun testCharAndMathExtensions() {
        val source = """
            func main() {
                val c = '7'
                val b = c.is_digit()
                
                val n = 10
                val clamped = n.clamp(0, 5)
            }
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "Errors: ${result.diagnostics}")
    }
}
