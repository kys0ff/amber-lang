package amber

import amber.compiler.formatter.Formatter
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatterTest {
    private val formatter = Formatter()

    private fun assertFormat(source: String, expected: String) {
        val formatted = formatter.format(source, "test.amb")
        assertEquals(expected, formatted)
    }

    private fun assertIdempotent(source: String) {
        val first = formatter.format(source, "test.amb")
        val second = formatter.format(first, "test.amb")
        assertEquals(first, second, "Formatter is not idempotent")
    }

    @Test
    fun testBasicFormatting() {
        assertFormat(
            "func main(){val x=1+2\nreturn x}",
            """
            func main() {
                val x = 1 + 2
                return x
            }
            
            """.trimIndent()
        )
    }

    @Test
    fun testSpacing() {
        assertFormat(
            "val x : num = 10",
            "val x: num = 10\n"
        )
        assertFormat(
            "func test(a: string, b: num) {}",
            "func test(a: string, b: num) {}\n"
        )
    }

    @Test
    fun testIndentation() {
        assertFormat(
            """
            func main() {
            if (true) {
            val x = 1
            }
            }
            """.trimIndent(),
            """
            func main() {
                if (true) {
                    val x = 1
                }
            }
            
            """.trimIndent()
        )
    }

    @Test
    fun testComments() {
        // Trailing comments currently might be moved to a new line if they are before a node that emits a newline
        // but let's test a simple case.
        assertFormat(
            """
            # This is a comment
            func main() {
                # Nested comment
                val x = 1
            }
            """.trimIndent(),
            """
            # This is a comment
            func main() {
                # Nested comment
                val x = 1
            }
            
            """.trimIndent()
        )
    }

    @Test
    fun testIdempotency() {
        assertIdempotent("func main() {\n    val x = 1 + 2\n}")
        assertIdempotent("val x = 1\n# comment\nval y = 2")
    }

    @Test
    fun testEmptyLines() {
        // Current implementation does not preserve double newlines to ensure idempotency easily
        assertFormat(
            """
            val x = 1

            val y = 2
            """.trimIndent(),
            """
            val x = 1
            
            val y = 2
            
            """.trimIndent()
        )
    }

    @Test
    fun testImports() {
        assertFormat(
            """
            use "core:list"
            use "core:str" as s
            """.trimIndent(),
            """
            use "core:list"
            use "core:str" as s
            
            """.trimIndent()
        )
    }

    @Test
    fun testInvalidSource() {
        val invalid = "func main( {"
        assertFormat(invalid, invalid)
    }
}
