package amber.compiler.type

import amber.compiler.CompilerConfig
import amber.compiler.ast.ArrayLiteralExpression
import amber.compiler.ast.AssignmentExpression
import amber.compiler.ast.AstNode
import amber.compiler.ast.BinaryExpression
import amber.compiler.ast.BlockStatement
import amber.compiler.ast.CallExpression
import amber.compiler.ast.CatchExpression
import amber.compiler.ast.EnumDeclaration
import amber.compiler.ast.ErrorNode
import amber.compiler.ast.Expression
import amber.compiler.ast.ExpressionStatement
import amber.compiler.ast.FunctionDeclaration
import amber.compiler.ast.IdentifierExpression
import amber.compiler.ast.IfStatement
import amber.compiler.ast.ImportStatement
import amber.compiler.ast.IndexAccessExpression
import amber.compiler.ast.IsExpression
import amber.compiler.ast.LiteralExpression
import amber.compiler.ast.MemberAccessExpression
import amber.compiler.ast.NamedArgumentExpression
import amber.compiler.ast.PanicExpression
import amber.compiler.ast.Program
import amber.compiler.ast.ReturnStatement
import amber.compiler.ast.Statement
import amber.compiler.ast.StringTemplateExpression
import amber.compiler.ast.StructDeclaration
import amber.compiler.ast.UnaryExpression
import amber.compiler.ast.VariableDeclaration
import amber.compiler.ast.WhileStatement
import amber.compiler.diagnostic.Diagnostic
import amber.compiler.diagnostic.DiagnosticSeverity
import amber.compiler.diagnostic.GenericDiagnostic
import amber.compiler.diagnostic.TypeError
import amber.compiler.semantic.ImportResolver
import amber.compiler.symbol.Symbol
import amber.compiler.symbol.SymbolTable
import amber.runtime.RuntimeProvider

