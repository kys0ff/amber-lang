package off.kys.amber_lang.transpiler.type

/**
 * manages scopes and symbol resolution during type checking and semantic analysis.
 */
class SymbolTable(
    val parent: SymbolTable? = null,
    initialSymbols: Map<String, Symbol>? = null
) {
    private val symbols = mutableMapOf<String, Symbol>()

    init {
        initialSymbols?.let { symbols.putAll(it) }
    }

    /**
     * adds a symbol to the current scope.
     * returns true if successful, false if the symbol is already defined locally.
     */
    fun define(symbol: Symbol): Boolean {
        if (symbols.containsKey(symbol.name)) {
            return false
        }
        symbols[symbol.name] = symbol
        return true
    }

    /**
     * looks up a symbol by name, traversing up the parent scopes if necessary.
     */
    fun resolve(name: String): Symbol? {
        val symbol = symbols[name] ?: parent?.resolve(name)
        symbol?.isUsed = true
        return symbol
    }

    /**
     * returns all symbols defined in the current scope (used for module exports).
     */
    fun getTopLevelSymbols(): List<Symbol> {
        return symbols.values.toList()
    }

    /**
     * returns all unused symbols in the current scope.
     */
    fun getUnusedSymbols(): List<Symbol> {
        return symbols.values.filter { !it.isUsed }
    }

    /**
     * creates a new nested scope with this table as the parent.
     */
    fun enterScope(): SymbolTable {
        return SymbolTable(this)
    }

    /**
     * returns the parent scope.
     */
    fun exitScope(): SymbolTable? {
        return parent
    }
}