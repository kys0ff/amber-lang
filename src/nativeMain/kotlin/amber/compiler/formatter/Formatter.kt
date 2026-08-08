package amber.compiler.formatter

import amber.compiler.ast.ArrayLiteralExpression
import amber.compiler.ast.AssignmentExpression
import amber.compiler.ast.AstNode
import amber.compiler.ast.BinaryExpression
import amber.compiler.ast.BlockStatement
import amber.compiler.ast.BreakStatement
import amber.compiler.ast.CallExpression
import amber.compiler.ast.CatchExpression
import amber.compiler.ast.ContinueStatement
import amber.compiler.ast.EnumDeclaration
import amber.compiler.ast.ErrorNode
import amber.compiler.ast.Expression
import amber.compiler.ast.ExpressionStatement
import amber.compiler.ast.ExtensionDeclaration
import amber.compiler.ast.ForStatement
import amber.compiler.ast.FunctionDeclaration
import amber.compiler.ast.IdentifierExpression
import amber.compiler.ast.IfStatement
import amber.compiler.ast.ImportStatement
import amber.compiler.ast.IndexAccessExpression
import amber.compiler.ast.IsExpression
import amber.compiler.ast.LiteralExpression
import amber.compiler.ast.MemberAccessExpression
import amber.compiler.ast.NamedArgumentExpression
import amber.compiler.ast.PanicExpression
import amber.compiler.ast.Parameter
import amber.compiler.ast.Program
import amber.compiler.ast.ReturnStatement
import amber.compiler.ast.Statement
import amber.compiler.ast.StringTemplateExpression
import amber.compiler.ast.StructDeclaration
import amber.compiler.ast.StructField
import amber.compiler.ast.UnaryExpression
import amber.compiler.ast.VariableDeclaration
import amber.compiler.ast.WhileStatement
import amber.compiler.lexer.Lexer
import amber.compiler.lexer.Token
import amber.compiler.parser.Parser

class Formatter(private val options: FormattingOptions = FormattingOptions()) {

    fun format(source: String, filePath: String): String {
        val lexer = Lexer(source)
        val allTokens = lexer.tokenize(keepTrivia = true)
        val pureTokens = allTokens.filter { 
            it !is Token.Whitespace && it !is Token.Comment
        }

        val parser = Parser(pureTokens, filePath)
        val (program, errors) = parser.parseProgram()

        if (errors.isNotEmpty()) {
            return source
        }

        val context = FormattingContext(options, allTokens)
        context.visit(program)
        
        // Append remaining comments
        context.emitCommentsUntil(-1)
        
        var result = context.toString()
        if (options.trimTrailingWhitespace) {
            result = result.lines().joinToString("\n") { it.trimEnd() }
        }
        if (options.insertFinalNewline && !result.endsWith("\n") && result.isNotEmpty()) {
            result += "\n"
        }
        
        return result
    }

