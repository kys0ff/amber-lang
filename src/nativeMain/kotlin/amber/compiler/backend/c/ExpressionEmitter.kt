package amber.compiler.backend.c

import amber.compiler.ast.ArrayLiteralExpression
import amber.compiler.ast.AssignmentExpression
import amber.compiler.ast.BinaryExpression
import amber.compiler.ast.CallExpression
import amber.compiler.ast.CatchExpression
import amber.compiler.ast.Expression
import amber.compiler.ast.ExpressionStatement
import amber.compiler.ast.IdentifierExpression
import amber.compiler.ast.IndexAccessExpression
import amber.compiler.ast.IsExpression
import amber.compiler.ast.LiteralExpression
import amber.compiler.ast.MemberAccessExpression
import amber.compiler.ast.NamedArgumentExpression
import amber.compiler.ast.PanicExpression
import amber.compiler.ast.StringTemplateExpression
import amber.compiler.ast.UnaryExpression
import amber.compiler.ast.VariableDeclaration
import amber.compiler.symbol.Symbol
import amber.compiler.type.Type

class ExpressionEmitter(private val context: CGenerationContext) {
    
    var statementEmitter: StatementEmitter? = null

    fun emit(expression: Expression, expectedType: Type? = null) {
        val actualType = context.expressionTypes[expression]
        
        val needsAnyBoxing = expectedType == Type.Any && actualType != Type.Any && actualType != null
        val needsUnsafeWrapping = expectedType is Type.Unsafe && actualType !is Type.Unsafe && actualType != Type.Nothing && actualType != Type.Error && actualType != null
        
        val boxFunc = if (needsAnyBoxing || needsUnsafeWrapping) {
            when (actualType) {
                Type.Number -> "__amber_rt_box_double"
                Type.Boolean -> "__amber_rt_box_bool"
                Type.String -> "__amber_rt_box_string"
                Type.Char -> "__amber_rt_box_char"
                is Type.Enum -> "__amber_rt_box_enum"
                is Type.Struct -> "__amber_rt_box_${context.symbolEmitter.mangleStruct(actualType.name, actualType.namespace)}"
                else -> ""
            }
        } else ""

        if (needsUnsafeWrapping) {
            context.writer.write("__amber_rt_result_success(")
        }

        if (boxFunc.isNotEmpty()) {
            context.writer.write(boxFunc)
            context.writer.write("(")
        }

        emitRaw(expression)

        if (boxFunc.isNotEmpty()) {
            if (actualType is Type.Enum) {
                context.writer.write(", &${context.symbolEmitter.mangle("type_${actualType.name}", actualType.moduleNamespace)}")
            }
            context.writer.write(")")
        }

        if (needsUnsafeWrapping) {
            context.writer.write(")")
        }
    }

    private fun emitRaw(expression: Expression) {
        when (expression) {
            is LiteralExpression -> visitLiteralExpression(expression)
            is IdentifierExpression -> visitIdentifierExpression(expression)
            is BinaryExpression -> visitBinaryExpression(expression)
            is CallExpression -> visitCallExpression(expression)
            is UnaryExpression -> visitUnaryExpression(expression)
            is ArrayLiteralExpression -> visitArrayLiteralExpression(expression)
            is AssignmentExpression -> visitAssignmentExpression(expression)
            is MemberAccessExpression -> visitMemberAccessExpression(expression)
            is StringTemplateExpression -> visitStringTemplateExpression(expression)
            is NamedArgumentExpression -> emit(expression.value)
            is IsExpression -> visitIsExpression(expression)
            is IndexAccessExpression -> visitIndexAccessExpression(expression)
            is PanicExpression -> visitPanicExpression(expression)
            is CatchExpression -> visitCatchExpression(expression)
            else -> context.writer.write("/* unsupported expression: ${expression::class.simpleName} */")
        }
    }

