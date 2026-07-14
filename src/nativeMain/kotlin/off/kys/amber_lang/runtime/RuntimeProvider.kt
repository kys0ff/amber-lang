package off.kys.amber_lang.runtime

import off.kys.amber_lang.transpiler.type.Symbol

interface RuntimeProvider {
    /**
     * Returns a map of built-in function names to their symbols.
     */
    fun getBuiltInSymbols(): Map<String, Symbol>

    /**
     * Returns a map of all intrinsic function names to their symbols,
     * including those not in the default global scope.
     */
    fun getAllIntrinsicSymbols(): Map<String, Symbol>

    /**
     * Returns the list of built-in function names that should not be mangled.
     */
    fun getBuiltInNames(): Set<String>

    /**
     * Returns the platform-specific name for a built-in function.
     * Returns null if the function is not a built-in or doesn't have a special platform name.
     */
    fun getPlatformName(name: String): String?

    /**
     * Returns true if the name refers to a runtime helper function (to be mangled with prefix).
     */
    fun isRuntimeHelper(name: String): Boolean

    /**
     * Returns the runtime source code (e.g., standard library functions in the target language).
     * @param prefix The prefix to use for runtime helper functions.
     * @param usedNames The names of the built-in functions that were actually used.
     */
    fun getRuntimeSource(prefix: String, usedNames: Set<String>): String
}
