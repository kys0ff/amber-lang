package amber.compiler.backend.c

import amber.compiler.ast.BlockStatement
import amber.compiler.ast.BreakStatement
import amber.compiler.ast.ContinueStatement
import amber.compiler.ast.EnumDeclaration
import amber.compiler.ast.Expression
import amber.compiler.ast.ExpressionStatement
import amber.compiler.ast.ExtensionDeclaration
import amber.compiler.ast.ForStatement
import amber.compiler.ast.FunctionDeclaration
import amber.compiler.ast.IfStatement
import amber.compiler.ast.ImportStatement
import amber.compiler.ast.PanicExpression
import amber.compiler.ast.ReturnStatement
import amber.compiler.ast.Statement
import amber.compiler.ast.StructDeclaration
import amber.compiler.ast.VariableDeclaration
import amber.compiler.ast.WhileStatement
import amber.compiler.symbol.Symbol
import amber.compiler.type.Type

class StatementEmitter(
    private val writer: CodeWriter,
    private val expressionEmitter: ExpressionEmitter,
    private val symbolEmitter: SymbolEmitter,
    private val typeMapper: CTypeMapper,
    private val expressionTypes: Map<Expression, Type>,
    private val resolvedSymbols: Map<Expression, Symbol>
) {
    private val returnTypeStack = mutableListOf<Type>()

    fun emit(statement: Statement, declarationOnly: Boolean = false, isTopLevel: Boolean = false) {
        when (statement) {
            is BlockStatement -> {
                writer.writeLine("{")
                writer.indent()
                statement.statements.forEach { emit(it) }
                writer.dedent()
                writer.writeLine("}")
            }

            is VariableDeclaration -> {
                val symbol = resolvedSymbols[statement.name]
                val type = symbol?.type ?: expressionTypes[statement.initializer] ?: Type.Any
                val cType = typeMapper.map(type)
                val name = symbol?.let { symbolEmitter.mangle(it.name, it.namespace) }
                    ?: symbolEmitter.mangle(statement.name.name)

                if (declarationOnly) {
                    writer.writeLine("$cType ${name};")
                } else {
                    if (isTopLevel) {
                        if (statement.initializer != null) {
                            writer.write("$name = ")
                            expressionEmitter.emit(statement.initializer, symbol?.type)
                            writer.writeLine(";")
                        }
                    } else {
                        writer.write("$cType $name")
                        if (statement.initializer != null) {
                            writer.write(" = ")
                            expressionEmitter.emit(statement.initializer, symbol?.type)
                        }
                        writer.writeLine(";")
                    }
                }
            }

            is FunctionDeclaration -> {
                val symbol = resolvedSymbols[statement.name]
                val functionType = symbol?.type as? Type.Function
                val returnType = functionType?.returnType ?: Type.Unit
                val isExtension = symbol?.isExtension == true

                val cReturnType = typeMapper.map(returnType)
                val name = symbol?.let {
                    if (isExtension && functionType != null) {
                        symbolEmitter.mangleExtension(it.name, functionType.parameterTypes[0], it.namespace)
                    } else {
                        symbolEmitter.mangle(it.name, it.namespace)
                    }
                } ?: symbolEmitter.mangle(statement.name.name)
                writer.write("$cReturnType ${name}(")

                if (isExtension && functionType != null) {
                    val receiverType = functionType.parameterTypes[0]
                    val isMutated = functionType.isParameterMutated.getOrElse(0) { false }
                    writer.write("${typeMapper.mapParameter(receiverType, isMutated)} ${symbolEmitter.mangle("self")}")
                    if (statement.parameters.isNotEmpty()) writer.write(", ")
                }

                statement.parameters.forEachIndexed { index, param ->
                    val paramSymbol = resolvedSymbols[param.name]
                    val paramType = paramSymbol?.type ?: Type.Any
                    val funcType = symbol?.type as? Type.Function
                    val mutationIndex = if (isExtension) index + 1 else index
                    val isMutated =
                        funcType?.isParameterMutated?.getOrElse(mutationIndex) { false } ?: false
                    val paramName = paramSymbol?.let { symbolEmitter.mangle(it.name, it.namespace) }
                        ?: symbolEmitter.mangle(param.name.name)
                    writer.write("${typeMapper.mapParameter(paramType, isMutated)} $paramName")
                    if (index < statement.parameters.size - 1) writer.write(", ")
                }
                if (declarationOnly) {
                    writer.writeLine(");")
                } else {
                    writer.writeLine(") {")
                    writer.indent()
                    returnTypeStack.add(returnType)
                    statement.body?.statements?.forEachIndexed { index, bodyStmt ->
                        val isLast = index == statement.body.statements.size - 1
                        if (isLast && bodyStmt is ExpressionStatement && returnType != Type.Unit) {
                            emit(
                                ReturnStatement(
                                    bodyStmt.expression,
                                    bodyStmt.line,
                                    bodyStmt.column
                                )
                            )
                        } else {
                            emit(bodyStmt)
                        }
                    }
                    returnTypeStack.removeAt(returnTypeStack.size - 1)
                    writer.dedent()
                    writer.writeLine("}")
                }
            }

            is ExpressionStatement -> {
                val expr = statement.expression
                val currentReturnType = returnTypeStack.lastOrNull()

                if (expr is PanicExpression && !expr.isFatal && currentReturnType is Type.Unsafe) {
                    writer.write("return ")
                    expressionEmitter.emit(expr)
                } else {
                    expressionEmitter.emit(expr)
                }
                writer.writeLine(";")
            }

            is IfStatement -> {
                writer.write("if (")
                expressionEmitter.emit(statement.condition)
                writer.writeLine(") {")
                writer.indent()
                emit(statement.thenBranch)
                writer.dedent()
                writer.writeLine("}")
                if (statement.elseBranch != null) {
                    writer.writeLine("else {")
                    writer.indent()
                    emit(statement.elseBranch)
                    writer.dedent()
                    writer.writeLine("}")
                }
            }

            is WhileStatement -> visitWhileStatement(statement)
            is ForStatement -> visitForStatement(statement)
            is BreakStatement -> writer.writeLine("break;")
            is ContinueStatement -> writer.writeLine("continue;")
            is ReturnStatement -> visitReturnStatement(statement)
            is EnumDeclaration -> {
                val symbol = resolvedSymbols[statement.name]
                val enumType = symbol?.type as? Type.EnumNamespace
                val ns = enumType?.enumType?.moduleNamespace
                val enumName = statement.name.name

                val variantsVar = symbolEmitter.mangle("variants_$enumName", ns)
                writer.writeLine("const char* $variantsVar[] = { ${statement.variants.joinToString(", ") { "\"${it.name}\"" }} };")

                val toStringFunc = symbolEmitter.mangle("${enumName}_to_string", ns)
                writer.writeLine("static char* $toStringFunc(void* val) {")
                writer.indent()
                writer.writeLine("__amber_box_enum_t* e = (__amber_box_enum_t*)val;")
                writer.writeLine("if (e->value >= 0 && e->value < ${statement.variants.size}) return (char*)$variantsVar[e->value];")
                writer.writeLine("return \"unknown\";")
                writer.dedent()
                writer.writeLine("}")

                writer.writeLine("typedef enum {")
                writer.indent()
                statement.variants.forEachIndexed { index, variant ->
                    writer.writeLine(
                        "${
                            symbolEmitter.mangle(
                                "${enumName}_${variant.name}",
                                ns
                            )
                        }${if (index < statement.variants.size - 1) "," else ""}"
                    )
                }
                writer.dedent()
                writer.writeLine("} ${symbolEmitter.mangle(enumName, ns)};")
                writer.writeLine(
                    "__amber_type_t ${
                        symbolEmitter.mangle(
                            "type_$enumName",
                            ns
                        )
                    } = { \"$enumName\", ${100 + (ns ?: "").hashCode() + enumName.hashCode()}, $variantsVar, ${statement.variants.size}, $toStringFunc };"
                )
            }

            is StructDeclaration -> {
                val symbol = resolvedSymbols[statement.name]
                val structType = symbol?.type as? Type.Struct
                val ns = structType?.namespace
                val structName = statement.name.name
                val mangledName = symbolEmitter.mangleStruct(structName, ns)

                writer.writeLine("typedef struct {")
                writer.indent()
                structType?.fields?.values?.forEach { field ->
                    writer.writeLine("${typeMapper.map(field.type)} ${field.name};")
                }
                writer.dedent()
                writer.writeLine("} $mangledName;")

                val boxName = "${mangledName}_box"
                writer.writeLine("typedef struct {")
                writer.indent()
                writer.writeLine("__amber_header_t header;")
                writer.writeLine("$mangledName value;")
                writer.dedent()
                writer.writeLine("} $boxName;")

                val toStringFunc = symbolEmitter.mangle("${structName}_to_string", ns)
                writer.writeLine("static char* $toStringFunc(void* val) {")
                writer.indent()
                writer.writeLine("$boxName* b = ($boxName*)val;")
                writer.writeLine("char* res = \"$structName { \";")
                structType?.fields?.values?.forEachIndexed { index, field ->
                    writer.write("res = __amber_rt_str_concat(res, \"${field.name}: \");")
                    val boxFunc = when (field.type) {
                        Type.Number -> "__amber_rt_box_double"
                        Type.Boolean -> "__amber_rt_box_bool"
                        Type.String -> "__amber_rt_box_string"
                        is Type.Enum -> "__amber_rt_box_enum" // Simplified, needs type descriptor
                        is Type.Struct -> "__amber_rt_box_${
                            symbolEmitter.mangle(
                                field.type.name,
                                field.type.namespace
                            )
                        }"

                        else -> ""
                    }
                    if (boxFunc.isNotEmpty()) {
                        if (field.type is Type.Enum) {
                            writer.write(
                                "res = __amber_rt_str_concat(res, __amber_rt_to_string($boxFunc(b->value.${field.name}, &${
                                    symbolEmitter.mangle(
                                        "type_${field.type.name}",
                                        field.type.moduleNamespace
                                    )
                                })));"
                            )
                        } else {
                            writer.write("res = __amber_rt_str_concat(res, __amber_rt_to_string($boxFunc(b->value.${field.name})));")
                        }
                    } else {
                        writer.write("res = __amber_rt_str_concat(res, __amber_rt_to_string(b->value.${field.name}));")
                    }
                    if (index < structType.fields.size - 1) {
                        writer.write("res = __amber_rt_str_concat(res, \", \");")
                    }
                }
                writer.writeLine("res = __amber_rt_str_concat(res, \" }\");")
                writer.writeLine("return res;")
                writer.dedent()
                writer.writeLine("}")
                writer.writeLine()
                writer.writeLine(
                    "__amber_type_t ${
                        symbolEmitter.mangle(
                            "type_$structName",
                            ns
                        )
                    } = { \"$structName\", ${200 + (ns ?: "").hashCode() + structName.hashCode()}, NULL, 0, $toStringFunc };"
                )

                writer.writeLine("static inline void* __amber_rt_box_$mangledName($mangledName v) {")
                writer.indent()
                writer.writeLine("$boxName* p = ($boxName*)__amber_rt_alloc(sizeof($boxName));")
                writer.writeLine(
                    "p->header.type = &${
                        symbolEmitter.mangle(
                            "type_$structName",
                            ns
                        )
                    };"
                )
                writer.writeLine("p->value = v;")
                writer.writeLine("return p;")
                writer.dedent()
                writer.writeLine("}")

                writer.writeLine("static inline $mangledName __amber_rt_unbox_$mangledName(void* p) {")
                writer.indent()
                writer.writeLine("if (!p) { $mangledName v = {0}; return v; }")
                writer.writeLine("return (($boxName*)p)->value;")
                writer.dedent()
                writer.writeLine("}")
            }

            is ExtensionDeclaration -> {}
            is ImportStatement -> {}
        }
    }

    private fun visitWhileStatement(statement: WhileStatement) {
        writer.write("while (")
        expressionEmitter.emit(statement.condition)
        writer.writeLine(") {")
        writer.indent()
        emit(statement.body)
        writer.dedent()
        writer.writeLine("}")
    }

    private fun visitForStatement(statement: ForStatement) {
        val iterableType = expressionTypes[statement.iterable] ?: Type.Any
        val cIterableType = typeMapper.map(iterableType)
        val iterableVar = symbolEmitter.nextTemp()

        writer.writeLine("{")
        writer.indent()

        // Cache the iterable
        writer.write("$cIterableType $iterableVar = ")
        expressionEmitter.emit(statement.iterable)
        writer.writeLine(";")

        val indexVar = if (statement.indexName != null) {
            symbolEmitter.mangle(statement.indexName.name)
        } else {
            symbolEmitter.nextTemp()
        }

        val itemName = symbolEmitter.mangle(statement.itemName.name)
        val itemType = resolvedSymbols[statement.itemName]?.type ?: Type.Any
        val cItemType = typeMapper.map(itemType)

        if (iterableType == Type.String) {
            val lenVar = symbolEmitter.nextTemp()
            writer.writeLine("int $lenVar = (int)strlen($iterableVar);")
            writer.writeLine("for (int $indexVar = 0; $indexVar < $lenVar; $indexVar++) {")
            writer.indent()
            writer.writeLine("$cItemType $itemName = $iterableVar[$indexVar];")
        } else {
            // Assume it's a list
            writer.writeLine("if ($iterableVar) {")
            writer.indent()
            writer.writeLine("for (int $indexVar = 0; $indexVar < $iterableVar->length; $indexVar++) {")
            writer.indent()
            val boxFunc = when (itemType) {
                Type.Number -> "__amber_rt_unbox_double"
                Type.Boolean -> "__amber_rt_unbox_bool"
                Type.String -> "__amber_rt_unbox_string"
                is Type.Enum -> "__amber_rt_unbox_enum"
                is Type.Struct -> "__amber_rt_unbox_${symbolEmitter.mangleStruct(itemType.name, itemType.namespace)}"

                else -> ""
            }
            if (boxFunc.isNotEmpty()) {
                writer.writeLine("$cItemType $itemName = $boxFunc($iterableVar->data[$indexVar]);")
            } else {
                writer.writeLine("$cItemType $itemName = ($cItemType)$iterableVar->data[$indexVar];")
            }
        }

        emit(statement.body)

        writer.dedent()
        writer.writeLine("}")

        if (iterableType != Type.String) {
            writer.dedent()
            writer.writeLine("}")
        }

        writer.dedent()
        writer.writeLine("}")
    }

    private fun visitReturnStatement(statement: ReturnStatement) {
        val currentReturnType = returnTypeStack.lastOrNull()
        if (currentReturnType is Type.Unsafe) {
            val exprType = statement.value?.let { expressionTypes[it] }
            if (exprType is Type.Unsafe || exprType == Type.Nothing) {
                writer.write("return ")
                statement.value.let { expressionEmitter.emit(it) }
                writer.writeLine(";")
            } else {
                writer.write("return __amber_rt_result_success(")
                if (statement.value != null) {
                    expressionEmitter.emit(statement.value, Type.Any)
                } else {
                    writer.write("NULL")
                }
                writer.writeLine(");")
            }
        } else {
            writer.write("return")
            if (statement.value != null && currentReturnType != Type.Unit) {
                writer.write(" ")
                expressionEmitter.emit(statement.value, currentReturnType)
            }
            writer.writeLine(";")
        }
    }
}