    private class FormattingContext(
        val options: FormattingOptions,
        val allTokens: List<Token>
    ) {
        private var indentLevel = 0
        private val sb = StringBuilder()
        private var lastWasNewline = true
        private var currentTokenIndex = 0

        override fun toString(): String = sb.toString()

        fun emitCommentsUntil(line: Int) {
            while (currentTokenIndex < allTokens.size) {
                val token = allTokens[currentTokenIndex]
                if (line != -1 && token.line >= line) break
                
                if (token is Token.Comment) {
                    if (!lastWasNewline) append(" ")
                    else appendIndent()
                    append(token.value)
                    appendNewline()
                }
                currentTokenIndex++
            }
            
            // Skip pure tokens that we are visiting via AST
            while (currentTokenIndex < allTokens.size) {
                val token = allTokens[currentTokenIndex]
                if (line != -1 && token.line >= line) break
                if (token !is Token.Comment && token !is Token.Newline && token !is Token.Whitespace) {
                    currentTokenIndex++
                } else {
                    break
                }
            }
        }

        fun visit(node: AstNode) {
            emitCommentsUntil(node.line)
            
            when (node) {
                is Program -> visitProgram(node)
                is Statement -> visitStatement(node)
                is Expression -> visitExpression(node)
                is Parameter -> visitParameter(node)
                is StructField -> visitStructField(node)
                else -> {}
            }
        }

        private fun visitProgram(node: Program) {
            node.statements.forEachIndexed { index, stmt ->
                visit(stmt)
                if (index < node.statements.size - 1) {
                    if (!lastWasNewline) appendNewline()
                    // Extra newline between top-level declarations
                    if (shouldHaveExtraNewline(stmt, node.statements[index + 1])) {
                        appendNewline()
                    }
                }
            }
        }

        private fun shouldHaveExtraNewline(current: Statement, next: Statement): Boolean {
            if (current is ImportStatement && next is ImportStatement) return false
            return true
        }

        private fun visitStatement(node: Statement) {
            when (node) {
                is ImportStatement -> {
                    appendIndent()
                    append("use \"${node.path}")
                    if (node.importedMember != null) {
                        append(".${node.importedMember}")
                    }
                    append("\"")
                    if (node.asName != null) {
                        append(" as ")
                        visit(node.asName)
                    }
                }
                is FunctionDeclaration -> {
                    appendIndent()
                    if (node.isIntrinsic) append("intrinsic ")
                    append("func ")
                    visit(node.name)
                    append("(")
                    node.parameters.forEachIndexed { index, param ->
                        visit(param)
                        if (index < node.parameters.size - 1) append(", ")
                    }
                    append(")")
                    if (node.returnTypeAnnotation != null) {
                        append(": ${node.returnTypeAnnotation}")
                    }
                    if (node.body != null) {
                        append(" ")
                        visit(node.body)
                    }
                }
                is VariableDeclaration -> {
                    appendIndent()
                    if (node.isIntrinsic) append("intrinsic ")
                    append(if (node.isMutable) "var " else "val ")
                    visit(node.name)
                    if (node.typeAnnotation != null) {
                        append(": ${node.typeAnnotation}")
                    }
                    if (node.initializer != null) {
                        append(" = ")
                        visit(node.initializer)
                    }
                }
                is EnumDeclaration -> {
                    appendIndent()
                    append("enum ")
                    visit(node.name)
                    append(" {")
                    if (node.variants.isNotEmpty()) {
                        appendNewline()
                        indentLevel++
                        node.variants.forEach { variant ->
                            appendIndent()
                            visit(variant)
                            append(",")
                            appendNewline()
                        }
                        indentLevel--
                        appendIndent()
                    }
                    append("}")
                }
                is StructDeclaration -> {
                    appendIndent()
                    append("struct ")
                    visit(node.name)
                    append(" {")
                    if (node.fields.isNotEmpty()) {
                        appendNewline()
                        indentLevel++
                        node.fields.forEach { field ->
                            appendIndent()
                            visit(field)
                            append(",")
                            appendNewline()
                        }
                        indentLevel--
                        appendIndent()
                    }
                    append("}")
                }
                is IfStatement -> {
                    appendIndent()
                    append("if (")
                    visit(node.condition)
                    append(") ")
                    visit(node.thenBranch)
                    if (node.elseBranch != null) {
                        append(" else ")
                        visit(node.elseBranch)
                    }
                }
                is WhileStatement -> {
                    appendIndent()
                    append("while (")
                    visit(node.condition)
                    append(") ")
                    visit(node.body)
                }
                is ForStatement -> {
                    appendIndent()
                    append("for (")
                    if (node.indexName != null) {
                        visit(node.indexName)
                        if (node.indexTypeAnnotation != null) {
                            append(": ${node.indexTypeAnnotation}")
                        }
                        append(", ")
                    }
                    visit(node.itemName)
                    if (node.itemTypeAnnotation != null) {
                        append(": ${node.itemTypeAnnotation}")
                    }
                    append(" in ")
                    visit(node.iterable)
                    append(") ")
                    visit(node.body)
                }
                is BreakStatement -> {
                    appendIndent()
                    append("break")
                }
                is ContinueStatement -> {
                    appendIndent()
                    append("continue")
                }
                is ReturnStatement -> {
                    appendIndent()
                    append("return")
                    if (node.value != null) {
                        append(" ")
                        visit(node.value)
                    }
                }
                is BlockStatement -> {
                    append("{")
                    if (node.statements.isNotEmpty()) {
                        appendNewline()
                        indentLevel++
                        node.statements.forEach { stmt ->
                            visit(stmt)
                            if (!lastWasNewline) appendNewline()
                        }
                        indentLevel--
                        appendIndent()
                    }
                    append("}")
                }
                is ExtensionDeclaration -> {
                    appendIndent()
                    append("extend ${node.targetType} {")
                    if (node.functions.isNotEmpty()) {
                        appendNewline()
                        indentLevel++
                        node.functions.forEach { func ->
                            visit(func)
                            appendNewline()
                        }
                        indentLevel--
                        appendIndent()
                    }
                    append("}")
                }
                is ExpressionStatement -> {
                    appendIndent()
                    visit(node.expression)
                }
            }
        }

        private fun visitParameter(node: Parameter) {
            visit(node.name)
            if (node.typeAnnotation != null) {
                append(": ${node.typeAnnotation}")
            }
            if (node.defaultValue != null) {
                append(" = ")
                visit(node.defaultValue)
            }
        }

        private fun visitStructField(node: StructField) {
            visit(node.name)
            if (node.typeAnnotation != null) {
                append(": ${node.typeAnnotation}")
            }
            if (node.defaultValue != null) {
                append(" = ")
                visit(node.defaultValue)
            }
        }

        private fun visitExpression(node: Expression) {
            when (node) {
                is BinaryExpression -> {
                    visit(node.left)
                    append(" ${node.operator} ")
                    visit(node.right)
                }
                is IsExpression -> {
                    visit(node.left)
                    append(" is ${node.typeName}")
                }
                is UnaryExpression -> {
                    append(node.operator)
                    visit(node.operand)
                }
                is LiteralExpression -> {
                    when (val v = node.value) {
                        is String -> append("\"$v\"")
                        is Char -> append("'$v'")
                        is Double -> {
                            if (v == v.toLong().toDouble()) append(v.toLong().toString())
                            else append(v.toString())
                        }
                        null -> append("null")
                        else -> append(v.toString())
                    }
                }
                is IdentifierExpression -> append(node.name)
                is CallExpression -> {
                    visit(node.callee)
                    append("(")
                    node.arguments.forEachIndexed { index, arg ->
                        visit(arg)
                        if (index < node.arguments.size - 1) append(", ")
                    }
                    append(")")
                }
                is NamedArgumentExpression -> {
                    visit(node.name)
                    append(" = ")
                    visit(node.value)
                }
                is PanicExpression -> {
                    append("panic")
                    if (node.message != null) {
                        append(" ")
                        visit(node.message)
                    }
                }
                is CatchExpression -> {
                    visit(node.target)
                    append(" or ")
                    if (node.body.statements.size == 1 && node.body.statements[0] is ExpressionStatement && (node.body.statements[0] as ExpressionStatement).expression is PanicExpression) {
                       val panic = (node.body.statements[0] as ExpressionStatement).expression as PanicExpression
                       append("panic")
                       if (panic.message != null && !(panic.message is IdentifierExpression && panic.message.isSynthetic)) {
                           append(" ")
                           visit(panic.message)
                       }
                    } else {
                        append("catch (${node.errorVarName}")
                        if (node.errorVarType != null) append(": ${node.errorVarType}")
                        append(") ")
                        visit(node.body)
                    }
                }
                is IndexAccessExpression -> {
                    visit(node.target)
                    append("[")
                    visit(node.index)
                    append("]")
                }
                is ArrayLiteralExpression -> {
                    append("[")
                    node.elements.forEachIndexed { index, elem ->
                        visit(elem)
                        if (index < node.elements.size - 1) append(", ")
                    }
                    append("]")
                }
                is AssignmentExpression -> {
                    visit(node.target)
                    append(" = ")
                    visit(node.value)
                }
                is MemberAccessExpression -> {
                    visit(node.target)
                    append(".")
                    visit(node.member)
                }
                is StringTemplateExpression -> {
                    append("\"")
                    node.segments.forEach { segment ->
                        if (segment is LiteralExpression && segment.value is String) {
                            append(segment.value.replace("$", "\\$"))
                        } else {
                            append("\${")
                            visit(segment)
                            append("}")
                        }
                    }
                    append("\"")
                }
                is ErrorNode -> append("/* ERROR: ${node.message} */")
            }
        }

        private fun append(text: String) {
            sb.append(text)
            lastWasNewline = false
        }

        private fun appendIndent() {
            if (lastWasNewline) {
                repeat(indentLevel * options.indentSize) { sb.append(" ") }
            }
        }

        private fun appendNewline() {
            sb.append("\n")
            lastWasNewline = true
        }
    }
}
