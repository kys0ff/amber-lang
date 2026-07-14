package off.kys.amber_lang

import off.kys.amber_lang.transpiler.Transpiler
import kotlin.test.Test
import kotlin.test.assertTrue

class ImportTest {

    @Test
    fun testCoreImportNaming() {
        // use "core:math" should be imported as "math"
        val source = """
            use "core:math"
            use "core:io"
            io.println(math.pi)
        """.trimIndent()
        val result = transpile(source, isProject = false)
        
        // Let's print the errors to see what we get
        result.errors.forEach { println("Error: ${it.message}") }
        
        assertTrue(result.errors.any { it.message.contains("import error") || it.message.contains("could not resolve") || it.type.contains("Import Error") }, 
            "Should attempt to resolve core:math. Actual errors: ${result.errors.map { it.message }}")
    }

    @Test
    fun testLocalImportInScriptModeFails() {
        val source = """
            use "local:my_mod"
        """.trimIndent()
        val result = transpile(source, isProject = false)
        assertTrue(result.errors.any { it.message.contains("local imports are only allowed in project mode") }, 
            "Local import should fail in script mode")
    }

    @Test
    fun testPkgImportInScriptModeFails() {
        val source = """
            use "pkg:some_pkg"
        """.trimIndent()
        val result = transpile(source, isProject = false)
        assertTrue(result.errors.any { it.message.contains("package imports are only allowed in project mode") }, 
            "Package import should fail in script mode")
    }

    @Test
    fun testNestedImportNaming() {
        // use "core:net:http" should be imported as "http"
        val source = """
            use "core:net:http"
            use "core:io"
            io.println(http.get("/"))
        """.trimIndent()
        val result = transpile(source, isProject = false)
        assertTrue(result.errors.any { it.message.contains("import error") || it.message.contains("could not resolve") || it.type.contains("Import Error") },
            "Should attempt to resolve core:net:http")
    }

    @Test
    fun testLocalImportInProjectMode() {
        // We can't easily mock the file system here, but we can verify it doesn't throw "only allowed in project mode"
        val source = """
            use "local:my_mod"
        """.trimIndent()
        val result = transpile(source, isProject = true)
        assertFalse(result.errors.any { it.message.contains("local imports are only allowed in project mode") },
            "Local import should be allowed in project mode")
    }

    private fun assertFalse(condition: Boolean, message: String) {
        assertTrue(!condition, message)
    }

    private fun transpile(source: String, isProject: Boolean) = Transpiler(
        sourceCode = source,
        currentFilePath = "/tmp/test.amb",
        projectRoot = "/tmp",
        isProject = isProject,
        executableDir = "/tmp/bin"
    ).transpile()
}
