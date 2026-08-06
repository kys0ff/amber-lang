package off.kys.amber_lang.transpiler.backend

import off.kys.amber_lang.transpiler.ast.Program

interface Backend {
    fun generate(program: Program): String
}