    private fun visitLiteralExpression(expression: LiteralExpression) {
        when (val value = expression.value) {
            is String -> context.writer.write("\"${value.replace("\"", "\\\"")}\"")
            is Number -> context.writer.write(value.toString())
            is Boolean -> context.writer.write(if (value) "1" else "0")
            null -> context.writer.write("NULL")
            else -> context.writer.write(value.toString())
        }
    }

    private fun visitIdentifierExpression(expression: IdentifierExpression) {
        val symbol = context.resolvedSymbols[expression]
        if (symbol != null) {
            emitSymbol(symbol)
        } else {
            context.writer.write(context.symbolEmitter.mangle(expression.name))
        }
    }

    private fun visitBinaryExpression(expression: BinaryExpression) {
        val leftType = context.expressionTypes[expression.left]
        val rightType = context.expressionTypes[expression.right]
        if (expression.operator == "+" && (leftType == Type.String || rightType == Type.String)) {
            context.writer.write(context.symbolEmitter.runtimeHelper("str_concat"))
            context.writer.write("(")
            emitSegment(expression.left)
            context.writer.write(", ")
            emitSegment(expression.right)
            context.writer.write(")")
        } else if (expression.operator == "+" && (leftType is Type.ArrayList || leftType is Type.List)) {
            context.writer.write("({ ")
            context.writer.write("${context.typeMapper.map(leftType)} _l = ")
            emit(expression.left)
            context.writer.write("; ")
            context.writer.write(context.symbolEmitter.runtimeHelper("list_push"))
            context.writer.write("(_l, ")
            emit(expression.right, Type.Any)
            context.writer.write("); _l; })")
        } else if ((expression.operator == "==" || expression.operator == "!=") && leftType == Type.String) {
            if (expression.operator == "!=") context.writer.write("!")
            context.writer.write("strcmp(")
            emit(expression.left)
            context.writer.write(", ")
            emit(expression.right)
            context.writer.write(") == 0")
        } else {
            context.writer.write("(")
            emit(expression.left)
            context.writer.write(" ${expression.operator} ")
            emit(expression.right)
            context.writer.write(")")
        }
    }

    private fun visitCallExpression(expression: CallExpression) {
        val calleeType = context.expressionTypes[expression.callee]
        if (calleeType is Type.Struct) {
            emitStructConstruction(expression, calleeType)
            return
        }

        val calleeSymbol = context.resolvedSymbols[expression.callee]
        val functionType = calleeSymbol?.type as? Type.Function
        val isExtension = calleeSymbol?.isExtension == true

        emit(expression.callee)
        context.writer.write("(")

        if (isExtension && functionType != null) {
            val receiver = (expression.callee as? MemberAccessExpression)?.target
            if (receiver != null) {
                val receiverType = functionType.parameterTypes[0]
                val isMutated = functionType.isParameterMutated.getOrElse(0) { false }
                if (isMutated && isPrimitive(receiverType)) {
                    context.writer.write("&")
                }
                emit(receiver, receiverType)
                if (expression.arguments.isNotEmpty()) context.writer.write(", ")
            }
        }

        expression.arguments.forEachIndexed { index, arg ->
            val mutationIndex = if (isExtension) index + 1 else index
            val paramType = functionType?.parameterTypes?.getOrNull(mutationIndex) ?: Type.Any
            val isMutated = functionType?.isParameterMutated?.getOrNull(mutationIndex) ?: false

            if (isMutated && isPrimitive(paramType)) {
                context.writer.write("&")
            }

            emit(arg, paramType)

            if (index < expression.arguments.size - 1) {
                context.writer.write(", ")
            }
        }
        context.writer.write(")")
    }

    private fun visitUnaryExpression(expression: UnaryExpression) {
        context.writer.write(expression.operator)
        emit(expression.operand)
    }

    private fun visitArrayLiteralExpression(expression: ArrayLiteralExpression) {
        context.writer.write(context.symbolEmitter.runtimeHelper("create_list"))
        context.writer.write("(")
        context.writer.write(expression.elements.size.toString())
        expression.elements.forEach { arg ->
            context.writer.write(", ")
            emit(arg, Type.Any)
        }
        context.writer.write(")")
    }

    private fun visitAssignmentExpression(expression: AssignmentExpression) {
        val target = expression.target
        if (target is IdentifierExpression) {
            val symbol = context.resolvedSymbols[target]
            val name = symbol?.let { context.symbolEmitter.mangle(it.name, it.namespace) } ?: context.symbolEmitter.mangle(target.name)
            if (symbol != null && symbol.isParameter && symbol.isMutated && isPrimitive(symbol.type)) {
                context.writer.write("(*$name)")
            } else {
                context.writer.write(name)
            }
            context.writer.write(" = ")
            val targetType = context.expressionTypes[target] ?: Type.Any
            emit(expression.value, targetType)
        } else if (target is IndexAccessExpression) {
            val targetType = context.expressionTypes[target.target]
            if (targetType is Type.List || targetType is Type.ArrayList || targetType == Type.Any) {
                context.writer.write(context.symbolEmitter.runtimeHelper("list_set"))
                context.writer.write("(")
                emit(target.target)
                context.writer.write(", ")
                emit(target.index)
                context.writer.write(", ")
                emit(expression.value, Type.Any)
                context.writer.write(")")
            } else {
                emitRaw(target)
                context.writer.write(" = ")
                val valType = context.expressionTypes[target] ?: Type.Any
                emit(expression.value, valType)
            }
        } else {
            emitRaw(target)
            context.writer.write(" = ")
            val targetType = context.expressionTypes[target] ?: Type.Any
            emit(expression.value, targetType)
        }
    }

    private fun visitMemberAccessExpression(expression: MemberAccessExpression) {
        val targetType = context.expressionTypes[expression.target]
        if (targetType is Type.EnumNamespace) {
            val enumType = targetType.enumType
            context.writer.write(context.symbolEmitter.mangle("${enumType.name}_${expression.member.name}", enumType.moduleNamespace))
            return
        }

        val symbol = context.resolvedSymbols[expression]
        if (symbol != null) {
            emitSymbol(symbol)
        } else {
            emit(expression.target)
            context.writer.write(".")
            context.writer.write(expression.member.name)
        }
    }

    private fun visitStringTemplateExpression(expression: StringTemplateExpression) {
        emitStringTemplate(expression.segments)
    }

    private fun visitIsExpression(expression: IsExpression) {
        val descriptor = when (val targetType = context.resolvedIsTypes[expression]) {
            is Type.Number -> "&__amber_type_double"
            is Type.String -> "&__amber_type_string"
            is Type.Boolean -> "&__amber_type_bool"
            is Type.List, is Type.ArrayList -> "&__amber_type_list"
            is Type.Enum -> "&${context.symbolEmitter.mangle("type_${targetType.name}", targetType.moduleNamespace)}"
            is Type.Struct -> "&${context.symbolEmitter.mangle("type_${targetType.name}", targetType.namespace)}"
            else -> "NULL"
        }
        context.writer.write("__amber_rt_is_type(")
        emit(expression.left, Type.Any)
        context.writer.write(", $descriptor)")
    }

    private fun visitIndexAccessExpression(expression: IndexAccessExpression) {
        val targetType = context.expressionTypes[expression.target]
        if (targetType is Type.List || targetType is Type.ArrayList || targetType == Type.Any) {
            val elementType = when (targetType) {
                is Type.List -> targetType.elementType
                is Type.ArrayList -> targetType.elementType
                else -> Type.Any
            }
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
            context.writer.write(context.symbolEmitter.runtimeHelper("list_get"))
            context.writer.write("(")
            emit(expression.target)
            context.writer.write(", ")
            emit(expression.index)
            context.writer.write(")")
            if (boxFunc.isNotEmpty()) {
                context.writer.write(")")
            }
        } else if (targetType == Type.String) {
            context.writer.write(context.symbolEmitter.runtimeHelper("str_get"))
            context.writer.write("(")
            emit(expression.target)
            context.writer.write(", ")
            emit(expression.index)
            context.writer.write(")")
        } else {
            emit(expression.target)
            context.writer.write("[")
            emit(expression.index)
            context.writer.write("]")
        }
    }

    private fun visitPanicExpression(expression: PanicExpression) {
        if (expression.isFatal) {
            context.writer.write("__amber_rt_panic(")
            if (expression.message != null) emit(expression.message) else context.writer.write("\"panic\"")
            context.writer.write(")")
        } else {
            context.writer.write("__amber_rt_result_error(")
            if (expression.message != null) emit(expression.message) else context.writer.write("\"error\"")
            context.writer.write(")")
        }
    }

    private fun visitCatchExpression(expression: CatchExpression) {
        val targetType = context.expressionTypes[expression.target]
        if (targetType !is Type.Unsafe && targetType != Type.Error) {
            emit(expression.target)
            return
        }
        val innerType = if (targetType is Type.Unsafe) targetType.innerType else Type.Any
        val cInnerType = context.typeMapper.map(innerType)

        val resVar = context.symbolEmitter.nextTemp()
        val finalResVar = context.symbolEmitter.nextTemp()

        context.writer.write("({ ")
        context.writer.write("struct AMBER_RESULT $resVar = ")
        emit(expression.target)
        context.writer.write("; ")
        context.writer.write("$cInnerType $finalResVar; ")
        context.writer.write("if (__amber_rt_is_error($resVar)) { ")

        if (expression.errorVarName != "_" && expression.errorVarName.isNotEmpty()) {
            context.writer.write("const char* ${context.symbolEmitter.mangle(expression.errorVarName)} = $resVar.error_message; ")
        }

        expression.body.statements.forEachIndexed { index, stmt ->
            val isLast = index == expression.body.statements.size - 1
            if (isLast) {
                if (stmt is ExpressionStatement) {
                    val expr = stmt.expression
                    if (context.expressionTypes[expr] == Type.Nothing) {
                        emit(expr)
                        context.writer.write("; ")
                    } else {
                        context.writer.write("$finalResVar = ")
                        emit(expr)
                        context.writer.write("; ")
                    }
                } else if (stmt is VariableDeclaration) {
                    val vSymbol = context.resolvedSymbols[stmt.name]
                    val vType = context.expressionTypes[stmt.initializer] ?: vSymbol?.type ?: Type.Any
                    val vName = vSymbol?.let { context.symbolEmitter.mangle(it.name, it.namespace) } ?: context.symbolEmitter.mangle(stmt.name.name)
                    context.writer.write("${context.typeMapper.map(vType)} $vName")
                    if (stmt.initializer != null) {
                        context.writer.write(" = ")
                        emit(stmt.initializer)
                    }
                    context.writer.write("; ")
                    context.writer.write("$finalResVar = $vName; ")
                } else {
                    statementEmitter?.emit(stmt) ?: context.writer.write("/* StatementEmitter not set */ ")
                }
            } else {
                statementEmitter?.emit(stmt) ?: context.writer.write("/* StatementEmitter not set */ ")
            }
        }

        context.writer.write("} else { ")
        context.writer.write("$finalResVar = ")
        if (innerType == Type.Number) {
            context.writer.write("__amber_rt_unbox_double(__amber_rt_unwrap($resVar))")
        } else if (innerType == Type.Boolean) {
            context.writer.write("__amber_rt_unbox_bool(__amber_rt_unwrap($resVar))")
        } else if (innerType == Type.Char) {
            context.writer.write("__amber_rt_unbox_char(__amber_rt_unwrap($resVar))")
        } else if (innerType == Type.String) {
            context.writer.write("__amber_rt_unbox_string(__amber_rt_unwrap($resVar))")
        } else {
            context.writer.write("(${cInnerType})__amber_rt_unwrap($resVar)")
        }
        context.writer.write("; ")
        context.writer.write("} ")
        context.writer.write("$finalResVar; ")
        context.writer.write("})")
    }

    private fun isPrimitive(type: Type): Boolean {
        return type == Type.Number || type == Type.Boolean || type == Type.String || type == Type.Char || type is Type.Enum || type is Type.Struct || type is Type.Unsafe
    }

    private fun emitSymbol(symbol: Symbol) {
        val platformName = context.runtimeProvider.getPlatformName(symbol)
        if (platformName != null) {
            context.writer.write(context.symbolEmitter.runtimeHelper(platformName))
        } else {
            val name = if (symbol.isExtension) {
                val funcType = symbol.type as Type.Function
                context.symbolEmitter.mangleExtension(symbol.name, funcType.parameterTypes[0], symbol.namespace)
            } else {
                context.symbolEmitter.mangle(symbol.name, symbol.namespace)
            }

            if (symbol.isParameter && symbol.isMutated && isPrimitive(symbol.type)) {
                context.writer.write("(*$name)")
            } else {
                context.writer.write(name)
            }
        }
    }

    private fun emitStringTemplate(segments: List<Expression>) {
        if (segments.size == 1) {
            emit(segments[0], Type.String)
            return
        }

        context.writer.write(context.symbolEmitter.runtimeHelper("str_concat_multi"))
        context.writer.write("(${segments.size}")
        segments.forEach { segment ->
            context.writer.write(", ")
            val type = context.expressionTypes[segment]
            when (type) {
                Type.Number -> {
                    context.writer.write(context.symbolEmitter.runtimeHelper("from_num"))
                    context.writer.write("(")
                    emit(segment)
                    context.writer.write(")")
                }
                Type.String -> {
                    emit(segment)
                }
                else -> {
                    context.writer.write(context.symbolEmitter.runtimeHelper("to_string"))
                    context.writer.write("(")
                    emit(segment, Type.Any)
                    context.writer.write(")")
                }
            }
        }
        context.writer.write(")")
    }

    private fun emitStructConstruction(expression: CallExpression, structType: Type.Struct) {
        val mangledName = context.symbolEmitter.mangleStruct(structType.name, structType.namespace)
        context.writer.write("({ $mangledName __am_tmp = {0}; ")
        
        expression.arguments.forEachIndexed { index, arg ->
            val field = structType.fields.values.elementAtOrNull(index)
            if (field != null) {
                context.writer.write("__am_tmp.${field.name} = ")
                emit(arg, field.type)
                context.writer.write("; ")
            }
        }
        
        context.writer.write("__am_tmp; })")
    }

    private fun emitSegment(expression: Expression) {
        val type = context.expressionTypes[expression]
        when (type) {
            Type.Number -> {
                context.writer.write(context.symbolEmitter.runtimeHelper("from_num"))
                context.writer.write("(")
                emit(expression)
                context.writer.write(")")
            }
            Type.String -> {
                emit(expression)
            }
            else -> {
                context.writer.write(context.symbolEmitter.runtimeHelper("to_string"))
                context.writer.write("(")
                emit(expression, Type.Any)
                context.writer.write(")")
            }
        }
    }
}
