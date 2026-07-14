package off.kys.amber_lang

import off.kys.amber_lang.transpiler.Severity
import off.kys.amber_lang.transpiler.Transpiler
import kotlin.test.Test
import kotlin.test.assertTrue

class RuntimeTypeCheckTest {

    @Test
    fun supportsIsOperatorForNumbers() {
        val source = """
            use "core:io"
            val x: any = 10
            if (x is number) {
                io.println("yes")
            }
        """.trimIndent()

        val result = transpile(source)
        val errors = result.errors.filter { it.severity == Severity.ERROR }
        assertTrue(errors.isEmpty(), "expected no errors, got: $errors")
        assertTrue(result.code!!.contains("[[ \"${'$'}__amber_x\" == num:* ]]"), "bash should check for num tag")
    }

    @Test
    fun supportsIsOperatorForStrings() {
        val source = """
            use "core:io"
            val x: any = "hi"
            if (x is string) {
                io.println("yes")
            }
        """.trimIndent()

        val result = transpile(source)
        val errors = result.errors.filter { it.severity == Severity.ERROR }
        assertTrue(errors.isEmpty(), "expected no errors, got: $errors")
        assertTrue(result.code!!.contains("[[ \"${'$'}__amber_x\" == str:* ]]"), "bash should check for str tag")
    }

    @Test
    fun distinguishesNumberFromStringAtRuntime() {
        val source = """
            use "core:io"
            val x: any = 10
            val y: any = "10"
            if (x is number) { io.println("x is num") }
            if (y is number) { io.println("y is num") }
        """.trimIndent()

        val result = transpile(source)
        // Check for variable assignment with tags
        assertTrue(result.code!!.contains("__amber_x="), "x should be declared")
        assertTrue(result.code.contains("num:10.0"), "x should be assigned a num tag")
        assertTrue(result.code.contains("__amber_y="), "y should be declared")
        assertTrue(result.code.contains("str:10"), "y should be assigned a str tag")
    }

    private fun transpile(source: String) = Transpiler(
        sourceCode = source,
        currentFilePath = "/tmp/test.amb",
        projectRoot = "/tmp",
        executableDir = "/home/kys0adam/IdeaProjects/amber-lang/build/bin/linuxX64/debugExecutable"
    ).transpile()
}
