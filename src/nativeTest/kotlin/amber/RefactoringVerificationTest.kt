package amber

import amber.compiler.Transpiler
import amber.compiler.compilerConfig
import amber.compiler.diagnostic.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertTrue

class RefactoringVerificationTest {

    private fun transpile(source: String): amber.compiler.CompilationResult {
        val config = compilerConfig {
            entryFile = "/tmp/test.amb"
        }
        val transpiler = Transpiler(config)
        return transpiler.transpile(source, "/tmp/test.amb")
    }

    @Test
    fun testStructExtensionWithPrintCollision() {
        val source = """
            use "core:io"
            
            struct person {
                name: string
                age: num
            }

            extend person {
                func print() {
                    io.println("Person: " + self.name)
                }
            }

            func main() {
                val jeff = person("Jeff", 18)
                jeff.print()
            }
        """.trimIndent()
        val result = transpile(source)
        if (result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            println("Diagnostics: ${result.diagnostics.joinToString("\n")}")
        }
        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "Errors: ${result.diagnostics}")
    }
}
