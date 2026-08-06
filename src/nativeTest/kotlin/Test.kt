package amber

import amber.compiler.Transpiler
import amber.compiler.compilerConfig
import amber.compiler.diagnostic.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertTrue

class Test {

    @Test
    fun supportsNumericCompoundAssignmentsAndIncDec() {
        val source = """
            var n: num = 10
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
            var n: num = 10
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
            val _c: any = 'z'
        """.trimIndent()

        val result = transpile(source)

        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "expected no errors for any type annotation, got: ${result.diagnostics}")
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
            val n: num = 42
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

    @Test
    fun supportsStringTemplates() {
        val source = $$"""
            val name = "Amber"
            val version = 1.0
            val _s1 = "Hello $name"
            val _s2 = "Version ${version + 0.1}"
            val _s3 = "Escaped \$dollar"
            val _s4 = "Complex: $name version ${version}"
        """.trimIndent()

        val result = transpile(source)

        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "expected no errors for string templates, got: ${result.diagnostics}")
        assertTrue(result.code!!.contains("__amber_rt_str_concat"), "code should contain __amber_rt_str_concat")
    }

    @Test
    fun infersReturnTypeFromSingleExpressionBody() {
        val source = """
            use "core:str"
            func get_name(number: num = 4) {
              "name: " + str.to_string(number)
            }
            val s: string = get_name(10)
        """.trimIndent()

        val result = transpile(source)

        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "expected no errors for inferred return type, got: ${result.diagnostics}")
    }

    @Test
    fun rejectsLegacyTypeNames() {
        val source = """
            val b: boolean = true
            val n: number = 10
        """.trimIndent()

        val result = transpile(source)

        assertTrue(result.diagnostics.any { it.message.contains("unknown type 'boolean'") }, "expected error for 'boolean' type")
        assertTrue(result.diagnostics.any { it.message.contains("unknown type 'number'") }, "expected error for 'number' type")
    }

    @Test
    fun warnsOnUselessUnsafeAnnotations() {
        val source = """
            func safe_func(): num! {
                return 1
            }
            val safe_val: num! = 2
        """.trimIndent()

        val result = transpile(source)

        assertTrue(result.diagnostics.any { it.message.contains("useless '!': function is safe and never returns an error") }, "expected warning for useless '!' on function")
        assertTrue(result.diagnostics.any { it.message.contains("useless '!': initializer is already safe") }, "expected warning for useless '!' on variable")
    }

    @Test
    fun warnsOnUselessOrCatch() {
        val source = """
            val x = 1 or catch(_) { 2 }
        """.trimIndent()

        val result = transpile(source)

        assertTrue(result.diagnostics.any { it.message.contains("useless 'or catch': expression is already safe") }, "expected warning for useless 'or catch'")
    }

    @Test
    fun enforcesErrorHandlingAndSuggestsAnnotation() {
        val source = """
            func unsafe(): num! { return 1 }
            func call_unsafe() {
                val _ = unsafe()
            }
        """.trimIndent()

        val result = transpile(source)

        val error = result.diagnostics.find { it.message.contains("unhandled unsafe call") }
        assertTrue(error != null, "expected error for unhandled unsafe call")
        assertTrue(error.suggestion!!.contains("annotate the current function as unsafe '!'"), "expected suggestion to annotate function")
    }

    @Test
    fun warnsOnUselessOrCatchAndMaintainsType() {
        val source = """
            val bb: bool = false or catch(_) { true }
        """.trimIndent()

        val result = transpile(source)

        assertTrue(result.diagnostics.any { it.message.contains("useless 'or catch': expression is already safe") }, "expected warning for useless 'or catch'")
        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "expected no errors for valid assignment with useless or catch. Errors: ${result.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }}")
    }

    private fun transpile(source: String) = Transpiler(
        compilerConfig {
            projectRoot = "/tmp"
            entryFile = "/tmp/test.amb"
            executableDir = "/home/kys0adam/IdeaProjects/amber-lang"
        }
    ).transpile(source, "/tmp/test.amb")
}
