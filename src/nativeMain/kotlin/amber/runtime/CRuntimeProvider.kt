package amber.runtime

import amber.compiler.symbol.Symbol

class CRuntimeProvider : RuntimeProvider {
    override fun getBuiltInSymbols(): Map<String, Symbol> {
        return emptyMap()
    }

    override fun getAllIntrinsicSymbols(): Map<String, Symbol> = StandardLibrary.getAllSymbols()

    override fun getBuiltInNames(): Set<String> = StandardLibrary.intrinsics.values.map { it.cName }.toSet()

    override fun getPlatformName(symbol: Symbol): String? {
        val intrinsic = StandardLibrary.getIntrinsic(symbol.qualifiedName)
        if (intrinsic != null) return intrinsic.cName
        
        if (symbol.namespace == null) {
            val match = StandardLibrary.intrinsics.values.find { it.name == symbol.name }
            if (match != null) return match.cName
        }
        
        return null
    }

    override fun isRuntimeHelper(name: String): Boolean = false

    override fun getRuntimeSource(prefix: String, usedNames: Set<String>): String = ""
}
