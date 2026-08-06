package amber

import amber.compiler.CompilerConfig
import amber.compiler.Transpiler
import amber.compiler.diagnostic.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertTrue

class Test {

    @Test
    fun supportsNumericCompoundAssignmentsAndIncDec() {
        val source = """
            var n: number = 10
            n += 2
            n -= 1
            n *= 3
            n /= 2
            n %= 4
            n++
            --n
        """.trimIndent()

        val result = transpile(source)

        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "expected no transpilation errors, got: ${result.diagnostics}")
    }

    @Test
    fun rejectsIncDecForNonNumericTypes() {
        val source = """
            var s: string = "hello"
            s++
        """.trimIndent()

        val result = transpile(source)

        assertTrue(result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }, "expected type error for string increment")
    }

    @Test
    fun rejectsIncrementForInvalidTargets() {
        val source = """
            var n: number = 10
            (n + 1)++
        """.trimIndent()

        val result = transpile(source)

        assertTrue(result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }, "expected syntax error for invalid increment target")
    }

    @Test
    fun infersUntypedFunctionParameterFromFirstCall() {
        val source = """
            func consume(value) {
            }

            consume(1)
            consume("text")
        """.trimIndent()

        val result = transpile(source)

        assertTrue(result.diagnostics.any { it.message.contains("argument 1 type mismatch") }, "expected argument mismatch after first call infers parameter type")
    }

    @Test
    fun keepsExplicitAnyParameterFlexible() {
        val source = """
            func consume(_value: any) {
            }

            consume(1)
            consume("text")
        """.trimIndent()

        val result = transpile(source)

        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "expected explicit any parameter to stay flexible, got: ${result.diagnostics}")
    }

    @Test
    fun infersCharLiteralType() {
        val source = """
            val _c = 'a'
        """.trimIndent()

        val result = transpile(source)

        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "expected no errors for char literal, got: ${result.diagnostics}")
    }

    @Test
    fun acceptsExplicitCharAnnotation() {
        val source = """
            val _c: char = 'z'
        """.trimIndent()

        val result = transpile(source)

        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "expected no errors for char type annotation, got: ${result.diagnostics}")
    }

    @Test
    fun rejectsCharAssignedToString() {
        val source = """
            val s: string = 'x'
        """.trimIndent()

        val result = transpile(source)

        assertTrue(result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }, "expected type error when assigning char to string")
    }

    @Test
    fun supportsToStringConversion() {
        val source = """
            use "core:io"
            use "core:str"
            val n: number = 42
            val s: string = str.to_string(n)
            val b: string = str.to_string(true)
            val a: any = false
            val sa: string = str.to_string(a)
            io.println("Value: " + str.to_string(100))
            io.println(s)
            io.println(b)
            io.println(sa)
        """.trimIndent()

        val result = transpile(source)

        val errors = result.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }
        assertTrue(errors.isEmpty(), "expected no transpilation errors for to_string, got: $errors")
    }

    @Test
    fun supportsListPrinting() {
        val source = """
            use "core:io"
            val fruits = ["apple", "banana"]
            io.println(fruits)
            io.println("List: " + fruits)
        """.trimIndent()

        val result = transpile(source)

        val errors = result.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }
        assertTrue(errors.isEmpty(), "expected no transpilation errors for list printing, got: $errors")
        assertTrue(result.code!!.contains("__amber_rt_println"), "code should contain __amber_rt_println function")
    }

    private fun transpile(source: String) = Transpiler(
        CompilerConfig(
            projectRoot = "/tmp",
            entryFile = "/tmp/test.amb",
            executableDir = "/home/kys0adam/IdeaProjects/amber-lang"
        )
    ).transpile(source, "/tmp/test.amb")
}
