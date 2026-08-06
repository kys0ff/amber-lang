package amber.runtime

import amber.compiler.symbol.Symbol

interface RuntimeProvider {
    fun getBuiltInSymbols(): Map<String, Symbol>
    fun getAllIntrinsicSymbols(): Map<String, Symbol>
    fun getBuiltInNames(): Set<String>
    fun getPlatformName(symbol: Symbol): String?
    fun isRuntimeHelper(name: String): Boolean
    fun getRuntimeSource(prefix: String, usedNames: Set<String>): String
}
