package off.kys.amber_lang.transpiler.type

import off.kys.amber_lang.runtime.RuntimeProvider
import off.kys.amber_lang.transpiler.Diagnostic
import off.kys.amber_lang.transpiler.GenericDiagnostic
import off.kys.amber_lang.transpiler.ImportResolutionException
import off.kys.amber_lang.transpiler.ImportResolver
import off.kys.amber_lang.transpiler.Severity
import off.kys.amber_lang.transpiler.TypeError
import off.kys.amber_lang.transpiler.ast.ArrayLiteralExpression
import off.kys.amber_lang.transpiler.ast.AssignmentExpression
import off.kys.amber_lang.transpiler.ast.AstNode
import off.kys.amber_lang.transpiler.ast.BinaryExpression
import off.kys.amber_lang.transpiler.ast.BlockStatement
import off.kys.amber_lang.transpiler.ast.CallExpression
import off.kys.amber_lang.transpiler.ast.CatchExpression
import off.kys.amber_lang.transpiler.ast.EnumDeclaration
import off.kys.amber_lang.transpiler.ast.ErrorNode
import off.kys.amber_lang.transpiler.ast.Expression
import off.kys.amber_lang.transpiler.ast.ExpressionStatement
import off.kys.amber_lang.transpiler.ast.FunctionDeclaration
import off.kys.amber_lang.transpiler.ast.IdentifierExpression
import off.kys.amber_lang.transpiler.ast.IfStatement
import off.kys.amber_lang.transpiler.ast.ImportStatement
import off.kys.amber_lang.transpiler.ast.IndexAccessExpression
import off.kys.amber_lang.transpiler.ast.IsExpression
import off.kys.amber_lang.transpiler.ast.LiteralExpression
import off.kys.amber_lang.transpiler.ast.MemberAccessExpression
import off.kys.amber_lang.transpiler.ast.PanicExpression
import off.kys.amber_lang.transpiler.ast.Program
import off.kys.amber_lang.transpiler.ast.ReturnStatement
import off.kys.amber_lang.transpiler.ast.Statement
import off.kys.amber_lang.transpiler.ast.UnaryExpression
import off.kys.amber_lang.transpiler.ast.VariableDeclaration
import off.kys.amber_lang.transpiler.ast.WhileStatement

