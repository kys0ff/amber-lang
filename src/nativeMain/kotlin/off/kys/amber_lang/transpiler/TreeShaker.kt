package off.kys.amber_lang.transpiler

import off.kys.amber_lang.transpiler.ast.AstNode
import off.kys.amber_lang.transpiler.ast.FunctionDeclaration
import off.kys.amber_lang.transpiler.ast.IdentifierExpression
import off.kys.amber_lang.transpiler.ast.ImportStatement
import off.kys.amber_lang.transpiler.ast.MemberAccessExpression
import off.kys.amber_lang.transpiler.ast.Program
import off.kys.amber_lang.transpiler.ast.VariableDeclaration

class TreeShaker(private val importedModulePrograms: Map<String, Program>) {

    /**
     * Returns a map of Module Path -> Set of used top-level symbol names.
     */
    fun shake(mainProgram: Program): Map<String, Set<String>> {
        val usedSymbolsByModule = mutableMapOf<String, MutableSet<String>>()
        val aliasToPath = mutableMapOf<String, String>()

        // Initialize maps based on imports
        mainProgram.statements.filterIsInstance<ImportStatement>().forEach {
            val name = it.asName?.name ?: it.path.split(":").last().substringBeforeLast(".amb")
            aliasToPath[name] = it.path
            usedSymbolsByModule[it.path] = mutableSetOf()
        }

        // Phase 1: Direct references from main program (e.g., util.stub_const)
        fun findDirectRefs(node: AstNode) {
            if (node is MemberAccessExpression && node.target is IdentifierExpression) {
                val path = aliasToPath[node.target.name]
                if (path != null) {
                    usedSymbolsByModule[path]?.add(node.member.name)
                }
            }
            node.children.forEach { findDirectRefs(it) }
        }
        findDirectRefs(mainProgram)

        // Phase 2: Trace internal dependencies within each module recursively
        usedSymbolsByModule.forEach { (path, usedSet) ->
            val moduleAST = importedModulePrograms[path] ?: return@forEach
            val declarations = moduleAST.statements.filter { it is VariableDeclaration || it is FunctionDeclaration }

            var changed = true
            while (changed) {
                val initialSize = usedSet.size
                declarations.forEach { decl ->
                    val name = when (decl) {
                        is VariableDeclaration -> decl.name.name
                        is FunctionDeclaration -> decl.name.name
                        else -> ""
                    }

                    if (usedSet.contains(name)) {
                        fun trace(node: AstNode) {
                            if (node is IdentifierExpression) {
                                usedSet.add(node.name)
                            }
                            if (node is MemberAccessExpression && node.target is IdentifierExpression) {
                                val targetName = node.target.name
                                val path = aliasToPath[targetName]
                                if (path != null) {
                                    usedSymbolsByModule[path]?.add(node.member.name)
                                }
                            }
                            node.children.forEach { trace(it) }
                        }
                        decl.children.forEach { trace(it) }
                    }
                }
                changed = usedSet.size > initialSize
            }
        }

        return usedSymbolsByModule
    }
}