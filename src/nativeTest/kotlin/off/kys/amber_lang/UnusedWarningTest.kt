package off.kys.amber_lang

import off.kys.amber_lang.transpiler.Transpiler
import kotlin.test.Test
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
        assertTrue(result.errors.any { it.message.contains("unused declaration: 'x'") && it.type == "Warning" }, 
            "Should have unused variable warning for 'x'. Actual errors: ${result.errors.map { "${it.type}: ${it.message}" }}")
    }

    @Test
    fun testNoUnusedWarningInImportedFile() {
        val source = """
            func foo() {
            }
        """.trimIndent()
        // Simulate being an imported file
        val lexer = off.kys.amber_lang.transpiler.lexer.Lexer(source)
        val tokens = lexer.tokenize()
        val parser = off.kys.amber_lang.transpiler.parser.Parser(tokens, "/tmp/imported.amb")
        val (program, _) = parser.parseProgram()
        
        val typeChecker = off.kys.amber_lang.transpiler.type.TypeChecker(
            projectRoot = "/tmp",
            currentFilePath = "/tmp/imported.amb",
            runtimeProvider = off.kys.amber_lang.runtime.BashRuntimeProvider(),
            isMainFile = false, // This is the key
            isProject = false,
            executableDir = "/tmp"
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
        val lexer = off.kys.amber_lang.transpiler.lexer.Lexer(source)
        val tokens = lexer.tokenize()
        val parser = off.kys.amber_lang.transpiler.parser.Parser(tokens, "/tmp/main.amb")
        val (program, _) = parser.parseProgram()
        
        val typeChecker = off.kys.amber_lang.transpiler.type.TypeChecker(
            projectRoot = "/tmp",
            currentFilePath = "/tmp/main.amb",
            runtimeProvider = off.kys.amber_lang.runtime.BashRuntimeProvider(),
            isMainFile = true, // Main file
            isProject = false,
            executableDir = "/tmp"
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
        assertFalse(result.errors.any { it.message.contains("unused declaration: 'x'") }, 
            "Should not have unused variable warning for 'x'. Actual errors: ${result.errors.map { "${it.type}: ${it.message}" }}")
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
        assertTrue(result.errors.any { it.message.contains("unused declaration: 'foo'") && it.type == "Warning" },
            "Should have unused function warning for 'foo'. Actual errors: ${result.errors.map { "${it.type}: ${it.message}" }}")
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
        assertTrue(result.errors.any { it.message.contains("unused declaration: 'x'") && it.type == "Warning" },
            "Should have unused parameter warning for 'x'. Actual errors: ${result.errors.map { "${it.type}: ${it.message}" }}")
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
        assertTrue(result.errors.any { it.message.contains("unused return value") && it.type == "Warning" },
            "Should have unused return value warning for 'foo()'. Actual errors: ${result.errors.map { "${it.type}: ${it.message}" }}")
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
        val warning = result.errors.find { it.message.contains("unused return value") }
        assertTrue(warning?.suggestion == "prefix with '_' if this is intentional",
            "Suggestion should be 'prefix with '_' if this is intentional'. Actual: ${warning?.suggestion}")
    }

    @Test
    fun testUnusedVariableSuggestion() {
        val source = "val x = 10"
        val result = transpile(source)
        val warning = result.errors.find { it.message.contains("unused declaration: 'x'") }
        assertTrue(warning?.suggestion == "remove it",
            "Suggestion should be 'remove it'. Actual: ${warning?.suggestion}")
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
        assertFalse(result.errors.any { it.message.contains("unused return value") },
            "Should not have unused return value warning for 'Got' in catch block. Actual errors: ${result.errors.filter { it.type == "Warning" }.map { "${it.type}: ${it.message}" }}")
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
        assertTrue(result.errors.any { it.message.contains("unused return value") && it.line == 8 },
            "Should have unused return value warning for 'unused' in catch block. Actual errors: ${result.errors.filter { it.type == "Warning" }.map { "${it.line}: ${it.message}" }}")
    }

    private fun transpile(source: String) = Transpiler(
        sourceCode = source,
        currentFilePath = "/tmp/test.amb",
        projectRoot = "/tmp",
        isProject = false,
        executableDir = "/home/kys0adam/IdeaProjects/amber-lang/build/bin/linuxX64/debugExecutable"
    ).transpile()
}
