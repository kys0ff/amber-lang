package amber.compiler.doc

import amber.compiler.lexer.Lexer
import amber.compiler.lexer.Token
import amber.util.readFile

/**
 * Generates human-friendly Markdown documentation from Amber source files.
 *
 * The generator performs a single lexical pass over the source, collecting
 * `##`-style doc comments and attaching them to the declaration that follows
 * (functions, intrinsics, structs, enums, and top-level vals/vars). The
 * resulting [DocItem]s are then rendered into a navigable Markdown document
 * with a table of contents, grouped sections, and parameter/return tables.
 */
class DocGenerator {

    data class DocItem(
        val type: String,
        val name: String,
        val signature: String,
        val docstring: String?,
        val module: String? = null,
        val description: String? = null,
        val params: List<Pair<String, String>> = emptyList(),
        val returns: String? = null,
        val receiver: String? = null,
        val panics: List<String> = emptyList(),
    )

    /** Order in which item categories are rendered, and the emoji used as a section marker. */
    private val typeOrder = listOf(
        "Intrinsic Function" to "⚙️",
        "Function" to "🔧",
        "Intrinsic Extension Function" to "🔌⚙️",
        "Extension Function" to "🔌🔧",
        "Struct" to "📦",
        "Enum" to "🔢",
        "Variable" to "📎",
    )

    fun generate(entryPath: String): String {
        val items = getDocItems(entryPath)
        return formatMarkdown(items, entryPath)
    }

