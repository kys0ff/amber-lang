package amber

import amber.compiler.compilerConfig
import amber.compiler.Transpiler
import amber.compiler.lexer.Lexer
import amber.compiler.parser.Parser
import amber.compiler.type.TypeChecker
import amber.runtime.CRuntimeProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnusedWarningTest {

    @Test
    fun testUnusedVariableWarning() {
        val source = """
            use "core:io"
            val x = 10
            io.println("Hello")
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.any { it.message.contains("unused declaration: 'x'") && it.type == "Warning" }, 
            "Should have unused variable warning for 'x'. Actual errors: ${result.diagnostics.map { "${it.type}: ${it.message}" }}")
    }

    @Test
    fun testNoUnusedWarningInImportedFile() {
        val source = """
            func foo() {
            }
        """.trimIndent()
        // Simulate being an imported file
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens, "/tmp/imported.amb")
        val (program, _) = parser.parseProgram()
        
        val typeChecker = TypeChecker(
            compilerConfig {
                projectRoot = "/tmp"
                isProject = false
                executableDir = "/tmp"
            },
            currentFilePath = "/tmp/imported.amb",
            runtimeProvider = CRuntimeProvider(),
            isMainFile = false // This is the key
        )
        typeChecker.check(program)
        
        assertFalse(typeChecker.errors.any { it.message.contains("unused declaration: 'foo'") },
            "Should not have unused warning for 'foo' in an imported file. Actual errors: ${typeChecker.errors.map { "${it.type}: ${it.message}" }}")
    }

    @Test
    fun testUnusedWarningInMainFile() {
        val source = """
            func foo() {
            }
        """.trimIndent()
        // Simulate being a main file
        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens, "/tmp/main.amb")
        val (program, _) = parser.parseProgram()
        
        val typeChecker = TypeChecker(
            compilerConfig {
                projectRoot = "/tmp"
                isProject = false
                executableDir = "/tmp"
            },
            currentFilePath = "/tmp/main.amb",
            runtimeProvider = CRuntimeProvider(),
            isMainFile = true // Main file
        )
        typeChecker.check(program)
        
        assertTrue(typeChecker.errors.any { it.message.contains("unused declaration: 'foo'") },
            "Should have unused warning for 'foo' in a main file.")
    }

    @Test
    fun testUsedVariableNoWarning() {
        val source = """
            use "core:io"
            val x = 10
            io.println(x)
        """.trimIndent()
        val result = transpile(source)
        assertFalse(result.diagnostics.any { it.message.contains("unused declaration: 'x'") }, 
            "Should not have unused variable warning for 'x'. Actual errors: ${result.diagnostics.map { "${it.type}: ${it.message}" }}")
    }

    @Test
    fun testUnusedFunctionWarning() {
        val source = """
            use "core:io"
            func foo() {
                io.println("foo")
            }
            io.println("Hello")
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.any { it.message.contains("unused declaration: 'foo'") && it.type == "Warning" },
            "Should have unused function warning for 'foo'. Actual errors: ${result.diagnostics.map { "${it.type}: ${it.message}" }}")
    }

    @Test
    fun testUnusedParameterWarning() {
        val source = """
            use "core:io"
            func foo(x: number) {
                io.println("foo")
            }
            foo(10)
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.any { it.message.contains("unused declaration: 'x'") && it.type == "Warning" },
            "Should have unused parameter warning for 'x'. Actual errors: ${result.diagnostics.map { "${it.type}: ${it.message}" }}")
    }

    @Test
    fun testUnusedReturnValueWarning() {
        val source = """
            func foo(): number {
                return 10
            }
            foo()
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.any { it.message.contains("unused return value") && it.type == "Warning" },
            "Should have unused return value warning for 'foo()'. Actual errors: ${result.diagnostics.map { "${it.type}: ${it.message}" }}")
    }

    @Test
    fun testUnusedReturnValueSuggestion() {
        val source = """
            func foo(): number {
                return 10
            }
            foo()
        """.trimIndent()
        val result = transpile(source)
        val warning = result.diagnostics.find { it.message.contains("unused return value") }
        assertEquals(
            warning?.suggestion,
            "prefix with '_' if this is intentional",
            "Suggestion should be 'prefix with '_' if this is intentional'. Actual: ${warning?.suggestion}"
        )
    }

    @Test
    fun testUnusedVariableSuggestion() {
        val source = "val x = 10"
        val result = transpile(source)
        val warning = result.diagnostics.find { it.message.contains("unused declaration: 'x'") }
        assertEquals(
            warning?.suggestion,
            "remove it",
            "Suggestion should be 'remove it'. Actual: ${warning?.suggestion}"
        )
    }

    @Test
    fun testCatchBlockReturnValueNoWarning() {
        val source = """
            use "core:io"
            func your_name(): string! {
                panic "Yoo"
                return "Name"
            }
            io.println(
                your_name() or catch(_) {
                    io.println("First")
                    "Got"
                }
            )
        """.trimIndent()
        val result = transpile(source)
        assertFalse(result.diagnostics.any { it.message.contains("unused return value") },
            "Should not have unused return value warning for 'Got' in catch block. Actual errors: ${result.diagnostics.filter { it.type == "Warning" }.map { "${it.type}: ${it.message}" }}")
    }

    @Test
    fun testUnusedReturnValueInCatchBlockWarning() {
        val source = """
            use "core:io"
            func your_name(): string! {
                panic "Yoo"
                return "Name"
            }
            io.println(
                your_name() or catch(_) {
                    "unused"
                    "used"
                }
            )
        """.trimIndent()
        val result = transpile(source)
        assertTrue(result.diagnostics.any { it.message.contains("unused return value") && it.line == 8 },
            "Should have unused return value warning for 'unused' in catch block. Actual errors: ${result.diagnostics.filter { it.type == "Warning" }.map { "${it.line}: ${it.message}" }}")
    }

    private fun transpile(source: String) = Transpiler(
        compilerConfig {
            projectRoot = "/tmp"
            entryFile = "/tmp/test.amb"
            executableDir = "/home/kys0adam/IdeaProjects/amber-lang"
        }
    ).transpile(source, "/tmp/test.amb")
}
