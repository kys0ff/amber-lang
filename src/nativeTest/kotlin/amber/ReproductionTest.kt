package amber

import amber.compiler.Transpiler
import amber.compiler.compilerConfig
import amber.compiler.diagnostic.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertTrue

class ReproductionTest {

    @Test
    fun reproduceUserIssue() {
        val source = """
            use "core:io"

            struct user {
              name = "John"
              age = 18
            }

            var users: user[] = [user(), user(name = "Smith"), user(name = "Phoebe", age = 14)]

            users += user()

            io.println(users)

            func change_username(u: user, name: string): unit {
              u.name = name
            }

            var john = user()
            io.println("Hello " + john.name + " you are " + john.age + " years old")
            change_username(john, "Johny")
            io.println("Hello " + john.name + " you are " + john.age + " years old")


            for (user in users) {
                if (user.age >= 18) {
                    io.println(user.name + " is an adult")
                    break
                } else {
                    io.println(user.name + " is a minor")
                }
            }

            var index = 0

            while(true) {
                val item = users[index]
                if (item.age >= 18) {
                    io.println(item.name + " is an adult")
                    break
                } else {
                    io.println(item.name + " is a minor")
                }
                index++
            }
        """.trimIndent()

        val transpiler = Transpiler(
            compilerConfig {
                projectRoot = "/tmp"
                entryFile = "/tmp/test.amb"
                executableDir = "/home/kys0adam/IdeaProjects/amber-lang"
            }
        )
        val result = transpiler.transpile(source, "/tmp/test.amb")

        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "Expected no compilation errors")
    }

    @Test
    fun testGetOrErrInUserScript() {
        val source = """
            use "core:io"
            use "core:list"
            
            struct user {
              name: string = "John"
              age: num = 18
            }

            var users: user[] = [user()]
            val _ = list.get_or_err(users, 0) or panic
        """.trimIndent()
        
        val transpiler = Transpiler(
            compilerConfig {
                projectRoot = "/tmp"
                entryFile = "/tmp/test.amb"
                executableDir = "/home/kys0adam/IdeaProjects/amber-lang"
            }
        )
        val result = transpiler.transpile(source, "/tmp/test.amb")
        assertTrue(result.diagnostics.none { it.severity == DiagnosticSeverity.ERROR }, "Expected no errors, got: ${result.diagnostics}")
    }
}
