package off.kys.amber_lang.transpiler.codegen

/**
 * Runtime value tags used to encode Amber's dynamic values as tagged Bash strings
 * (`"tag:payload"`). Centralized here instead of scattered string literals so the
 * encoding can never drift out of sync between the code that writes a tag and the
 * code that reads it back.
 */
object ValueTag {
    const val NUM = "num"
    const val STR = "str"
    const val BOOL = "bool"
    const val CHAR = "char"
    const val UNIT = "unit"
    const val ARR = "arr"
    const val ERR = "err"
}