class TypeChecker(
    val config: CompilerConfig,
    private val currentFilePath: String,
    private val runtimeProvider: RuntimeProvider,
    private val isMainFile: Boolean = true,
    private val namespace: String? = null
) {
    private var currentScope: SymbolTable
    internal val expressionTypes = mutableMapOf<Expression, Type>()
    internal val resolvedSymbols = mutableMapOf<Expression, Symbol>()
    internal val resolvedIsTypes = mutableMapOf<IsExpression, Type>()
    val errors = mutableListOf<Diagnostic>()
    private var isQuietMode = false
    private var currentFunctionReturnType: Type? = null
    private var currentFunctionPropagatedUnsafe = false
    private val expectedReturnTypes = mutableListOf<Type>()

    private val typeResolver = TypeResolver(::reportError)
    private val typeCompatibilityChecker = TypeCompatibilityChecker(::reportError)
    private val importResolver = ImportResolver(config)

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
        if (isQuietMode) return
        errors.add(
            TypeError(
                currentFilePath,
                node.line,
                node.column,
                message,
                node.length,
                suggestion = suggestion
            )
        )
    }

    private fun reportWarning(node: AstNode, message: String, suggestion: String? = null) {
        if (isQuietMode) return
        errors.add(
            GenericDiagnostic(
                filePath = currentFilePath,
                line = node.line,
                column = node.column,
                message = message,
                type = "Warning",
                length = node.length,
                suggestion = suggestion,
                severity = DiagnosticSeverity.WARNING
            )
        )
    }

    private fun defineSymbol(node: AstNode, symbol: Symbol) {
        val existing = currentScope.resolveLocal(symbol.name)
        if (existing != null) {
            if (existing.line == symbol.line && existing.column == symbol.column) {
                // Same symbol being re-defined (e.g. during pre-pass or multi-pass analysis)
                if (node is Expression) {
                    resolvedSymbols[node] = existing
                }
                return
            }
            reportError(node, "identifier '${symbol.name}' already exists in this scope")
        } else {
            currentScope.define(symbol)
        }
        if (node is Expression) {
            resolvedSymbols[node] = symbol
        }
    }

    private fun reportUnusedSymbols() {
        if (isStandardLibraryFile(currentFilePath)) return
        val isTopLevel = currentScope.parent == null || currentScope.parent?.parent == null
        currentScope.getUnusedSymbols().forEach { symbol ->
            if (symbol.line != -1 && !symbol.name.startsWith("_")) {
                if (!isMainFile && isTopLevel && symbol.type !is Type.Module) {
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
                        severity = DiagnosticSeverity.WARNING
                    )
                )
            }
        }
    }

    fun reportUnusedExports() {
        if (isMainFile || isStandardLibraryFile(currentFilePath)) return
        currentScope.getUnusedSymbols().forEach { symbol ->
            if (symbol.line != -1 && !symbol.name.startsWith("_") && symbol.type !is Type.Module) {
                errors.add(
                    GenericDiagnostic(
                        filePath = currentFilePath,
                        line = symbol.line,
                        column = symbol.column,
                        message = "unused export: '${symbol.name}'",
                        type = "Warning",
                        length = symbol.name.length,
                        suggestion = "remove it",
                        severity = DiagnosticSeverity.WARNING
                    )
                )
            }
        }
    }

    private fun calculateNamespace(importPath: String): String? {
        return when {
            importPath.startsWith("core:") -> "std." + importPath.removePrefix("core:")
                .replace(":", ".")

            importPath.startsWith("local:") -> importPath.removePrefix("local:").replace(":", ".")
            importPath.startsWith("pkg:") -> importPath.removePrefix("pkg:").replace(":", ".")
            else -> null
        }
    }

    fun hasErrors(): Boolean = errors.any { it.severity == DiagnosticSeverity.ERROR }

    private fun visitProgram(program: Program) {
        // Pass 1: Register top-level symbols (Enums, Structs, and Function signatures)
        program.statements.forEach {
            when (it) {
                is EnumDeclaration -> visitEnumDeclaration(it)
                is StructDeclaration -> preRegisterStruct(it)
                is FunctionDeclaration -> preRegisterFunction(it)

                else -> {}
            }
        }

        // Pass 1.5: Resolve struct fields and check defaults
        program.statements.filterIsInstance<StructDeclaration>().forEach {
            resolveStructFields(it)
        }

        // Pass 1.6: Cycle detection in struct defaults
        checkRecursiveDefaults(program)

        // Pass 2: Infer return types for all functions
        program.statements.filterIsInstance<FunctionDeclaration>()
            .forEach { inferFunctionReturnType(it) }

        // Pass 2.5: Detect variable promotions to 'any'
        detectVariablePromotions(program)

        // Pass 3: Full semantic analysis and error reporting
        program.statements.forEach {
            if (!isMainFile && !isDeclaration(it)) {
                reportError(
                    it,
                    "direct invoking is not allowed in non-main script files",
                    "move this logic into a function or the main script"
                )
            }
            if (it !is EnumDeclaration && it !is StructDeclaration) {
                visitStatement(it)
            }
        }
        reportUnusedSymbols()
    }

    private fun preRegisterStruct(declaration: StructDeclaration) {
        val structType = Type.Struct(declaration.name.name, emptyMap(), namespace)
        val symbol = Symbol(
            declaration.name.name,
            structType,
            isMutable = false,
            line = declaration.line,
            column = declaration.column,
            namespace = namespace
        )
        if (!currentScope.define(symbol)) {
            reportError(
                declaration.name,
                "identifier '${declaration.name.name}' already exists in this scope"
            )
        }
        resolvedSymbols[declaration.name] = symbol
    }

    private fun resolveStructFields(declaration: StructDeclaration) {
        val structSymbol = currentScope.resolve(declaration.name.name) ?: return
        val structType = structSymbol.type as Type.Struct
        val fields = mutableMapOf<String, Type.Struct.StructField>()

        declaration.fields.forEach { field ->
            val declaredType =
                field.typeAnnotation?.let { typeResolver.resolveType(it, field, currentScope) }
            val defaultType = field.defaultValue?.let { visitExpression(it) }

            val finalType = declaredType ?: defaultType ?: Type.Error
            if (finalType == Type.Error) {
                reportError(
                    field,
                    "cannot infer type for field '${field.name.name}'",
                    "add a type annotation or a default value"
                )
            }

            if (declaredType != null && defaultType != null) {
                typeCompatibilityChecker.checkAssignmentCompatibility(
                    declaredType,
                    defaultType,
                    field,
                    field.name.name
                )
            }

            fields[field.name.name] = Type.Struct.StructField(
                field.name.name,
                finalType,
                field.defaultValue != null,
                field.defaultValue
            )
        }

        structSymbol.type = structType.copy(fields = fields)
    }

    private fun checkRecursiveDefaults(program: Program) {
        val structs = program.statements.filterIsInstance<StructDeclaration>()
        val adj = mutableMapOf<String, MutableSet<String>>()

        structs.forEach { struct ->
            val deps = mutableSetOf<String>()
            struct.fields.forEach { field ->
                field.defaultValue?.let { findStructConstructionDependencies(it, deps) }
            }
            adj[struct.name.name] = deps
        }

        val visited = mutableSetOf<String>()
        val recStack = mutableSetOf<String>()
        val path = mutableListOf<String>()

        fun hasCycle(u: String, node: AstNode): Boolean {
            visited.add(u)
            recStack.add(u)
            path.add(u)

            adj[u]?.forEach { v ->
                if (v !in visited) {
                    if (hasCycle(v, node)) return true
                } else if (v in recStack) {
                    val cycleStart = path.indexOf(v)
                    val cyclePath = path.subList(cycleStart, path.size) + v
                    val cycleStr = cyclePath.joinToString("\n -> ") { name ->
                        val s = structs.find { it.name.name == name }
                        val fieldWithDefault = s?.fields?.find { field ->
                            field.defaultValue?.let {
                                val d = mutableSetOf<String>()
                                findStructConstructionDependencies(it, d)
                                d.contains(cyclePath.getOrNull(cyclePath.indexOf(name) + 1) ?: "")
                            } ?: false
                        }
                        if (fieldWithDefault != null) "${name}.${fieldWithDefault.name.name}" else name
                    }
                    reportError(
                        node,
                        "Recursive struct default initialization detected.\n\n$cycleStr"
                    )
                    return true
                }
            }

            recStack.remove(u)
            path.removeAt(path.size - 1)
            return false
        }

        structs.forEach { if (it.name.name !in visited) hasCycle(it.name.name, it) }
    }

    private fun findStructConstructionDependencies(expr: Expression, deps: MutableSet<String>) {
        when (expr) {
            is CallExpression -> {
                val callee = expr.callee
                if (callee is IdentifierExpression) {
                    val symbol = currentScope.resolve(callee.name)
                    if (symbol?.type is Type.Struct) {
                        deps.add(callee.name)
                    }
                }
                expr.arguments.forEach { findStructConstructionDependencies(it, deps) }
            }

            is NamedArgumentExpression -> findStructConstructionDependencies(expr.value, deps)
            else -> expr.children.forEach {
                if (it is Expression) findStructConstructionDependencies(
                    it,
                    deps
                )
            }
        }
    }

    private fun inferFunctionReturnType(function: FunctionDeclaration) {
        if (function.isIntrinsic || function.returnTypeAnnotation != null) return

        val functionSymbol = currentScope.resolve(function.name.name) ?: return
        val paramTypes = (functionSymbol.type as Type.Function).parameterTypes

        val inferenceScope = currentScope.enterScope()
        function.parameters.forEachIndexed { index, param ->
            inferenceScope.define(
                Symbol(
                    param.name.name,
                    paramTypes[index],
                    isMutable = true,
                    isParameter = true,
                    isInitialized = true,
                    line = param.name.line,
                    column = param.name.column
                )
            )
        }

        val originalScope = currentScope
        currentScope = inferenceScope

        val wasQuiet = isQuietMode
        isQuietMode = true

        // Quietly visit the body to infer return type
        val inferredType = function.body?.let { body ->
            when (val lastStmt = body.statements.lastOrNull()) {
                is ExpressionStatement -> visitExpression(lastStmt.expression, allowUnsafe = true)

                is ReturnStatement -> lastStmt.value?.let {
                    visitExpression(
                        expression = it,
                        allowUnsafe = true
                    )
                } ?: Type.Unit

                else ->
                    Type.Unit
            }
        } ?: Type.Unit

        isQuietMode = wasQuiet
        currentScope = originalScope
        functionSymbol.type = (functionSymbol.type as Type.Function).copy(returnType = inferredType)
    }

    private fun preRegisterFunction(function: FunctionDeclaration) {
        val paramTypes = function.parameters.map { param ->
            param.typeAnnotation?.let { typeResolver.resolveType(it, param, currentScope) }
                ?: Type.Any
        }
        val hasDefaultValues = function.parameters.map { it.defaultValue != null }
        val declaredReturnType = function.returnTypeAnnotation?.let {
            typeResolver.resolveType(it, function, currentScope)
        } ?: Type.Unit

        val functionType = Type.Function(paramTypes, hasDefaultValues, declaredReturnType)
        val functionSymbol = Symbol(
            function.name.name,
            functionType,
            isIntrinsic = function.isIntrinsic,
            line = function.name.line,
            column = function.name.column,
            namespace = namespace
        )
        currentScope.define(functionSymbol)
    }

    private fun isDeclaration(statement: Statement): Boolean {
        return statement is VariableDeclaration ||
                statement is FunctionDeclaration ||
                statement is ImportStatement ||
                statement is StructDeclaration ||
                statement is EnumDeclaration
    }

    private fun visitStatement(statement: Statement, skipUnusedWarning: Boolean = false) {
        when (statement) {
            is VariableDeclaration -> visitVariableDeclaration(statement)
            is EnumDeclaration -> visitEnumDeclaration(statement)
            is StructDeclaration -> { /* Already handled in Pass 1 and 1.5 */
            }

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
            is NamedArgumentExpression -> visitExpression(expression.value)
            is AssignmentExpression -> visitAssignmentExpression(expression)
            is MemberAccessExpression -> visitMemberAccessExpression(expression)
            is StringTemplateExpression -> visitStringTemplateExpression(expression)
            is ArrayLiteralExpression -> visitArrayLiteralExpression(expression)
            is IndexAccessExpression -> visitIndexAccessExpression(expression)
            is PanicExpression -> visitPanicExpression(expression)
            is CatchExpression -> visitCatchExpression(expression)
            is ErrorNode -> Type.Error
        }
        expressionTypes[expression] = type

        val isPropagating = currentFunctionReturnType is Type.Unsafe
        if (!allowUnsafe && type is Type.Unsafe) {
            if (isPropagating) {
                currentFunctionPropagatedUnsafe = true
            } else {
                val suggestion = if (currentFunctionReturnType != null) {
                    "handle it with 'or catch' or 'or panic', or annotate the current function as unsafe '!'"
                } else {
                    "handle it with 'or catch' or 'or panic'"
                }
                reportError(expression, "unhandled unsafe call", suggestion)
            }
        }
        return type
    }

    private fun visitStructConstruction(call: CallExpression, structType: Type.Struct): Type {
        val fields = structType.fields
        val suppliedFields = mutableSetOf<String>()
        var namedStarted = false

        call.arguments.forEachIndexed { index, arg ->
            if (arg is NamedArgumentExpression) {
                namedStarted = true

                val fieldName = arg.name.name
                if (fieldName in suppliedFields) {
                    reportError(
                        arg,
                        "duplicate value supplied for field '$fieldName'",
                        "remove the duplicate field value"
                    )
                }

                suppliedFields.add(fieldName)

                val field = fields[fieldName]
                if (field == null) {
                    reportError(
                        arg.name,
                        "struct '${structType.name}' has no field '$fieldName'",
                        "check for typos or ensure the field exists"
                    )
                } else {
                    val valueType = visitExpression(arg.value)
                    typeCompatibilityChecker.checkAssignmentCompatibility(
                        field.type,
                        valueType,
                        arg,
                        fieldName
                    )
                }
            } else {
                if (namedStarted) {
                    reportError(
                        arg,
                        "positional arguments cannot appear after named arguments",
                        "move positional arguments before named arguments"
                    )
                }

                val field = fields.values.toList().getOrNull(index)
                if (field == null) {
                    reportError(
                        arg,
                        "too many positional arguments for struct construction",
                        "remove the extra argument"
                    )
                } else {
                    if (field.name in suppliedFields) {
                        reportError(
                            arg,
                            "duplicate value supplied for field '${field.name}'",
                            "remove the duplicate field value"
                        )
                    }

                    suppliedFields.add(field.name)

                    val valueType = visitExpression(arg)
                    typeCompatibilityChecker.checkAssignmentCompatibility(
                        field.type,
                        valueType,
                        arg,
                        field.name
                    )
                }
            }
        }

        fields.values.forEach { field ->
            if (field.name !in suppliedFields && !field.hasDefault) {
                reportError(
                    call,
                    "missing required field '${field.name}'",
                    "provide a value for this field or add a default value"
                )
            }
        }

        return structType
    }

    private fun isStandardLibraryFile(filePath: String): Boolean {
        return filePath.contains("/lib/std/") || filePath.contains("lib/std/")
    }

    private fun visitVariableDeclaration(declaration: VariableDeclaration) {
        if (declaration.isIntrinsic && !isStandardLibraryFile(currentFilePath)) {
            reportError(
                declaration,
                "intrinsic variables are only allowed in the core standard library"
            )
        }
        val declaredType = declaration.typeAnnotation?.let {
            typeResolver.resolveType(
                it,
                declaration,
                currentScope
            )
        }
        var initializerType = declaration.initializer?.let {
            visitExpression(
                it,
                allowUnsafe = declaredType is Type.Unsafe
            )
        }

        if (declaredType is Type.Unsafe && initializerType != null && initializerType !is Type.Unsafe && initializerType != Type.Error) {
            reportWarning(
                declaration,
                "useless '!': initializer is already safe (type $initializerType)"
            )
        }

        if (initializerType is Type.List && (declaration.isMutable || declaration.initializer is ArrayLiteralExpression)) {
            initializerType = Type.ArrayList(initializerType.elementType)
        }

        val finalType: Type

        if (declaredType != null) {
            finalType = if (declaration.isMutable && declaredType is Type.List) {
                Type.ArrayList(declaredType.elementType)
            } else {
                declaredType
            }
            if (initializerType != null && initializerType != Type.Unit) {
                typeCompatibilityChecker.checkAssignmentCompatibility(
                    finalType,
                    initializerType,
                    declaration,
                    declaration.name.name
                )
            }
        } else {
            val existing = currentScope.resolveLocal(declaration.name.name)
            finalType = if (existing != null && existing.line == declaration.name.line && existing.column == declaration.name.column && existing.type == Type.Any && !existing.isExplicitlyTyped) {
                Type.Any
            } else {
                initializerType ?: Type.Error
            }
            if (finalType == Type.Error) {
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
            isInitialized = declaration.initializer != null || declaration.isIntrinsic,
            isExplicitlyTyped = declaration.typeAnnotation != null,
            line = declaration.name.line,
            column = declaration.name.column,
            namespace = namespace
        )
        defineSymbol(declaration.name, symbol)
    }

    private fun visitExpressionStatement(
        statement: ExpressionStatement,
        skipUnusedWarning: Boolean = false
    ) {
        val type = visitExpression(statement.expression)
        if (!skipUnusedWarning && statement.expression !is AssignmentExpression && type != Type.Unit && type != Type.Error && type !is Type.Unsafe && type != Type.Any && type != Type.Nothing) {
            errors.add(
                GenericDiagnostic(
                    filePath = currentFilePath,
                    line = statement.line,
                    column = statement.column,
                    message = "unused return value",
                    type = "Warning",
                    suggestion = "prefix with '_' if this is intentional",
                    severity = DiagnosticSeverity.WARNING
                )
            )
        }
    }

    private fun visitLiteralExpression(literal: LiteralExpression): Type = when (literal.value) {
        null -> Type.Unit
        is Int, is Double, is Float, is Long, is Short, is Byte -> Type.Number
        is String -> Type.String
        is Boolean -> Type.Boolean
        is Char -> Type.Char
        else -> {
            reportError(
                literal,
                "unknown literal type: ${literal.value::class.simpleName}",
                "check if this literal is supported"
            )
            Type.Error
        }
    }

    private fun visitStringTemplateExpression(expression: StringTemplateExpression): Type {
        expression.segments.forEach { visitExpression(it) }
        return Type.String
    }

    private fun visitIdentifierExpression(identifier: IdentifierExpression): Type {
        val symbol = currentScope.resolve(identifier.name)
        if (symbol != null) {
            if (symbol.name == "to_string" && symbol.isIntrinsic && !isStandardLibraryFile(
                    currentFilePath
                ) && identifier.name == "to_string" && !identifier.isSynthetic
            ) {
                reportError(
                    identifier,
                    "direct use of 'to_string' is not allowed",
                    "import 'core:str' and use 'str.to_string(value)' instead"
                )
            }

            if (!symbol.isInitialized && !symbol.isIntrinsic && symbol.type !is Type.Function && symbol.type !is Type.Module && symbol.type !is Type.EnumNamespace && symbol.type !is Type.Enum && symbol.type !is Type.Struct) {
                reportError(
                    identifier,
                    "variable '${identifier.name}' have not been initialized"
                )
            }

            resolvedSymbols[identifier] = symbol
            return symbol.type
        } else {
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
            return Type.Error
        }
    }

    private fun visitBinaryExpression(binary: BinaryExpression): Type {
        val leftType = visitExpression(binary.left)
        val rightType = visitExpression(binary.right)

        if (binary.operator == "+") {
            if (leftType is Type.List || leftType is Type.ArrayList) {
                if (!isExpressionMutable(binary.left)) {
                    reportError(
                        binary.left,
                        "cannot use '+' to append to an immutable list",
                        "declare the list with 'var' instead of 'val'"
                    )
                }
                markAsMutated(binary.left)

                val elementType = when (leftType) {
                    is Type.List -> leftType.elementType
                    is Type.ArrayList -> leftType.elementType
                }

                if (rightType != elementType && elementType != Type.Any && rightType != Type.Error) {
                    reportError(
                        binary,
                        "cannot add element of type '$rightType' to a collection of type '$leftType'",
                        "ensure the item matches the collection's element type"
                    )
                    return Type.Error
                }
                return leftType
            }
        }

        return typeCompatibilityChecker.checkBinaryOperatorCompatibility(
            leftType,
            binary.operator,
            rightType,
            binary
        )
    }

    private fun visitUnaryExpression(unary: UnaryExpression): Type {
        val operandType = visitExpression(unary.operand)
        return typeCompatibilityChecker.checkUnaryOperatorCompatibility(
            unary.operator,
            operandType,
            unary
        )
    }

    private fun visitIsExpression(isExpr: IsExpression): Type {
        visitExpression(isExpr.left)
        val targetType = typeResolver.resolveType(isExpr.typeName, isExpr, currentScope)
        resolvedIsTypes[isExpr] = targetType
        return Type.Boolean
    }

    private fun visitCallExpression(call: CallExpression): Type {
        val functionSymbol = resolveCalledFunctionSymbol(call.callee)
        if (functionSymbol != null) {
            resolvedSymbols[call.callee] = functionSymbol
        }
        var calleeType = visitExpression(call.callee)

        if (calleeType is Type.Struct) {
            return visitStructConstruction(call, calleeType)
        }

        if (calleeType !is Type.Function) {
            reportError(
                call.callee,
                "expression is not a function: $calleeType",
                "ensure you are calling a function"
            )
            return Type.Error
        }

        val argTypes = call.arguments.map { visitExpression(it) }
        val isArgMutable = call.arguments.map { isExpressionMutable(it) }

        if (isValidArgumentCount(calleeType, argTypes.size)) {
            calleeType = inferFunctionTypeFromFirstCall(functionSymbol, calleeType, argTypes)
        }

        // Mark arguments as mutated if the callee mutates them
        call.arguments.forEachIndexed { i, arg ->
            if (calleeType.isParameterMutated.getOrElse(i) { false }) {
                markAsMutated(arg)
            }
        }

        typeCompatibilityChecker.checkFunctionCallArguments(
            call,
            calleeType,
            argTypes,
            isArgMutable
        )

        return calleeType.returnType
    }

    private fun markAsMutated(expression: Expression) {
        when (expression) {
            is IdentifierExpression -> {
                val symbol = currentScope.resolve(expression.name)
                symbol?.isMutated = true
            }

            is IndexAccessExpression -> markAsMutated(expression.target)
            is MemberAccessExpression -> markAsMutated(expression.target)
            is ArrayLiteralExpression -> {
                expression.elements.forEach { markAsMutated(it) }
            }

            else -> {}
        }
    }

    private fun isExpressionMutable(expression: Expression): Boolean {
        return when (expression) {
            is IdentifierExpression -> {
                val symbol = currentScope.resolve(expression.name)
                symbol?.isMutable ?: false
            }

            is IndexAccessExpression -> isExpressionMutable(expression.target)
            is MemberAccessExpression -> isExpressionMutable(expression.target)
            else -> false
        }
    }

    private fun resolveCalledFunctionSymbol(callee: Expression): Symbol? {
        return when (callee) {
            is IdentifierExpression -> currentScope.resolve(callee.name)
            else -> null
        }
    }

    private fun isValidArgumentCount(functionType: Type.Function, argCount: Int): Boolean {
        val minArgs = functionType.parameterTypes.zip(functionType.hasDefaultValues)
            .count { (_, hasDefault) -> !hasDefault }
        val maxArgs = functionType.parameterTypes.size
        return argCount in minArgs..maxArgs
    }

    private fun inferFunctionTypeFromFirstCall(
        functionSymbol: Symbol?,
        functionType: Type.Function,
        argTypes: List<Type>
    ): Type.Function {
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
            if (expectedType == Type.Any && argType != Type.Error && argType != Type.Any) {
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
        val target = assignment.target
        val targetType = visitExpression(target)

        if (target is IdentifierExpression) {
            val targetSymbol = currentScope.resolve(target.name)
            if (targetSymbol != null) {
                resolvedSymbols[target] = targetSymbol
                targetSymbol.isMutated = true
                if (!targetSymbol.isMutable) {
                    reportError(
                        assignment,
                        "cannot assign to immutable variable '${target.name}'",
                        "use 'var' instead of 'val' to allow reassignment"
                    )
                }
                targetSymbol.isInitialized = true
            }
        } else {
            if (!isExpressionMutable(target)) {
                val targetName = when (target) {
                    is MemberAccessExpression -> "'${target.member.name}'"
                    else -> "expression"
                }
                val container = if (target is MemberAccessExpression) {
                    val containerName =
                        (target.target as? IdentifierExpression)?.name ?: "container"
                    " because '$containerName' is immutable"
                } else ""

                reportError(
                    assignment,
                    "cannot modify field $targetName$container",
                    "declare the root variable with 'var' instead of 'val'"
                )
            }
            markAsMutated(target)
        }

        val updateOperator = detectIncDecOperator(assignment)
        if (
            updateOperator != null &&
            targetType != Type.Number &&
            targetType != Type.Any &&
            targetType != Type.Error
        ) {
            reportError(
                assignment,
                "operator '$updateOperator' requires a number, but got $targetType",
                "use a number type for increment/decrement"
            )
        }

        val valueType = visitExpression(assignment.value)
        typeCompatibilityChecker.checkAssignmentCompatibility(
            targetType,
            valueType,
            assignment,
            "target"
        )
        return Type.Unit
    }

    private fun detectIncDecOperator(assignment: AssignmentExpression): String? {
        val binary = assignment.value as? BinaryExpression ?: return null
        val left = binary.left
        val oneLiteral = binary.right as? LiteralExpression ?: return null

        if (left != assignment.target) return null
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

        if (targetType is Type.EnumNamespace) {
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
                return Type.Error
            }
        }

        if (targetType is Type.Struct) {
            val field = targetType.fields[memberName]
            if (field != null) {
                return field.type
            } else {
                reportError(
                    memberAccess.member,
                    "struct '${targetType.name}' has no field '$memberName'",
                    "check for typos or ensure the field exists"
                )
                return Type.Error
            }
        }

        if (targetType is Type.Module) {
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
                return Type.Error
            }
        } else {
            reportError(
                memberAccess.target,
                "member access only allowed on modules. found type: $targetType",
                "ensure the left side is an imported module"
            )
            return Type.Error
        }
    }

    private fun visitArrayLiteralExpression(expression: ArrayLiteralExpression): Type {
        if (expression.elements.isEmpty()) {
            return Type.List(Type.Any)
        }
        val elementTypes = expression.elements.map { visitExpression(it) }
        val firstType = elementTypes.first()
        val allSame = elementTypes.all { it == firstType }
        val elementType = if (allSame) firstType else Type.Any
        return Type.List(elementType)
    }

    private fun visitIndexAccessExpression(expression: IndexAccessExpression): Type {
        val targetType = visitExpression(expression.target)
        val indexType = visitExpression(expression.index)

        if (targetType == Type.Error || indexType == Type.Error) return Type.Error

        if (indexType != Type.Number) {
            reportError(expression.index, "index must be a number, but got $indexType", null)
        }

        return when (targetType) {
            is Type.List -> targetType.elementType
            is Type.ArrayList -> targetType.elementType
            Type.String -> Type.Char
            Type.Any -> Type.Any
            else -> {
                reportError(expression.target, "cannot index into type $targetType", null)
                Type.Error
            }
        }
    }

    private fun visitPanicExpression(panic: PanicExpression): Type {
        panic.message?.let {
            val messageType = visitExpression(it)
            if (messageType != Type.String && messageType != Type.Error) {
                reportError(
                    it,
                    "panic message must be a string, but got $messageType",
                    "ensure the expression evaluates to a string"
                )
            }
        }
        return Type.Nothing
    }

    private fun visitCatchExpression(catch: CatchExpression): Type {
        val targetType = visitExpression(catch.target, allowUnsafe = true)
        if (targetType !is Type.Unsafe && targetType != Type.Error) {
            reportWarning(
                catch.target,
                "useless 'or catch': expression is already safe (type $targetType)"
            )
        }

        val innerType = if (targetType is Type.Unsafe) targetType.innerType else targetType

        currentScope = currentScope.enterScope()
        val errorVarType =
            catch.errorVarType?.let { typeResolver.resolveType(it, catch, currentScope) }
                ?: Type.String
        if (errorVarType != Type.String && errorVarType != Type.Error && errorVarType != Type.Any) {
            reportError(catch, "catch variable must be of type string, but got $errorVarType")
        }
        defineSymbol(
            catch,
            Symbol(catch.errorVarName, errorVarType, line = catch.line, column = catch.column)
        )

        visitBlockStatement(
            catch.body,
            isExpression = true,
            expectedReturnType = innerType,
            contextName = "catch block"
        )

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
                if (!isQuietMode) {
                    errors.add(
                        GenericDiagnostic(
                            filePath = currentFilePath,
                            line = statement.line,
                            column = statement.column,
                            message = "redundant return keyword",
                            type = "Warning",
                            suggestion = "remove the 'return' keyword as it's the only expression in this block",
                            severity = DiagnosticSeverity.WARNING,
                            length = 6
                        )
                    )
                }
            }

            visitStatement(statement, skipUnusedWarning = isExpression && isLast)

            if (block.statements.size == 1 && isExpression && isLast && statement is ExpressionStatement && expectedReturnType != null && expectedReturnType != Type.Unit) {
                val actualType = expressionTypes[statement.expression] ?: Type.Unit
                typeCompatibilityChecker.checkReturnType(actualType, expectedReturnType, statement)
            }
        }

        if (block.statements.size > 1 && isExpression && expectedReturnType != null && expectedReturnType != Type.Unit && expectedReturnType != Type.Error) {
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
        typeCompatibilityChecker.checkConditionType(
            conditionType,
            whileStmt.condition,
            "while statement"
        )

        visitBlockStatement(whileStmt.body)
    }

    private fun visitEnumDeclaration(declaration: EnumDeclaration) {
        val enumType =
            Type.Enum(declaration.name.name, declaration.variants.map { it.name }, namespace)
        val namespaceType = Type.EnumNamespace(enumType)
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
                reportError(
                    function,
                    "intrinsic functions are only allowed in the core standard library"
                )
            }
            if (function.body != null) {
                reportError(function, "intrinsic functions cannot have a body")
            }
        }

        val paramTypes = mutableListOf<Type>()
        val hasDefaultValues = mutableListOf<Boolean>()
        val inferableParameterIndices = mutableSetOf<Int>()

        function.parameters.forEachIndexed { index, param ->
            val paramDeclaredType =
                param.typeAnnotation?.let { typeResolver.resolveType(it, param, currentScope) }
            val tempScopeForDefaultValue = currentScope.enterScope()
            val originalScope = currentScope
            currentScope = tempScopeForDefaultValue
            val paramDefaultValueType = param.defaultValue?.let { visitExpression(it) }
            currentScope = originalScope

            val finalParamType = if (paramDeclaredType != null) {
                if (paramDefaultValueType != null && paramDefaultValueType != Type.Unit) {
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
                paramDefaultValueType ?: Type.Any
            }

            if (finalParamType == Type.Error) {
                reportError(
                    param,
                    "cannot infer type for parameter '${param.name.name}'",
                    "add a type annotation or a default value"
                )
            }
            paramTypes.add(finalParamType)
            hasDefaultValues.add(param.defaultValue != null)
        }

        val isImplicitReturn = function.returnTypeAnnotation == null &&
                function.body != null &&
                function.body.statements.size == 1 &&
                (function.body.statements[0] is ExpressionStatement || function.body.statements[0] is ReturnStatement)

        var inferredReturnType: Type? = null
        if (isImplicitReturn) {
            val inferenceScope = currentScope.enterScope()
            function.parameters.forEachIndexed { index, param ->
                inferenceScope.define(
                    Symbol(
                        param.name.name,
                        paramTypes[index],
                        isMutable = true,
                        isParameter = true,
                        isInitialized = true,
                        line = param.name.line,
                        column = param.name.column
                    )
                )
            }
            val originalScope = currentScope
            currentScope = inferenceScope
            val lastStmt = function.body.statements[0]
            val firstExpr =
                if (lastStmt is ExpressionStatement) lastStmt.expression else (lastStmt as ReturnStatement).value
            inferredReturnType =
                firstExpr?.let { visitExpression(it, allowUnsafe = true) } ?: Type.Unit
            currentScope = originalScope
        }

        val declaredReturnType = function.returnTypeAnnotation?.let {
            typeResolver.resolveType(it, function, currentScope)
        } ?: inferredReturnType ?: Type.Unit

        val previousFunctionReturnType = currentFunctionReturnType
        currentFunctionReturnType = declaredReturnType

        val previousPropagated = currentFunctionPropagatedUnsafe
        currentFunctionPropagatedUnsafe = false

        var functionType = Type.Function(paramTypes, hasDefaultValues, declaredReturnType)

        if (function.isIntrinsic) {
            val qualifiedName =
                if (namespace != null) "$namespace.${function.name.name}" else function.name.name
            val intrinsic = runtimeProvider.getAllIntrinsicSymbols()[qualifiedName]
            if (intrinsic != null && intrinsic.type is Type.Function) {
                functionType = functionType.copy(
                    isParameterMutated = (intrinsic.type as Type.Function).isParameterMutated
                )
            }
        }

        val existingSymbol = currentScope.resolve(function.name.name)
        val functionSymbol: Symbol
        if (existingSymbol != null && existingSymbol.line == function.name.line && existingSymbol.column == function.name.column) {
            existingSymbol.type = functionType
            functionSymbol = existingSymbol
        } else {
            functionSymbol = Symbol(
                function.name.name,
                functionType,
                inferableParameterIndices = inferableParameterIndices,
                isIntrinsic = function.isIntrinsic,
                line = function.name.line,
                column = function.name.column,
                namespace = namespace
            )
            defineSymbol(function.name, functionSymbol)
        }
        resolvedSymbols[function.name] = functionSymbol

        val paramSymbols = mutableListOf<Symbol>()
        currentScope = currentScope.enterScope()
        function.parameters.forEachIndexed { index, param ->
            val paramType = paramTypes[index]
            val symbol = Symbol(
                param.name.name,
                paramType,
                isMutable = true, // Allow modification inside function
                isParameter = true,
                isInitialized = true,
                line = param.name.line,
                column = param.name.column
            )
            paramSymbols.add(symbol)
            if (function.isIntrinsic) symbol.isUsed = true
            defineSymbol(param.name, symbol)
        }

        function.body?.let {
            visitBlockStatement(
                it,
                isExpression = true,
                expectedReturnType = declaredReturnType,
                contextName = "function '${function.name.name}'"
            )
            if (declaredReturnType != Type.Unit && !definitelyReturns(it)) {
                reportError(
                    function.name,
                    "function '${function.name.name}' must return a value of type $declaredReturnType",
                    "add a return statement at the end of the function or in all execution paths"
                )
            }
        }

        // Update function type with mutation info inferred from body
        if (!function.isIntrinsic) {
            val mutationInfo = paramSymbols.map { it.isMutated }
            functionSymbol.type =
                (functionSymbol.type as Type.Function).copy(isParameterMutated = mutationInfo)
        }

        if (declaredReturnType is Type.Unsafe && !currentFunctionPropagatedUnsafe && !function.isIntrinsic) {
            reportWarning(function.name, "useless '!': function is safe and never returns an error")
        }

        reportUnusedSymbols()
        currentScope = currentScope.exitScope()!!
        currentFunctionReturnType = previousFunctionReturnType
        currentFunctionPropagatedUnsafe = previousPropagated
    }

    private fun visitReturnStatement(returnStmt: ReturnStatement) {
        val returnedType = returnStmt.value?.let { visitExpression(it) } ?: Type.Unit

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
        val moduleIdentifierName =
            alias ?: importStmt.path.split(":").last().substringBeforeLast(".amb")

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

            val importedFilePath =
                importResolver.resolveAbsolutePath(importStmt.path, currentFilePath)
            val importedNamespace = calculateNamespace(importStmt.path)

            val importedTypeChecker = TypeChecker(
                config,
                importedFilePath,
                runtimeProvider,
                isMainFile = false,
                namespace = importedNamespace
            )

            val (_, _, importedErrors) = importedTypeChecker.check(importedProgram)

            if (importedErrors.isNotEmpty()) {
                errors.addAll(importedErrors)
            }

            val symbolsToImport = importedTypeChecker.currentScope.getTopLevelSymbols()
            val moduleType = Type.Module(symbolsToImport.associateBy { it.name })

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

        } catch (e: Exception) {
            reportError(importStmt, "error processing import: ${e.message}")
        }
    }

    private fun detectVariablePromotions(program: Program) {
        val wasQuiet = isQuietMode
        isQuietMode = true

        // We use the same scope as the main pass will use initially
        fun traverse(stmt: Statement) {
            when (stmt) {
                is VariableDeclaration -> {
                    val declaredType =
                        stmt.typeAnnotation?.let { typeResolver.resolveType(it, stmt, currentScope) }
                    val initializerType = stmt.initializer?.let { visitExpression(it) } ?: Type.Any
                    val finalType = declaredType ?: initializerType
                    val symbol = Symbol(
                        stmt.name.name,
                        finalType,
                        isMutable = stmt.isMutable,
                        isInitialized = stmt.initializer != null,
                        isExplicitlyTyped = stmt.typeAnnotation != null,
                        line = stmt.name.line,
                        column = stmt.name.column
                    )
                    defineSymbol(stmt.name, symbol)
                }

                is ExpressionStatement -> {
                    val expr = stmt.expression
                    if (expr is AssignmentExpression) {
                        val target = expr.target
                        if (target is IdentifierExpression) {
                            val symbol = currentScope.resolve(target.name)
                            if (symbol != null && symbol.isMutable && !symbol.isExplicitlyTyped) {
                                val valueType = visitExpression(expr.value)
                                if (!typeCompatibilityChecker.isCompatible(symbol.type, valueType)) {
                                    symbol.type = Type.Any
                                }
                            }
                        }
                    }
                }

                is BlockStatement -> {
                    val outerScope = currentScope
                    currentScope = currentScope.enterScope()
                    stmt.statements.forEach { traverse(it) }
                    currentScope = outerScope
                }

                is IfStatement -> {
                    traverse(stmt.thenBranch)
                    stmt.elseBranch?.let { traverse(it) }
                }

                is WhileStatement -> traverse(stmt.body)
                else -> {}
            }
        }

        program.statements.forEach { traverse(it) }

        // We DON'T want to keep the symbols defined here if they are not top-level,
        // but for top-level symbols in the main file, they are in the global scope.
        // Actually, Pass 3 will redefine them (which we allowed in defineSymbol).
        
        isQuietMode = wasQuiet
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
