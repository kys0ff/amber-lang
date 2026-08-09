package amber.compiler.backend.c

import amber.compiler.ast.BlockStatement
import amber.compiler.ast.BreakStatement
import amber.compiler.ast.ContinueStatement
import amber.compiler.ast.EnumDeclaration
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
import amber.compiler.type.Type

class StatementEmitter(
    private val context: CGenerationContext,
    private val expressionEmitter: ExpressionEmitter
) {
    private val returnTypeStack = mutableListOf<Type>()

    fun emit(
        statement: Statement,
        declarationOnly: Boolean = false,
        isTopLevel: Boolean = false
    ) {
        when (statement) {
            is VariableDeclaration -> visitVariableDeclaration(statement, declarationOnly, isTopLevel)
            is FunctionDeclaration -> visitFunctionDeclaration(statement, declarationOnly)
            is ExpressionStatement -> visitExpressionStatement(statement)
            is IfStatement -> visitIfStatement(statement)
            is WhileStatement -> visitWhileStatement(statement)
            is ForStatement -> visitForStatement(statement)
            is BreakStatement -> context.writer.writeLine("break;")
            is ContinueStatement -> context.writer.writeLine("continue;")
            is ReturnStatement -> visitReturnStatement(statement)
            is EnumDeclaration -> visitEnumDeclaration(statement)
            is StructDeclaration -> visitStructDeclaration(statement)
            is ExtensionDeclaration -> {}
            is BlockStatement -> visitBlockStatement(statement)
            is ImportStatement -> {}
        }
    }

    private fun visitVariableDeclaration(statement: VariableDeclaration, declarationOnly: Boolean, isTopLevel: Boolean) {
        if (statement.isIntrinsic) return
        val symbol = context.resolvedSymbols[statement.name]
        val type = context.expressionTypes[statement.initializer] ?: symbol?.type ?: Type.Any
        val name = symbol?.let { context.symbolEmitter.mangle(it.name, it.namespace) }
            ?: context.symbolEmitter.mangle(statement.name.name)

        if (declarationOnly) {
            context.writer.writeLine("extern ${context.typeMapper.map(type)} $name;")
        } else {
            if (isTopLevel) {
                context.writer.write("${context.typeMapper.map(type)} $name")
            } else {
                context.writer.write("${context.typeMapper.map(type)} $name")
            }
            if (statement.initializer != null) {
                context.writer.write(" = ")
                expressionEmitter.emit(statement.initializer, type)
            }
            context.writer.writeLine(";")
        }
    }

    private fun visitFunctionDeclaration(statement: FunctionDeclaration, declarationOnly: Boolean) {
        val symbol = context.resolvedSymbols[statement.name]
        val functionType = symbol?.type as? Type.Function
        val returnType = functionType?.returnType ?: Type.Unit
        val isExtension = symbol?.isExtension == true

        val cReturnType = context.typeMapper.map(returnType)
        val name = symbol?.let {
            if (isExtension && functionType != null) {
                context.symbolEmitter.mangleExtension(it.name, functionType.parameterTypes[0], it.namespace)
            } else {
                context.symbolEmitter.mangle(it.name, it.namespace)
            }
        } ?: context.symbolEmitter.mangle(statement.name.name)
        context.writer.write("$cReturnType ${name}(")

        if (isExtension && functionType != null) {
            val receiverType = functionType.parameterTypes[0]
            val isMutated = functionType.isParameterMutated.getOrElse(0) { false }
            context.writer.write("${context.typeMapper.mapParameter(receiverType, isMutated)} ${context.symbolEmitter.mangle("self")}")
            if (statement.parameters.isNotEmpty()) context.writer.write(", ")
        }

        statement.parameters.forEachIndexed { index, param ->
            val paramSymbol = context.resolvedSymbols[param.name]
            val paramType = paramSymbol?.type ?: Type.Any
            val funcType = symbol?.type as? Type.Function
            val mutationIndex = if (isExtension) index + 1 else index
            val isMutated = funcType?.isParameterMutated?.getOrElse(mutationIndex) { false } ?: false
            val paramName = paramSymbol?.let { context.symbolEmitter.mangle(it.name, it.namespace) }
                ?: context.symbolEmitter.mangle(param.name.name)
            context.writer.write("${context.typeMapper.mapParameter(paramType, isMutated)} $paramName")
            if (index < statement.parameters.size - 1) context.writer.write(", ")
        }

        if (declarationOnly) {
            context.writer.writeLine(");")
        } else {
            context.writer.writeLine(") {")
            context.writer.indent()
            returnTypeStack.add(returnType)
            statement.body?.statements?.forEachIndexed { index, bodyStmt ->
                val isLast = index == statement.body.statements.size - 1
                if (isLast && bodyStmt is ExpressionStatement && returnType != Type.Unit) {
                    visitReturnStatement(ReturnStatement(bodyStmt.expression, bodyStmt.line, bodyStmt.column))
                } else {
                    emit(bodyStmt)
                }
            }
            returnTypeStack.removeAt(returnTypeStack.size - 1)
            context.writer.dedent()
            context.writer.writeLine("}")
        }
    }

    private fun visitExpressionStatement(statement: ExpressionStatement) {
        val expr = statement.expression
        val currentReturnType = returnTypeStack.lastOrNull()

        if (expr is PanicExpression && !expr.isFatal && currentReturnType is Type.Unsafe) {
            context.writer.write("return ")
            expressionEmitter.emit(expr)
        } else {
            expressionEmitter.emit(expr)
        }
        context.writer.writeLine(";")
    }

    private fun visitIfStatement(statement: IfStatement) {
        context.writer.write("if (")
        expressionEmitter.emit(statement.condition)
        context.writer.writeLine(") {")
        context.writer.indent()
        emit(statement.thenBranch)
        context.writer.dedent()
        context.writer.writeLine("}")
        if (statement.elseBranch != null) {
            context.writer.writeLine("else {")
            context.writer.indent()
            emit(statement.elseBranch)
            context.writer.dedent()
            context.writer.writeLine("}")
        }
    }

    private fun visitWhileStatement(statement: WhileStatement) {
        context.writer.write("while (")
        expressionEmitter.emit(statement.condition)
        context.writer.writeLine(") {")
        context.writer.indent()
        emit(statement.body)
        context.writer.dedent()
        context.writer.writeLine("}")
    }

    private fun visitForStatement(statement: ForStatement) {
        val iterableType = context.expressionTypes[statement.iterable]
        val itemSymbol = context.resolvedSymbols[statement.itemName]
        val itemName = itemSymbol?.let { context.symbolEmitter.mangle(it.name, it.namespace) }
            ?: context.symbolEmitter.mangle(statement.itemName.name)
        val itemType = itemSymbol?.type ?: Type.Any

        if (iterableType is Type.List || iterableType is Type.ArrayList || iterableType == Type.Any) {
            val listVar = context.symbolEmitter.nextTemp()
            val indexVar = context.symbolEmitter.nextTemp()
            context.writer.writeLine("{")
            context.writer.indent()
            context.writer.write("${context.typeMapper.map(iterableType)} $listVar = ")
            expressionEmitter.emit(statement.iterable)
            context.writer.writeLine(";")
            context.writer.writeLine("for (int $indexVar = 0; $indexVar < __amber_rt_list_len($listVar); $indexVar++) {")
            context.writer.indent()

            val elementType = when (iterableType) {
                is Type.List -> iterableType.elementType
                is Type.ArrayList -> iterableType.elementType
                else -> Type.Any
            }

            context.writer.write("${context.typeMapper.map(itemType)} $itemName = ")
            val boxFunc = when (elementType) {
                Type.Number -> "__amber_rt_unbox_double"
                Type.Boolean -> "__amber_rt_unbox_bool"
                Type.String -> "__amber_rt_unbox_string"
                Type.Char -> "__amber_rt_unbox_char"
                is Type.Enum -> "__amber_rt_unbox_enum"
                is Type.Struct -> "__amber_rt_unbox_${context.symbolEmitter.mangleStruct(elementType.name, elementType.namespace)}"
                else -> ""
            }
            if (boxFunc.isNotEmpty()) {
                context.writer.write("$boxFunc(")
            }
            context.writer.write("__amber_rt_list_get($listVar, $indexVar)")
            if (boxFunc.isNotEmpty()) {
                context.writer.write(")")
            }
            context.writer.writeLine(";")
            emit(statement.body)
            context.writer.dedent()
            context.writer.writeLine("}")
            context.writer.dedent()
            context.writer.writeLine("}")
        } else if (iterableType == Type.String) {
            val strVar = context.symbolEmitter.nextTemp()
            val indexVar = context.symbolEmitter.nextTemp()
            context.writer.writeLine("{")
            context.writer.indent()
            context.writer.write("char* $strVar = ")
            expressionEmitter.emit(statement.iterable)
            context.writer.writeLine(";")
            context.writer.writeLine("for (int $indexVar = 0; $indexVar < strlen($strVar); $indexVar++) {")
            context.writer.indent()
            context.writer.writeLine("${context.typeMapper.map(itemType)} $itemName = $strVar[$indexVar];")
            emit(statement.body)
            context.writer.dedent()
            context.writer.writeLine("}")
            context.writer.dedent()
            context.writer.writeLine("}")
        }
    }

    private fun visitReturnStatement(statement: ReturnStatement) {
        context.writer.write("return ")
        if (statement.value != null) {
            val returnType = returnTypeStack.lastOrNull() ?: Type.Any
            expressionEmitter.emit(statement.value, returnType)
        }
        context.writer.writeLine(";")
    }

    private fun visitEnumDeclaration(statement: EnumDeclaration) {
        val symbol = context.resolvedSymbols[statement.name]
        val enumType = symbol?.type as? Type.EnumNamespace
        val ns = enumType?.enumType?.moduleNamespace
        val enumName = statement.name.name

        val variantsVar = context.symbolEmitter.mangle("variants_$enumName", ns)
        context.writer.writeLine("const char* $variantsVar[] = { ${statement.variants.joinToString(", ") { "\"${it.name}\"" }} };")

        val toStringFunc = context.symbolEmitter.mangle("${enumName}_to_string", ns)
        context.writer.writeLine("static char* $toStringFunc(void* val) {")
        context.writer.indent()
        context.writer.writeLine("__amber_box_enum_t* e = (__amber_box_enum_t*)val;")
        context.writer.writeLine("if (e->value >= 0 && e->value < ${statement.variants.size}) return (char*)$variantsVar[e->value];")
        context.writer.writeLine("return \"unknown\";")
        context.writer.dedent()
        context.writer.writeLine("}")

        context.writer.writeLine("typedef enum {")
        context.writer.indent()
        statement.variants.forEachIndexed { index, variant ->
            context.writer.writeLine(
                "${context.symbolEmitter.mangle("${enumName}_${variant.name}", ns)}${if (index < statement.variants.size - 1) "," else ""}"
            )
        }
        context.writer.dedent()
        context.writer.writeLine("} ${context.symbolEmitter.mangle(enumName, ns)};")
        context.writer.writeLine(
            "__amber_type_t ${context.symbolEmitter.mangle("type_$enumName", ns)} = { \"$enumName\", ${100 + (ns ?: "").hashCode() + enumName.hashCode()}, $variantsVar, ${statement.variants.size}, $toStringFunc };"
        )
    }

    private fun visitStructDeclaration(statement: StructDeclaration) {
        val symbol = context.resolvedSymbols[statement.name]
        val structType = symbol?.type as? Type.Struct
        val ns = structType?.namespace
        val structName = statement.name.name
        val mangledName = context.symbolEmitter.mangleStruct(structName, ns)

        context.writer.writeLine("typedef struct {")
        context.writer.indent()
        structType?.fields?.values?.forEach { field ->
            context.writer.writeLine("${context.typeMapper.map(field.type)} ${field.name};")
        }
        context.writer.dedent()
        context.writer.writeLine("} $mangledName;")

        val boxName = "${mangledName}_box"
        context.writer.writeLine("typedef struct {")
        context.writer.indent()
        context.writer.writeLine("__amber_header_t header;")
        context.writer.writeLine("$mangledName value;")
        context.writer.dedent()
        context.writer.writeLine("} $boxName;")

        val toStringFunc = context.symbolEmitter.mangle("${structName}_to_string", ns)
        context.writer.writeLine("static char* $toStringFunc(void* val) {")
        context.writer.indent()
        context.writer.writeLine("$boxName* b = ($boxName*)val;")
        context.writer.writeLine("char* res = \"$structName { \";")
        structType?.fields?.values?.forEachIndexed { index, field ->
            context.writer.write("res = __amber_rt_str_concat(res, \"${field.name}: \");")
            val boxFunc = when (field.type) {
                Type.Number -> "__amber_rt_box_double"
                Type.Boolean -> "__amber_rt_box_bool"
                Type.String -> "__amber_rt_box_string"
                is Type.Enum -> "__amber_rt_box_enum"
                is Type.Struct -> "__amber_rt_box_${context.symbolEmitter.mangleStruct(field.type.name, field.type.namespace)}"
                else -> ""
            }
            if (boxFunc.isNotEmpty()) {
                if (field.type is Type.Enum) {
                    context.writer.write(
                        "res = __amber_rt_str_concat(res, __amber_rt_to_string($boxFunc(b->value.${field.name}, &${context.symbolEmitter.mangle("type_${field.type.name}", field.type.moduleNamespace)})));"
                    )
                } else {
                    context.writer.write("res = __amber_rt_str_concat(res, __amber_rt_to_string($boxFunc(b->value.${field.name})));")
                }
            } else {
                context.writer.write("res = __amber_rt_str_concat(res, __amber_rt_to_string(b->value.${field.name}));")
            }
            if (index < structType.fields.size - 1) {
                context.writer.write("res = __amber_rt_str_concat(res, \", \");")
            }
        }
        context.writer.writeLine("res = __amber_rt_str_concat(res, \" }\");")
        context.writer.writeLine("return res;")
        context.writer.dedent()
        context.writer.writeLine("}")

        context.writer.writeLine("__amber_type_t ${context.symbolEmitter.mangle("type_$structName", ns)} = { \"$structName\", ${100 + (ns ?: "").hashCode() + structName.hashCode()}, NULL, 0, $toStringFunc };")

        context.writer.writeLine("static inline void* __amber_rt_box_$mangledName($mangledName v) {")
        context.writer.indent()
        context.writer.writeLine("$boxName* p = ($boxName*)__amber_rt_alloc(sizeof($boxName));")
        context.writer.writeLine("p->header.type = &${context.symbolEmitter.mangle("type_$structName", ns)};")
        context.writer.writeLine("p->value = v;")
        context.writer.writeLine("return p;")
        context.writer.dedent()
        context.writer.writeLine("}")

        context.writer.writeLine("static inline $mangledName __amber_rt_unbox_$mangledName(void* p) {")
        context.writer.indent()
        context.writer.writeLine("if (!p) { $mangledName v = {0}; return v; }")
        context.writer.writeLine("return (($boxName*)p)->value;")
        context.writer.dedent()
        context.writer.writeLine("}")
    }

    private fun visitBlockStatement(statement: BlockStatement) {
        context.writer.writeLine("{")
        context.writer.indent()
        statement.statements.forEach { emit(it) }
        context.writer.dedent()
        context.writer.writeLine("}")
    }
}