    fun getDocItems(entryPath: String): List<DocItem> {
        val source = readFile(entryPath)
        val lexer = Lexer(source)
        val tokens = lexer.tokenize(keepTrivia = true)

        val items = mutableListOf<DocItem>()
        var currentDoc: StringBuilder? = null
        var braceDepth = 0
        var currentReceiver: String? = null
        var extendBraceDepth = -1

        var i = 0
        while (i < tokens.size) {
            when (val token = tokens[i]) {
                is Token.Comment -> {
                    val value = token.value.trim()
                    if (value.startsWith("///")) {
                        if (currentDoc == null) currentDoc = StringBuilder()
                        val content = value.removePrefix("///").trim()
                        if (content.isNotEmpty() || currentDoc.isNotEmpty()) {
                            currentDoc.append(content).append("\n")
                        }
                    } else if (value.startsWith("//") && (currentDoc != null)) {
                        currentDoc = null
                    }
                }
                is Token.Keyword -> {
                    // Check for module header before processing keywords
                    val doc = currentDoc?.toString()?.trim()
                    if (doc != null && doc.contains("@module") && (braceDepth == 0)) {
                        val parsed = parseDocstring(doc)
                        items.add(
                            DocItem(
                                "Module", parsed.module ?: "Unknown", "", doc,
                                module = parsed.module,
                                description = parsed.description
                            )
                        )
                        currentDoc = null
                    }

                    if (braceDepth == 0 || isInsideExtend(tokens, i)) {
                        when (token.value) {
                            "intrinsic" -> {
                                val doc = currentDoc?.toString()?.trim()
                                currentDoc = null

                                var j = nextSignificant(tokens, i + 1)
                                if (tokens.getOrNull(j)?.let { it is Token.Keyword && it.value == "func" } == true) {
                                    j = nextSignificant(tokens, j + 1)
                                }

                                val nameToken = tokens.getOrNull(j)
                                if (nameToken is Token.Identifier) {
                                    val signature = extractSignature(tokens, i)
                                    val parsed = parseDocstring(doc)
                                    val type = if (currentReceiver != null) "Intrinsic Extension Function" else "Intrinsic Function"
                                    items.add(
                                        DocItem(
                                            type, nameToken.value, signature, doc,
                                            module = parsed.module,
                                            description = parsed.description, params = parsed.params, returns = parsed.returns,
                                            receiver = currentReceiver, panics = parsed.panics
                                        )
                                    )
                                }
                            }
                            "func" -> {
                                val prevIdx = lastSignificant(tokens, i - 1)
                                val prev = tokens.getOrNull(prevIdx)
                                if (prev is Token.Keyword && prev.value == "intrinsic") {
                                    // Already handled above as an Intrinsic Function.
                                } else {
                                    val doc = currentDoc?.toString()?.trim()
                                    currentDoc = null

                                    val j = nextSignificant(tokens, i + 1)
                                    val nameToken = tokens.getOrNull(j)
                                    if (nameToken is Token.Identifier) {
                                        val signature = extractSignature(tokens, i)
                                        val parsed = parseDocstring(doc)
                                        val type = if (currentReceiver != null) "Extension Function" else "Function"
                                        items.add(
                                            DocItem(
                                                type, nameToken.value, signature, doc,
                                                module = parsed.module,
                                                description = parsed.description, params = parsed.params, returns = parsed.returns,
                                                receiver = currentReceiver, panics = parsed.panics
                                            )
                                        )
                                    }
                                }
                            }
                            "struct" -> {
                                val doc = currentDoc?.toString()?.trim()
                                currentDoc = null
                                val j = nextSignificant(tokens, i + 1)
                                val nameToken = tokens.getOrNull(j)
                                if (nameToken is Token.Identifier) {
                                    val parsed = parseDocstring(doc)
                                    items.add(
                                        DocItem(
                                            "Struct", nameToken.value, "struct ${nameToken.value}", doc,
                                            module = parsed.module,
                                            description = parsed.description,
                                            panics = parsed.panics
                                        )
                                    )
                                }
                            }
                            "enum" -> {
                                val doc = currentDoc?.toString()?.trim()
                                currentDoc = null
                                val j = nextSignificant(tokens, i + 1)
                                val nameToken = tokens.getOrNull(j)
                                if (nameToken is Token.Identifier) {
                                    val parsed = parseDocstring(doc)
                                    items.add(
                                        DocItem(
                                            "Enum", nameToken.value, "enum ${nameToken.value}", doc,
                                            module = parsed.module,
                                            description = parsed.description,
                                            panics = parsed.panics
                                        )
                                    )
                                }
                            }
                            "val", "var" -> {
                                if (braceDepth == 0) {
                                    val doc = currentDoc?.toString()?.trim()
                                    currentDoc = null
                                    val j = nextSignificant(tokens, i + 1)
                                    val nameToken = tokens.getOrNull(j)
                                    if (nameToken is Token.Identifier) {
                                        val parsed = parseDocstring(doc)
                                        items.add(
                                            DocItem(
                                                "Variable", nameToken.value, "${token.value} ${nameToken.value}", doc,
                                                module = parsed.module,
                                                description = parsed.description,
                                                panics = parsed.panics
                                            )
                                        )
                                    }
                                }
                            }
                            "extend" -> {
                                val j = nextSignificant(tokens, i + 1)
                                val receiverToken = tokens.getOrNull(j)
                                if (receiverToken is Token.Identifier) {
                                    currentReceiver = receiverToken.value
                                    extendBraceDepth = braceDepth
                                }
                            }
                            else -> {
                                currentDoc = null
                            }
                        }
                    } else {
                        currentDoc = null
                    }
                }
                is Token.Separator -> {
                    // Check for module header
                    val doc = currentDoc?.toString()?.trim()
                    if (doc != null && doc.contains("@module") && (braceDepth == 0)) {
                        val parsed = parseDocstring(doc)
                        items.add(
                            DocItem(
                                "Module", parsed.module ?: "Unknown", "", doc,
                                module = parsed.module,
                                description = parsed.description
                            )
                        )
                        currentDoc = null
                    }

                    if (token.value == "{") braceDepth++
                    if (token.value == "}") {
                        braceDepth--
                        if (braceDepth == extendBraceDepth) {
                            currentReceiver = null
                            extendBraceDepth = -1
                        }
                    }
                    currentDoc = null
                }
                is Token.Whitespace, is Token.Newline -> {
                    // ignore
                }
                else -> {
                    currentDoc = null
                }
            }
            i++
        }

        return items
    }

    private data class ParsedDoc(
        val description: String?,
        val params: List<Pair<String, String>>,
        val returns: String?,
        val module: String? = null,
        val panics: List<String> = emptyList()
    )