class TypeChecker(
    private val projectRoot: String,
    private val currentFilePath: String,
    private val runtimeProvider: RuntimeProvider,
    private val isMainFile: Boolean = true,
    private val isProject: Boolean = false,
    private val executableDir: String = "."
) {
    private var currentScope: SymbolTable
    internal val expressionTypes = mutableMapOf<Expression, Type>()
    internal val resolvedSymbols = mutableMapOf<Expression, Symbol>()
    val errors = mutableListOf<Diagnostic>()
    private var currentFunctionReturnType: Type? = null
    private val expectedReturnTypes = mutableListOf<Type>()

    private val typeResolver = TypeResolver(::reportError)
    private val typeCompatibilityChecker = TypeCompatibilityChecker(::reportError)
    private val importResolver = ImportResolver(projectRoot, isProject, executableDir)

    val importedModulePrograms = mutableMapOf<String, Program>()
    val importedModuleTypeCheckers = mutableMapOf<String, TypeChecker>()

    init {
        val globalScope = SymbolTable(initialSymbols = runtimeProvider.getBuiltInSymbols())
        currentScope = globalScope.enterScope()
    }

    fun check(program: Program): Triple<Map<Expression, Type>, Map<Expression, Symbol>, List<Diagnostic>> {
        visitProgram(program)
        return Triple(expressionTypes, resolvedSymbols, errors)
    }

    private fun reportError(node: AstNode, message: String, suggestion: String? = null) {
        errors.add(TypeError(currentFilePath, node.line, node.column, message, node.length, suggestion = suggestion))
    }

    private fun defineSymbol(node: AstNode, symbol: Symbol) {
        if (!currentScope.define(symbol)) {
            reportError(node, "identifier '${symbol.name}' already exists in this scope")
        }
    }

    private fun reportUnusedSymbols() {
        if (isStandardLibraryFile(currentFilePath)) return
        val isTopLevel = currentScope.parent == null || currentScope.parent?.parent == null
        currentScope.getUnusedSymbols().forEach { symbol ->
            if (symbol.line != -1 && !symbol.name.startsWith("_")) {
                // In non-main files, don't warn about unused top-level declarations (they are exports)
                // EXCEPT for imports, which should always be used if present.
                if (!isMainFile && isTopLevel && symbol.type !is Type.ModuleType) {
                    return@forEach
                }

                errors.add(
                    GenericDiagnostic(
                        filePath = currentFilePath,
                        line = symbol.line,
                        column = symbol.column,
                        message = "unused declaration: '${symbol.name}'",
                        type = "Warning",
                        length = symbol.name.length,
                        suggestion = "remove it",
                        severity = Severity.WARNING
                    )
                )
            }
        }
    }

    fun reportUnusedExports() {
        if (isMainFile || isStandardLibraryFile(currentFilePath)) return
        currentScope.getUnusedSymbols().forEach { symbol ->
            if (symbol.line != -1 && !symbol.name.startsWith("_") && symbol.type !is Type.ModuleType) {
                errors.add(
                    GenericDiagnostic(
                        filePath = currentFilePath,
                        line = symbol.line,
                        column = symbol.column,
                        message = "unused export: '${symbol.name}'",
                        type = "Warning",
                        length = symbol.name.length,
                        suggestion = "remove it",
                        severity = Severity.WARNING
                    )
                )
            }
        }
    }

    fun hasErrors(): Boolean = errors.any { it.severity == Severity.ERROR }

    private fun visitProgram(program: Program) {
        program.statements.forEach {
            if (!isMainFile && !isDeclaration(it)) {
                reportError(
                    it,
                    "direct invoking is not allowed in non-main script files",
                    "move this logic into a function or the main script"
                )
            }
            visitStatement(it)
        }
        reportUnusedSymbols()
    }

    private fun isDeclaration(statement: Statement): Boolean {
        return statement is VariableDeclaration ||
                statement is FunctionDeclaration ||
                statement is ImportStatement ||
                statement is EnumDeclaration
    }

    private fun visitStatement(statement: Statement, skipUnusedWarning: Boolean = false) {
        when (statement) {
            is VariableDeclaration -> visitVariableDeclaration(statement)
            is EnumDeclaration -> visitEnumDeclaration(statement)
            is ExpressionStatement -> visitExpressionStatement(statement, skipUnusedWarning)
            is BlockStatement -> visitBlockStatement(statement, isExpression = skipUnusedWarning)
            is IfStatement -> visitIfStatement(statement)
            is WhileStatement -> visitWhileStatement(statement)
            is FunctionDeclaration -> visitFunctionDeclaration(statement)
            is ReturnStatement -> visitReturnStatement(statement)
            is ImportStatement -> visitImportStatement(statement)
        }
    }

    private fun visitExpression(expression: Expression, allowUnsafe: Boolean = false): Type {
        val type = when (expression) {
            is LiteralExpression -> visitLiteralExpression(expression)
            is IdentifierExpression -> visitIdentifierExpression(expression)
            is BinaryExpression -> visitBinaryExpression(expression)
            is UnaryExpression -> visitUnaryExpression(expression)
            is IsExpression -> visitIsExpression(expression)
            is CallExpression -> visitCallExpression(expression)
            is AssignmentExpression -> visitAssignmentExpression(expression)
            is MemberAccessExpression -> visitMemberAccessExpression(expression)
            is ArrayLiteralExpression -> visitArrayLiteralExpression(expression)
            is IndexAccessExpression -> visitIndexAccessExpression(expression)
            is PanicExpression -> visitPanicExpression(expression)
            is CatchExpression -> visitCatchExpression(expression)
            is ErrorNode -> Type.ErrorType
        }
        expressionTypes[expression] = type
        if (!allowUnsafe && type is Type.UnsafeType) {
            reportError(expression, "unhandled unsafe call", "handle it with 'or catch' or 'or panic'")
        }
        return type
    }

    private fun isStandardLibraryFile(filePath: String): Boolean {
        return filePath.contains("/lib/std/") || filePath.contains("lib/std/")
    }

    private fun visitVariableDeclaration(declaration: VariableDeclaration) {
        if (declaration.isIntrinsic && !isStandardLibraryFile(currentFilePath)) {
            reportError(declaration, "intrinsic variables are only allowed in the core standard library")
        }
        val declaredType = declaration.typeAnnotation?.let { typeResolver.resolveType(it, declaration, currentScope) }
        var initializerType = declaration.initializer?.let { visitExpression(it) }

        if (declaration.initializer is ArrayLiteralExpression && initializerType is Type.ListType) {
            initializerType = if (declaration.isMutable) {
                Type.ArrayListType(initializerType.elementType)
            } else {
                initializerType
            }
        }

        val finalType: Type

        if (declaredType != null) {
            finalType = if (declaration.isMutable && declaredType is Type.ListType) {
                Type.ArrayListType(declaredType.elementType)
            } else {
                declaredType
            }
            if (initializerType != null && initializerType != Type.UnitType) {
                typeCompatibilityChecker.checkAssignmentCompatibility(
                    finalType,
                    initializerType,
                    declaration,
                    declaration.name.name
                )
            }
        } else {
            finalType = initializerType ?: Type.ErrorType
            if (finalType == Type.ErrorType) {
                reportError(
                    declaration,
                    "cannot infer type for variable '${declaration.name.name}'",
                    "add an initializer or a type annotation"
                )
            }
        }

        val symbol = Symbol(
            declaration.name.name,
            finalType,
            declaration.isMutable,
            isIntrinsic = declaration.isIntrinsic,
            line = declaration.name.line,
            column = declaration.name.column
        )
        defineSymbol(declaration.name, symbol)
    }

    private fun visitExpressionStatement(statement: ExpressionStatement, skipUnusedWarning: Boolean = false) {
        val type = visitExpression(statement.expression)
        if (!skipUnusedWarning && statement.expression !is AssignmentExpression && type != Type.UnitType && type != Type.ErrorType && type !is Type.UnsafeType && type != Type.AnyType && type != Type.NothingType) {
            errors.add(
                GenericDiagnostic(
                    filePath = currentFilePath,
                    line = statement.line,
                    column = statement.column,
                    message = "unused return value",
                    type = "Warning",
                    suggestion = "prefix with '_' if this is intentional",
                    severity = Severity.WARNING
                )
            )
        }
    }

    private fun visitLiteralExpression(literal: LiteralExpression): Type = when (literal.value) {
        null -> Type.UnitType
        is Int, is Double, is Float, is Long, is Short, is Byte -> Type.NumberType
        is String -> Type.StringType
        is Boolean -> Type.BooleanType
        is Char -> Type.CharType
        else -> {
            reportError(
                literal,
                "unknown literal type: ${literal.value::class.simpleName}",
                "check if this literal is supported"
            )
            Type.ErrorType
        }
    }

    private fun visitIdentifierExpression(identifier: IdentifierExpression): Type {
        val symbol = currentScope.resolve(identifier.name)
        if (symbol != null) {
            if (symbol.name == "to_string" && symbol.isIntrinsic && !isStandardLibraryFile(currentFilePath) && identifier.name == "to_string" && !identifier.isSynthetic) {
                reportError(
                    identifier,
                    "direct use of 'to_string' is not allowed",
                    "import 'core:str' and use 'str.to_string(value)' instead"
                )
            }
            resolvedSymbols[identifier] = symbol
            return symbol.type
        } else {
            // Handle synthetic calls for intrinsics not in global scope
            if (identifier.isSynthetic) {
                val intrinsicSymbol = runtimeProvider.getAllIntrinsicSymbols()[identifier.name]
                if (intrinsicSymbol != null) {
                    resolvedSymbols[identifier] = intrinsicSymbol
                    return intrinsicSymbol.type
                }
            }

            // Special error message for to_string
            if (identifier.name == "to_string" && !identifier.isSynthetic) {
                reportError(
                    identifier,
                    "direct use of 'to_string' is not allowed",
                    "import 'core:str' and use 'str.to_string(value)' instead"
                )
            } else {
                reportError(
                    identifier,
                    "undefined identifier '${identifier.name}'",
                    "declare it before use or check for typos"
                )
            }
            return Type.ErrorType
        }
    }

    private fun visitBinaryExpression(binary: BinaryExpression): Type {
        val leftType = visitExpression(binary.left)
        val rightType = visitExpression(binary.right)

        if (binary.operator == "+") {
            if ((leftType == Type.StringType && rightType == Type.NumberType) ||
                (leftType == Type.NumberType && rightType == Type.StringType)) {
                reportError(
                    binary,
                    "operator '+' cannot be applied to types '$leftType' and '$rightType'",
                    "explicitly convert the number to a string or vice versa"
                )
                return Type.ErrorType
            }

            // Allow array/list + element concatenation -> returns modified structure type
            if (leftType is Type.ListType || leftType is Type.ArrayListType) {
                val elementType = when (leftType) {
                    is Type.ListType -> leftType.elementType
                    is Type.ArrayListType -> leftType.elementType
                }

                if (rightType != elementType && elementType != Type.AnyType && rightType != Type.ErrorType) {
                    reportError(
                        binary,
                        "cannot add element of type '$rightType' to a collection of type '$leftType'",
                        "ensure the item matches the collection's element type"
                    )
                    return Type.ErrorType
                }
                return leftType
            }
        }

        return typeCompatibilityChecker.checkBinaryOperatorCompatibility(leftType, binary.operator, rightType, binary)
    }

    private fun visitUnaryExpression(unary: UnaryExpression): Type {
        val operandType = visitExpression(unary.operand)
        return typeCompatibilityChecker.checkUnaryOperatorCompatibility(unary.operator, operandType, unary)
    }

    private fun visitIsExpression(isExpr: IsExpression): Type {
        visitExpression(isExpr.left)
        typeResolver.resolveType(isExpr.typeName, isExpr, currentScope)
        return Type.BooleanType
    }

    private fun visitCallExpression(call: CallExpression): Type {
        val functionSymbol = resolveCalledFunctionSymbol(call.callee)
        if (functionSymbol != null) {
            resolvedSymbols[call.callee] = functionSymbol
        }
        var calleeType = visitExpression(call.callee)
        if (calleeType !is Type.FunctionType) {
            reportError(
                call.callee,
                "expression is not a function: $calleeType",
                "ensure you are calling a function"
            )
            return Type.ErrorType
        }

        val argTypes = call.arguments.map { visitExpression(it) }
        if (isValidArgumentCount(calleeType, argTypes.size)) {
            calleeType = inferFunctionTypeFromFirstCall(functionSymbol, calleeType, argTypes)
        }
        typeCompatibilityChecker.checkFunctionCallArguments(call, calleeType, argTypes)

        return calleeType.returnType
    }

    private fun resolveCalledFunctionSymbol(callee: Expression): Symbol? {
        return when (callee) {
            is IdentifierExpression -> currentScope.resolve(callee.name)
            else -> null
        }
    }

    private fun isValidArgumentCount(functionType: Type.FunctionType, argCount: Int): Boolean {
        val minArgs = functionType.parameterTypes.zip(functionType.hasDefaultValues).count { (_, hasDefault) -> !hasDefault }
        val maxArgs = functionType.parameterTypes.size
        return argCount in minArgs..maxArgs
    }

    private fun inferFunctionTypeFromFirstCall(
        functionSymbol: Symbol?,
        functionType: Type.FunctionType,
        argTypes: List<Type>
    ): Type.FunctionType {
        if (functionSymbol == null || functionSymbol.inferableParameterIndices.isEmpty()) {
            return functionType
        }

        val inferredParameterTypes = functionType.parameterTypes.toMutableList()
        val inferredParameterIndices = mutableSetOf<Int>()
        var inferredAnyType = false

        argTypes.forEachIndexed { index, argType ->
            if (index >= inferredParameterTypes.size) return@forEachIndexed
            if (!functionSymbol.inferableParameterIndices.contains(index)) return@forEachIndexed

            val expectedType = inferredParameterTypes[index]
            if (expectedType == Type.AnyType && argType != Type.ErrorType && argType != Type.AnyType) {
                inferredParameterTypes[index] = argType
                inferredParameterIndices.add(index)
                inferredAnyType = true
            }
        }

        return if (inferredAnyType) {
            functionSymbol.inferableParameterIndices.removeAll(inferredParameterIndices)
            val inferredFunctionType = functionType.copy(parameterTypes = inferredParameterTypes)
            functionSymbol.type = inferredFunctionType
            inferredFunctionType
        } else {
            functionType
        }
    }

    private fun visitAssignmentExpression(assignment: AssignmentExpression): Type {
        val targetSymbol = currentScope.resolve(assignment.target.name)
        if (targetSymbol == null) {
            reportError(
                assignment,
                "cannot assign to undeclared variable '${assignment.target.name}'",
                "declare the variable before assigning to it"
            )
            return Type.ErrorType
        }
        resolvedSymbols[assignment.target] = targetSymbol
        if (!targetSymbol.isMutable) {
            reportError(
                assignment,
                "cannot assign to immutable variable '${assignment.target.name}'",
                "use 'var' instead of 'val' to allow reassignment"
            )
            return Type.ErrorType
        }

        // Disallow operations like list += item if parsed as complex BinaryExpression structures
        val isTargetCollection = targetSymbol.type is Type.ListType || targetSymbol.type is Type.ArrayListType
        val valueExpression = assignment.value

        if (isTargetCollection && valueExpression is BinaryExpression && valueExpression.operator == "+") {
            val leftId = valueExpression.left as? IdentifierExpression
            if (leftId != null && leftId.name == targetSymbol.name) {
                reportError(
                    assignment,
                    "operator '+=' is not supported for array or list updates",
                    "use '+' concatenation and explicitly reassign"
                )
                return Type.ErrorType
            }
        }

        val updateOperator = detectIncDecOperator(assignment)
        if (
            updateOperator != null &&
            targetSymbol.type != Type.NumberType &&
            targetSymbol.type != Type.AnyType &&
            targetSymbol.type != Type.ErrorType
        ) {
            reportError(
                assignment,
                "operator '$updateOperator' requires a number, but '${assignment.target.name}' is ${targetSymbol.type}",
                "use a number type for increment/decrement"
            )
        }

        val valueType = visitExpression(assignment.value)
        typeCompatibilityChecker.checkAssignmentCompatibility(
            targetSymbol.type,
            valueType,
            assignment,
            targetSymbol.name
        )
        return valueType
    }

    private fun detectIncDecOperator(assignment: AssignmentExpression): String? {
        val binary = assignment.value as? BinaryExpression ?: return null
        val leftIdentifier = binary.left as? IdentifierExpression ?: return null
        val oneLiteral = binary.right as? LiteralExpression ?: return null

        if (leftIdentifier.name != assignment.target.name) return null
        if (oneLiteral.value != 1) return null

        return when (binary.operator) {
            "+" -> "++"
            "-" -> "--"
            else -> null
        }
    }

    private fun visitMemberAccessExpression(memberAccess: MemberAccessExpression): Type {
        val targetType = visitExpression(memberAccess.target)
        val memberName = memberAccess.member.name

        if (targetType is Type.EnumTypeNamespace) {
            val enumType = targetType.enumType
            if (memberName in enumType.variants) {
                expressionTypes[memberAccess] = enumType
                return enumType
            } else {
                reportError(
                    memberAccess.member,
                    "enum '${enumType.name}' has no variant '$memberName'",
                    "check for typos or ensure the variant exists"
                )
                return Type.ErrorType
            }
        }

        if (targetType is Type.ModuleType) {
            val symbol = targetType.exportedSymbols[memberName]
            if (symbol != null) {
                symbol.isUsed = true
                resolvedSymbols[memberAccess] = symbol
                return symbol.type
            } else {
                reportError(
                    memberAccess.member,
                    "module '${(memberAccess.target as? IdentifierExpression)?.name ?: "unknown"}' has no member '$memberName'",
                    "check for typos or ensure the member is exported"
                )
                return Type.ErrorType
            }
        } else {
            reportError(
                memberAccess.target,
                "member access only allowed on modules. found type: $targetType",
                "ensure the left side is an imported module"
            )
            return Type.ErrorType
        }
    }

    private fun visitArrayLiteralExpression(expression: ArrayLiteralExpression): Type {
        if (expression.elements.isEmpty()) {
            return Type.ListType(Type.AnyType)
        }
        val elementTypes = expression.elements.map { visitExpression(it) }
        val firstType = elementTypes.first()
        val allSame = elementTypes.all { it == firstType }
        val elementType = if (allSame) firstType else Type.AnyType
        return Type.ListType(elementType)
    }

    private fun visitIndexAccessExpression(expression: IndexAccessExpression): Type {
        val targetType = visitExpression(expression.target)
        val indexType = visitExpression(expression.index)

        if (targetType == Type.ErrorType || indexType == Type.ErrorType) return Type.ErrorType

        if (indexType != Type.NumberType) {
            reportError(expression.index, "index must be a number, but got $indexType", null)
        }

        return when (targetType) {
            is Type.ListType -> targetType.elementType
            is Type.ArrayListType -> targetType.elementType
            Type.StringType -> Type.CharType
            else -> {
                reportError(expression.target, "cannot index into type $targetType", null)
                Type.ErrorType
            }
        }
    }

    private fun visitPanicExpression(panic: PanicExpression): Type {
        panic.message?.let {
            val messageType = visitExpression(it)
            if (messageType != Type.StringType && messageType != Type.ErrorType) {
                reportError(it, "panic message must be a string, but got $messageType", "ensure the expression evaluates to a string")
            }
        }
        return Type.NothingType
    }

    private fun visitCatchExpression(catch: CatchExpression): Type {
        val targetType = visitExpression(catch.target, allowUnsafe = true)
        if (targetType !is Type.UnsafeType && targetType != Type.ErrorType) {
            reportError(catch.target, "'or catch' can only be used on unsafe calls (type T!), but got $targetType")
            return targetType
        }

        val innerType = if (targetType is Type.UnsafeType) targetType.innerType else Type.ErrorType

        currentScope = currentScope.enterScope()
        val errorVarType = catch.errorVarType?.let { typeResolver.resolveType(it, catch, currentScope) } ?: Type.StringType
        if (errorVarType != Type.StringType && errorVarType != Type.ErrorType && errorVarType != Type.AnyType) {
            reportError(catch, "catch variable must be of type string, but got $errorVarType")
        }
        defineSymbol(catch, Symbol(catch.errorVarName, errorVarType, line = catch.line, column = catch.column))
        
        visitBlockStatement(catch.body, isExpression = true, expectedReturnType = innerType, contextName = "catch block")
        
        currentScope = currentScope.exitScope()!!
        
        return innerType
    }

    private fun visitBlockStatement(
        block: BlockStatement,
        isExpression: Boolean = false,
        expectedReturnType: Type? = null,
        contextName: String = "block"
    ) {
        currentScope = currentScope.enterScope()
        if (expectedReturnType != null) expectedReturnTypes.add(expectedReturnType)
        block.statements.forEachIndexed { index, statement ->
            val isLast = index == block.statements.size - 1

            if (block.statements.size == 1 && isExpression && isLast && statement is ReturnStatement) {
                errors.add(
                    GenericDiagnostic(
                        filePath = currentFilePath,
                        line = statement.line,
                        column = statement.column,
                        message = "redundant return keyword",
                        type = "Warning",
                        suggestion = "remove the 'return' keyword as it's the only expression in this block",
                        severity = Severity.WARNING,
                        length = 6
                    )
                )
            }

            visitStatement(statement, skipUnusedWarning = isExpression && isLast)

            if (block.statements.size == 1 && isExpression && isLast && statement is ExpressionStatement && expectedReturnType != null) {
                val actualType = expressionTypes[statement.expression] ?: Type.UnitType
                typeCompatibilityChecker.checkReturnType(actualType, expectedReturnType, statement)
            }
        }

        if (block.statements.size > 1 && isExpression && expectedReturnType != null && expectedReturnType != Type.UnitType && expectedReturnType != Type.ErrorType) {
            val last = block.statements.lastOrNull()
            if (last !is ReturnStatement) {
                reportError(
                    last ?: block,
                    "$contextName with multiple expressions must explicitly return a value",
                    "add a 'return' keyword to the last expression"
                )
            }
        }

        reportUnusedSymbols()
        if (expectedReturnType != null) expectedReturnTypes.removeAt(expectedReturnTypes.size - 1)
        currentScope = currentScope.exitScope()!!
    }

    private fun visitIfStatement(ifStmt: IfStatement) {
        val conditionType = visitExpression(ifStmt.condition)
        typeCompatibilityChecker.checkConditionType(conditionType, ifStmt.condition, "if statement")

        visitBlockStatement(ifStmt.thenBranch)

        ifStmt.elseBranch?.let {
            visitBlockStatement(it)
        }
    }

    private fun visitWhileStatement(whileStmt: WhileStatement) {
        val conditionType = visitExpression(whileStmt.condition)
        typeCompatibilityChecker.checkConditionType(conditionType, whileStmt.condition, "while statement")

        visitBlockStatement(whileStmt.body)
    }

    private fun visitEnumDeclaration(declaration: EnumDeclaration) {
        val enumType = Type.EnumType(declaration.name.name, declaration.variants.map { it.name })
        val namespaceType = Type.EnumTypeNamespace(enumType)
        val symbol = Symbol(
            declaration.name.name,
            namespaceType,
            isMutable = false,
            line = declaration.line,
            column = declaration.column
        )
        if (!currentScope.define(symbol)) {
            reportError(declaration.name, "symbol '${declaration.name.name}' is already defined")
        }
    }

    private fun visitFunctionDeclaration(function: FunctionDeclaration) {
        if (function.isIntrinsic) {
            if (!isStandardLibraryFile(currentFilePath)) {
                reportError(function, "intrinsic functions are only allowed in the core standard library")
            }
            if (function.body != null) {
                reportError(function, "intrinsic functions cannot have a body")
            }
        }
        val declaredReturnType =
            function.returnTypeAnnotation?.let { typeResolver.resolveType(it, function, currentScope) } ?: Type.UnitType
        val previousFunctionReturnType = currentFunctionReturnType
        currentFunctionReturnType = declaredReturnType

        val paramTypes = mutableListOf<Type>()
        val hasDefaultValues = mutableListOf<Boolean>()
        val inferableParameterIndices = mutableSetOf<Int>()

        function.parameters.forEachIndexed { index, param ->
            val paramDeclaredType = param.typeAnnotation?.let { typeResolver.resolveType(it, param, currentScope) }
            val tempScopeForDefaultValue = currentScope.enterScope()
            val originalScope = currentScope
            currentScope = tempScopeForDefaultValue
            val paramDefaultValueType = param.defaultValue?.let { visitExpression(it) }
            currentScope = originalScope

            val finalParamType = if (paramDeclaredType != null) {
                if (paramDefaultValueType != null && paramDefaultValueType != Type.UnitType) {
                    typeCompatibilityChecker.checkAssignmentCompatibility(
                        paramDeclaredType,
                        paramDefaultValueType,
                        param,
                        param.name.name
                    )
                }
                paramDeclaredType
            } else {
                if (paramDefaultValueType == null) {
                    inferableParameterIndices.add(index)
                }
                paramDefaultValueType ?: Type.AnyType
            }

            if (finalParamType == Type.ErrorType) {
                reportError(
                    param,
                    "cannot infer type for parameter '${param.name.name}'",
                    "add a type annotation or a default value"
                )
            }
            paramTypes.add(finalParamType)
            hasDefaultValues.add(param.defaultValue != null)
        }

        val functionType = Type.FunctionType(paramTypes, hasDefaultValues, declaredReturnType)
        val functionSymbol = Symbol(
            function.name.name,
            functionType,
            inferableParameterIndices = inferableParameterIndices,
            isIntrinsic = function.isIntrinsic,
            line = function.name.line,
            column = function.name.column
        )
        defineSymbol(function.name, functionSymbol)

        currentScope = currentScope.enterScope()
        function.parameters.forEachIndexed { index, param ->
            val paramType = paramTypes[index]
            val symbol = Symbol(
                param.name.name,
                paramType,
                line = param.name.line,
                column = param.name.column
            )
            if (function.isIntrinsic) symbol.isUsed = true
            defineSymbol(param.name, symbol)
        }

        function.body?.let { 
            visitBlockStatement(it, isExpression = true, expectedReturnType = declaredReturnType, contextName = "function '${function.name.name}'")
            if (declaredReturnType != Type.UnitType && !definitelyReturns(it)) {
                reportError(
                    function.name,
                    "function '${function.name.name}' must return a value of type $declaredReturnType",
                    "add a return statement at the end of the function or in all execution paths"
                )
            }
        }
        reportUnusedSymbols()
        currentScope = currentScope.exitScope()!!
        currentFunctionReturnType = previousFunctionReturnType
    }

    private fun visitReturnStatement(returnStmt: ReturnStatement) {
        val returnedType = returnStmt.value?.let { visitExpression(it) } ?: Type.UnitType

        val expectedType = expectedReturnTypes.lastOrNull() ?: currentFunctionReturnType
        if (expectedType == null) {
            reportError(
                returnStmt,
                "return statement is only allowed inside a function or a returnable block",
                "move this return into a function body or a catch block"
            )
            return
        }

        typeCompatibilityChecker.checkReturnType(returnedType, expectedType, returnStmt)
    }

    fun visitImportStatement(importStmt: ImportStatement) {
        val alias = importStmt.asName?.name
        val moduleIdentifierName = alias ?: importStmt.path.split(":").last().substringBeforeLast(".amb")

        if (currentScope.resolve(moduleIdentifierName) != null) {
            reportError(
                importStmt,
                "conflicting import: identifier '$moduleIdentifierName' already exists",
                "use a different alias"
            )
            return
        }

        try {
            val importedProgram = importResolver.resolveAndParse(importStmt.path, currentFilePath)

            if (importResolver.importErrors.isNotEmpty()) {
                errors.addAll(importResolver.importErrors)
                importResolver.importErrors.clear()
                return
            }

            if (importedProgram == null) {
                reportError(importStmt, "could not resolve or parse import '${importStmt.path}'")
                return
            }

            val importedFilePath = importResolver.resolveAbsolutePath(importStmt.path, currentFilePath)
            val importedTypeChecker = TypeChecker(
                projectRoot,
                importedFilePath,
                runtimeProvider,
                isMainFile = false,
                isProject = isProject,
                executableDir = executableDir
            )

            val (_, _, importedErrors) = importedTypeChecker.check(importedProgram)

            if (importedErrors.isNotEmpty()) {
                errors.addAll(importedErrors)
            }

            val symbolsToImport = importedTypeChecker.currentScope.getTopLevelSymbols()
            val moduleType = Type.ModuleType(symbolsToImport.associateBy { it.name })

            if (currentScope.resolve(moduleIdentifierName) != null) {
                reportError(
                    importStmt,
                    "conflicting import: identifier '$moduleIdentifierName' already exists",
                    "use a different alias"
                )
            } else {
                currentScope.define(
                    Symbol(
                        moduleIdentifierName,
                        moduleType,
                        line = importStmt.line,
                        column = importStmt.column
                    )
                )
                importedModulePrograms[importStmt.path] = importedProgram
                importedModuleTypeCheckers[importStmt.path] = importedTypeChecker
            }

        } catch (e: ImportResolutionException) {
            reportError(importStmt, "import resolution failed: ${e.message}")
        } catch (e: Exception) {
            reportError(importStmt, "error processing import: ${e.message}")
        }
    }

    private fun definitelyReturns(statement: Statement): Boolean {
        return when (statement) {
            is ReturnStatement -> true
            is BlockStatement -> {
                if (statement.statements.size == 1 && statement.statements[0] is ExpressionStatement) {
                    true
                } else {
                    statement.statements.any { definitelyReturns(it) }
                }
            }
            is IfStatement -> {
                val thenReturns = definitelyReturns(statement.thenBranch)
                val elseReturns = statement.elseBranch?.let { definitelyReturns(it) } ?: false
                thenReturns && elseReturns
            }
            else -> false
        }
    }
}