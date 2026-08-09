package amber.compiler.doc

import amber.cli.ProjectConfig
import amber.util.Ansi
import amber.util.isDirectory
import amber.util.joinPaths
import amber.util.listFiles
import kotlin.math.min

/**
 * Resolves and pretty-prints documentation for a symbol query, searching both
 * the standard library and the current project.
 *
 * Query formats:
 *  - `symbol`                 — search everywhere for a top-level declaration
 *  - `module:symbol`          — search a specific module (`core`, `std`, `local`, `pkg`, or the project name)
 *  - `module:symbol.member`   — look up a member on a type
 *  - `module:symbol member`   — same, space-separated
 */
class DocReader(
    private val projectConfig: ProjectConfig?,
    private val projectRoot: String,
    private val stdlibPath: String,
    private val useColor: Boolean = true
) {
    private val docGenerator = DocGenerator()

    data class Query(
        val prefix: String?,
        val symbol: String,
        val member: String?
    )

    private data class Match(val item: DocGenerator.DocItem, val module: String)

    fun read(queryString: String) {
        val query = parseQuery(queryString)
        val items = findItems(query)

        if (items.isEmpty()) {
            println(style(Ansi::yellow, "No documentation found for '$queryString'"))
            printSuggestions(query)
            return
        }

        if (items.size > 1) {
            println(style(Ansi::dim, "Found ${items.size} matches for '$queryString':"))
            println()
        }

        items.forEachIndexed { index, match ->
            if (index > 0) println(style(Ansi::dim, "───"))
            printItem(match.item, match.module)
        }
    }

    // ---------------------------------------------------------------------
    // Query parsing
    // ---------------------------------------------------------------------

    private fun parseQuery(queryString: String): Query {
        // Formats:
        // 1. symbol
        // 2. module:symbol
        // 3. module:symbol.member
        // 4. module:symbol member

        val parts = queryString.split(Regex("[:. ]"), limit = 3)
        return when {
            queryString.contains(":") -> {
                val prefix = queryString.substringBefore(":")
                val rest = queryString.substringAfter(":")
                val symbolParts = rest.split(Regex("[. ]"), limit = 2)
                Query(prefix, symbolParts[0], symbolParts.getOrNull(1))
            }
            parts.size == 1 -> Query(null, parts[0], null)
            parts.size == 2 -> Query(null, parts[0], parts[1])
            else -> Query(null, parts[0], parts[1]) // fallback
        }
    }

    // ---------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------

    private fun findItems(query: Query): List<Match> {
        val results = mutableListOf<Match>()

        val searchInStd = query.prefix == null || query.prefix == "core" || query.prefix == "std"
        val searchInLocal = query.prefix == null || query.prefix == "local" || query.prefix == "pkg" ||
                query.prefix == projectConfig?.name

        if (searchInStd) {
            searchInDir(stdlibPath, "core", query, results)
        }

        if (searchInLocal && projectRoot.isNotEmpty()) {
            searchInDir(projectRoot, projectConfig?.name ?: "local", query, results)
        }

        return results
    }

    private fun searchInDir(dir: String, modulePrefix: String, query: Query, results: MutableList<Match>) {
        val files = mutableListOf<String>()
        collectAmbFiles(dir, files)

        files.forEach { filePath ->
            val fileName = filePath.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".amb")
            val currentModule = "$modulePrefix:$fileName"

            val items = docGenerator.getDocItems(filePath)
            items.forEach { item ->
                val matches = if (query.member != null) {
                    // Specific member lookup: symbol is the module/type, member is the symbol name
                    val matchesContext = fileName.equals(query.symbol, ignoreCase = true) ||
                            item.receiver?.equals(query.symbol, ignoreCase = true) == true ||
                            item.signature.contains(query.symbol, ignoreCase = true)
                    val matchesName = item.name.equals(query.member, ignoreCase = true)
                    matchesContext && matchesName
                } else {
                    // Global/Module lookup: symbol is the name of the thing
                    item.name.equals(query.symbol, ignoreCase = true) ||
                            fileName.equals(query.symbol, ignoreCase = true)
                }

                if (matches) {
                    results.add(Match(item, currentModule))
                }
            }
        }
    }

    private fun collectAmbFiles(dir: String, result: MutableList<String>) {
        val entries = listFiles(dir)
        for (name in entries) {
            val path = joinPaths(dir, name)
            if (isDirectory(path)) {
                if (name != ".build" && name != ".git") {
                    collectAmbFiles(path, result)
                }
            } else if (name.endsWith(".amb")) {
                result.add(path)
            }
        }
    }

    /** When a query has no exact match, suggest the closest-named declarations instead of failing silently. */
    private fun printSuggestions(query: Query) {
        val target = query.member ?: query.symbol
        val candidates = mutableListOf<Pair<String, Int>>()

        val dirs = listOfNotNull(
            stdlibPath.takeIf { it.isNotEmpty() },
            projectRoot.takeIf { it.isNotEmpty() }
        )

        dirs.forEach { dir ->
            val files = mutableListOf<String>()
            collectAmbFiles(dir, files)
            files.forEach { filePath ->
                docGenerator.getDocItems(filePath).forEach { item ->
                    val distance = editDistance(target.lowercase(), item.name.lowercase())
                    if (distance <= 2 && distance < target.length) {
                        candidates.add(item.name to distance)
                    }
                }
            }
        }

        val suggestions = candidates.distinctBy { it.first }.sortedBy { it.second }.take(3)
        if (suggestions.isNotEmpty()) {
            val names = suggestions.joinToString(", ") { style(Ansi::green, it.first) }
            println(style(Ansi::dim, "Did you mean: $names?"))
        }
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + min(dp[i - 1][j - 1], min(dp[i - 1][j], dp[i][j - 1]))
                }
            }
        }
        return dp[a.length][b.length]
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    private fun printItem(item: DocGenerator.DocItem, module: String) {
        val typeLabel = when (item.type) {
            "Intrinsic Function" -> style(Ansi::cyan, "intrinsic func")
            "Function" -> style(Ansi::cyan, "func")
            "Intrinsic Extension Function" -> style(Ansi::cyan, "intrinsic extension func")
            "Extension Function" -> style(Ansi::cyan, "extension func")
            "Struct" -> style(Ansi::cyan, "struct")
            "Enum" -> style(Ansi::cyan, "enum")
            "Variable" -> style(Ansi::cyan, "var")
            "Module" -> style(Ansi::cyan, "module")
            else -> style(Ansi::dim, item.type.lowercase())
        }

        val displayName = if (item.receiver != null) "${item.receiver}.${item.name}" else item.name
        val moduleLabel = if (item.module != null) "(${item.module}) " else ""
        println("${style(Ansi::dim, module)} $typeLabel $moduleLabel${boldYellow(displayName)}")
        printSignature(item.signature)
        println()

        if (item.description != null) {
            println(formatDocstring(item.description))
            println()
        }

        if (item.params.isNotEmpty()) {
            println(style(Ansi::bold, "Arguments:"))
            item.params.forEach { (name, desc) ->
                println("  ${style(Ansi::green, name)}: $desc")
            }
            println()
        }

        if (item.returns != null) {
            println(style(Ansi::bold, "Returns:"))
            println("  ${item.returns}")
            println()
        }

        if (item.panics.isNotEmpty()) {
            println(style(Ansi::bold, "Panics:"))
            item.panics.forEach { panic ->
                println("  $panic")
            }
            println()
        }

        if (item.description == null && item.params.isEmpty() && item.returns == null && item.panics.isEmpty()) {
            println(style(Ansi::dim, "  No documentation provided."))
            println()
        }
    }

    private fun boldYellow(text: String): String =
        if (useColor) Ansi.bold(Ansi.yellow(text)) else text

    private fun printSignature(sig: String) {
        // Simple syntax highlighting for signature
        val keywords = setOf("func", "intrinsic", "struct", "enum", "var", "val", "extend")
        val types = setOf("num", "string", "bool", "char", "any", "unit", "void")

        val parts = sig.split(Regex("(?<=[():, ])|(?=[():, ])"))
        print("  ")
        parts.forEach { part ->
            when {
                part.trim() in keywords -> print(style(Ansi::cyan, part))
                part.trim() in types -> print(style(Ansi::blue, part))
                part == ":" || part == "," -> print(style(Ansi::dim, part))
                part == "(" || part == ")" -> print(style(Ansi::yellow, part))
                else -> print(part)
            }
        }
        println()
    }

    private fun formatDocstring(doc: String): String {
        val lines = doc.lines()
        val sb = StringBuilder()
        val regex = Regex("`([^`]+)`")
        lines.forEach { line ->
            val formattedLine = if (useColor) {
                regex.replace(line) { matchResult -> Ansi.green(matchResult.groupValues[1]) }
            } else {
                regex.replace(line) { matchResult -> matchResult.groupValues[1] }
            }
            sb.append("  ").append(formattedLine).append("\n")
        }
        return sb.toString().trimEnd()
    }

    /** Applies an Ansi color/style function only when [useColor] is enabled; otherwise returns the plain text. */
    private fun style(colorFn: (String) -> String, text: String): String =
        if (useColor) colorFn(text) else text
}