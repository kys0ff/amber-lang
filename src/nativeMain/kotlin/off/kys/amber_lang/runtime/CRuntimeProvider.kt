package off.kys.amber_lang.runtime

import off.kys.amber_lang.transpiler.type.Symbol

class CRuntimeProvider : RuntimeProvider {
    override fun getBuiltInSymbols(): Map<String, Symbol> {
        // No more direct globals like echo or str_concat
        return emptyMap()
    }

    override fun getAllIntrinsicSymbols(): Map<String, Symbol> = StandardLibrary.getAllSymbols()

    override fun getBuiltInNames(): Set<String> = StandardLibrary.intrinsics.values.map { it.cName }.toSet()

    override fun getPlatformName(symbol: Symbol): String? {
        // First try matching by qualified name
        val intrinsic = StandardLibrary.getIntrinsic(symbol.qualifiedName)
        if (intrinsic != null) return intrinsic.cName
        
        // Fallback for non-namespaced symbols if they match an intrinsic
        if (symbol.namespace == null) {
            val match = StandardLibrary.intrinsics.values.find { it.name == symbol.name }
            if (match != null) return match.cName
        }
        
        return null
    }

    override fun isRuntimeHelper(name: String): Boolean = false

    override fun getRuntimeSource(prefix: String, usedNames: Set<String>): String = ""
}