    private fun parseDocstring(doc: String?): ParsedDoc {
        if (doc == null) return ParsedDoc(null, emptyList(), null)

        val lines = doc.lines()
        val description = mutableListOf<String>()
        val params = mutableListOf<Pair<String, String>>()
        var returns: String? = null
        var module: String? = null
        val panics = mutableListOf<String>()

        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("@param") -> {
                    val rest = trimmed.removePrefix("@param").trim()
                    val parts = rest.split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2) {
                        params.add(parts[0] to parts[1])
                    } else if (parts.size == 1) {
                        params.add(parts[0] to "")
                    }
                }
                trimmed.startsWith("@return") -> {
                    returns = trimmed.removePrefix("@return").trim()
                }
                trimmed.startsWith("@module") -> {
                    module = trimmed.removePrefix("@module").trim()
                }
                trimmed.startsWith("@panic") -> {
                    panics.add(trimmed.removePrefix("@panic").trim())
                }
                else -> {
                    if (params.isEmpty() && returns == null && module == null && panics.isEmpty()) {
                        description.add(line)
                    }
                }
            }
        }

        return ParsedDoc(
            description = description.joinToString("\n").trim().ifEmpty { null },
            params = params,
            returns = returns,
            module = module,
            panics = panics
        )
    }

    private fun isInsideExtend(tokens: List<Token>, index: Int): Boolean {
        var depth = 0
        var j = index
        while (j >= 0) {
            val t = tokens[j]
            if (t is Token.Separator) {
                if (t.value == "}") depth++
                if (t.value == "{") {
                    if (depth == 0) {
                        var k = j - 1
                        while (k >= 0) {
                            val prev = tokens[k]
                            if (prev is Token.Keyword && prev.value == "extend") return true
                            if (prev is Token.Separator && (prev.value == "}" || prev.value == ";")) break
                            if (prev is Token.Keyword && (prev.value == "func" || prev.value == "struct")) break
                            k--
                        }
                        return false
                    }
                    depth--
                }
            }
            j--
        }
        return false
    }

    private fun nextSignificant(tokens: List<Token>, startIndex: Int): Int {
        var j = startIndex
        while (j < tokens.size) {
            val t = tokens[j]
            if (t !is Token.Whitespace && t !is Token.Newline) return j
            j++
        }
        return j
    }

    private fun lastSignificant(tokens: List<Token>, startIndex: Int): Int {
        var j = startIndex
        while (j >= 0) {
            val t = tokens[j]
            if (t !is Token.Whitespace && t !is Token.Newline) return j
            j--
        }
        return -1
    }

    private fun extractSignature(tokens: List<Token>, startIndex: Int): String {
        val sb = StringBuilder()
        var j = startIndex
        while (j < tokens.size) {
            val t = tokens[j]
            if (t is Token.Separator && t.value == "{") break
            if (t is Token.Newline) break
            if (t is Token.Comment) break

            if (t is Token.Identifier || t is Token.Keyword || t is Token.Separator || t is Token.Operator ||
                t is Token.StringLiteral || t is Token.NumberLiteral || t is Token.BooleanLiteral ||
                t is Token.CharLiteral || t is Token.NullLiteral
            ) {
                val value = when (t) {
                    is Token.Identifier -> t.value
                    is Token.Keyword -> t.value
                    is Token.Separator -> t.value
                    is Token.Operator -> t.value
                    is Token.StringLiteral -> "\"${t.value}\""
                    is Token.NumberLiteral -> t.value
                    is Token.BooleanLiteral -> t.value
                    is Token.CharLiteral -> "'${t.value}'"
                    is Token.NullLiteral -> "null"
                }
                if (value.isNotEmpty()) {
                    if (sb.isNotEmpty() && value != "(" && value != ")" && value != "[" && value != "]" &&
                        value != "," && value != ":" && sb.last() != '(' && sb.last() != '[' && sb.last() != ' '
                    ) {
                        sb.append(" ")
                    }
                    sb.append(value)
                }
            }
            j++
        }
        return sb.toString().trim()
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    private fun formatMarkdown(items: List<DocItem>, filePath: String): String {
        val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
        val sb = StringBuilder()

        val moduleItem = items.find { it.type == "Module" }
        if (moduleItem != null) {
            sb.append("# 📘 ${moduleItem.module ?: moduleItem.name}\n\n")
            if (moduleItem.description != null) {
                sb.append(moduleItem.description).append("\n\n")
            }
        } else {
            sb.append("# 📘 $fileName\n\n")
        }

        if (items.isEmpty() || (items.size == 1 && moduleItem != null)) {
            if (items.isEmpty()) {
                sb.append("_No documented items found in this file._\n\n")
                sb.append("> Add a `///` doc comment above a `func`, `intrinsic`, `struct`, `enum`, or top-level `val`/`var` to document it.\n")
            }
            return sb.toString()
        }

        val documented = items.count { (it.description != null || it.docstring != null) && it.type != "Module" }
        val declarationCount = items.count { it.type != "Module" }
        sb.append("**$declarationCount** declaration${if (declarationCount == 1) "" else "s"} found · ")
            .append("**$documented** documented\n\n")

        val grouped = items.groupBy { it.type }

        // ---- Table of contents -------------------------------------------------
        sb.append("<a name=\"contents\"></a>\n")
        sb.append("## Contents\n\n")
        typeOrder.forEach { (type, emoji) ->
            val group = grouped[type] ?: return@forEach
            sb.append("- $emoji [**$type**](#${sectionAnchor(type)}) (${group.size})\n")

            if (type.contains("Extension")) {
                val byReceiver = group.groupBy { it.receiver }
                byReceiver.keys.filterNotNull().sortedBy { it.lowercase() }.forEach { receiver ->
                    val receiverItems = byReceiver[receiver] ?: return@forEach
                    sb.append("  - [**$receiver**](#${receiverAnchor(receiver)})\n")
                    receiverItems.sortedBy { it.name.lowercase() }.forEach { item ->
                        sb.append("    - [`${item.name}`](#${anchor(item)})\n")
                    }
                }
            } else {
                group.sortedBy { it.name.lowercase() }.forEach { item ->
                    sb.append("  - [`${item.name}`](#${anchor(item)})\n")
                }
            }
        }
        sb.append("\n---\n\n")

        // ---- Sections ------------------------------------------------------------
        typeOrder.forEach { (type, emoji) ->
            val group = grouped[type]?.sortedBy { it.name.lowercase() } ?: return@forEach

            sb.append("<a name=\"${sectionAnchor(type)}\"></a>\n")
            sb.append("## $emoji $type${if (group.size > 1) "s" else ""}\n\n")

            if (type.contains("Extension")) {
                val byReceiver = group.groupBy { it.receiver }
                byReceiver.keys.filterNotNull().sortedBy { it.lowercase() }.forEach { receiver ->
                    sb.append("<a name=\"${receiverAnchor(receiver)}\"></a>\n")
                    sb.append("### Extensions for `$receiver`\n\n")
                    val receiverItems = byReceiver[receiver] ?: return@forEach
                    receiverItems.sortedBy { it.name.lowercase() }.forEach { item ->
                        renderItem(sb, item, headerLevel = 4)
                    }
                }
            } else {
                group.forEach { item -> renderItem(sb, item) }
            }
        }

        return sb.toString()
    }

    private fun renderItem(sb: StringBuilder, item: DocItem, headerLevel: Int = 3) {
        val displayName = if (item.receiver != null) "${item.receiver}.${item.name}" else item.name
        val prefix = "#".repeat(headerLevel)
        sb.append("<a name=\"${anchor(item)}\"></a>\n")
        sb.append("$prefix $displayName\n\n")
        
        if (item.module != null) {
            sb.append("**Module:** `${item.module}`\n\n")
        }

        sb.append("```amber\n${item.signature}\n```\n\n")

        val description = item.description ?: item.docstring
        if (!description.isNullOrBlank()) {
            sb.append(description.trim()).append("\n\n")
        } else {
            sb.append("_No description provided._\n\n")
        }

        if (item.params.isNotEmpty()) {
            sb.append("**Parameters**\n\n")
            sb.append("| Name | Description |\n")
            sb.append("|------|-------------|\n")
            item.params.forEach { (name, desc) ->
                sb.append("| `$name` | ${desc.ifBlank { "—" }} |\n")
            }
            sb.append("\n")
        }

        item.returns?.let { returns ->
            sb.append("**Returns:** $returns\n\n")
        }

        if (item.panics.isNotEmpty()) {
            sb.append("**Panics**\n\n")
            item.panics.forEach { panic ->
                sb.append("- $panic\n")
            }
            sb.append("\n")
        }

        sb.append("[↑ back to top](#contents)\n\n")
        sb.append("---\n\n")
    }

    /** Builds a GitHub-flavored Markdown anchor id for a doc item's heading. */
    private fun anchor(item: DocItem): String {
        val base = if (item.receiver != null) "${item.receiver}-${item.name}" else item.name
        return base.lowercase().replace("_", "-").replace(Regex("[^a-z0-9-]+"), "-").trim('-')
    }

    private fun sectionAnchor(type: String): String {
        return type.lowercase().replace(" ", "-").replace(Regex("[^a-z0-9-]+"), "-")
    }

    private fun receiverAnchor(receiver: String): String {
        return "extensions-for-${receiver.lowercase().replace(Regex("[^a-z0-9-]+"), "-")}"
    }
}
