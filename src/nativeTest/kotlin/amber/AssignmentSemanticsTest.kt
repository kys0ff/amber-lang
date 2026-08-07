package amber

import amber.compiler.Transpiler
import amber.compiler.compilerConfig
import amber.compiler.diagnostic.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertTrue

class AssignmentSemanticsTest {

    private fun transpile(source: String) = Transpiler(
        compilerConfig {
            projectRoot = "/tmp"
            entryFile = "/tmp/test.amb"
            executableDir = "/home/kys0adam/IdeaProjects/amber-lang"
        }
    ).transpile(source, "/tmp/test.amb")

    @Test
    fun assignmentStatementShouldCompile() {
        val source = """
            struct user {
                name: string = "John"
                age: num = 18
            }
            
            func test(u: user) {
                u.name = "Bob"
            }
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "Expected no errors, but got: ${result.diagnostics}")
    }

    @Test
    fun assignmentReturnedAsValueShouldFail() {
        val source = """
            struct user {
                name: string = "John"
                age: num = 18
            }
            
            func test(u: user): string {
                return u.name = "Bob"
            }
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.any { it.message.contains("return type mismatch") && it.message.contains("unit") }, 
            "Expected return type mismatch with 'unit', but got: ${result.diagnostics}")
    }

    @Test
    fun assignmentTypeMismatchShouldFail() {
        val source = """
            struct user {
                name: string = "John"
                age: num = 18
            }
            
            func test(u: user) {
                u.age = "hello"
            }
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.any { it.message.contains("type mismatch") && it.message.contains("string") && it.message.contains("num") }, 
            "Expected assignment type mismatch, but got: ${result.diagnostics}")
    }

    @Test
    fun assignmentInChainedExpressionShouldFail() {
        val source = """
            struct user {
                name: string = "John"
                age: num = 18
            }
            
            func test(u: user) {
                val s = "Name: " + (u.name = "Bob")
            }
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.any { it.message.contains("invalid operands") && it.message.contains("unit") }, 
            "Expected invalid operands error with 'unit', got: ${result.diagnostics}")
    }

    @Test
    fun functionMutationShouldCompile() {
        val source = """
            struct user {
                name: string = "John"
                age: num = 18
            }
            
            func change_username(u: user, name: string): string {
                u.name = name
                return u.name
            }
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "Expected no errors, but got: ${result.diagnostics}")
    }
}
