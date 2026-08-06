package amber

import amber.compiler.compilerConfig
import amber.compiler.Transpiler
import amber.compiler.diagnostic.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertTrue

class ImportTest {

    @Test
    fun testCoreImportNaming() {
        val source = """
            use "core:math"
            use "core:io"
            io.println(math.pi)
        """.trimIndent()
        val result = transpile(source, isProject = false)
        
        result.diagnostics.forEach { println("Error: ${it.message}") }
        
        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, 
            "Should resolve core:math without errors. Actual errors: ${result.diagnostics.map { it.message }}")
    }

    @Test
    fun testLocalImportInScriptModeFails() {
        val source = """
            use "local:my_mod"
        """.trimIndent()
        val result = transpile(source, isProject = false)
        assertTrue(result.diagnostics.any { it.message.contains("local imports are only allowed in project mode") }, 
            "Local import should fail in script mode")
    }

    @Test
    fun testPkgImportInScriptModeFails() {
        val source = """
            use "pkg:some_pkg"
        """.trimIndent()
        val result = transpile(source, isProject = false)
        assertTrue(result.diagnostics.any { it.message.contains("package imports are only allowed in project mode") }, 
            "Package import should fail in script mode")
    }

    @Test
    fun testNestedImportNaming() {
        val source = """
            use "core:net:http"
            use "core:io"
            io.println(http.get("/"))
        """.trimIndent()
        val result = transpile(source, isProject = false)
        assertTrue(result.diagnostics.any { it.message.contains("import error") || it.message.contains("could not resolve") || it.type.contains("Import Error") },
            "Should attempt to resolve core:net:http")
    }

    @Test
    fun testLocalImportInProjectMode() {
        val source = """
            use "local:my_mod"
        """.trimIndent()
        val result = transpile(source, isProject = true)
        assertFalse(result.diagnostics.any { it.message.contains("local imports are only allowed in project mode") },
            "Local import should be allowed in project mode")
    }

    private fun assertFalse(condition: Boolean, message: String) {
        assertTrue(!condition, message)
    }

    private fun transpile(source: String, isProject: Boolean) = Transpiler(
        compilerConfig {
            projectRoot = "/tmp"
            entryFile = "/tmp/test.amb"
            this.isProject = isProject
            executableDir = "/home/kys0adam/IdeaProjects/amber-lang"
        }
    ).transpile(source, "/tmp/test.amb")
}
