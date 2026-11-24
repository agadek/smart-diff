package io.github.agadek.smartdiff.engine.declarations.psi.javascript

import com.intellij.lang.Language
import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.javascript.psi.JSBlockStatement
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSExpressionStatement
import com.intellij.lang.javascript.psi.JSField
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSParameterList
import com.intellij.lang.javascript.psi.JSParameterListElement
import com.intellij.lang.javascript.psi.JSReturnStatement
import com.intellij.lang.javascript.psi.JSStatement
import com.intellij.lang.javascript.psi.JSThrowStatement
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.JSXmlLiteralExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnum
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnumField
import com.intellij.lang.javascript.psi.ecma6.TypeScriptFunctionSignature
import com.intellij.lang.javascript.psi.ecma6.TypeScriptInterface
import com.intellij.lang.javascript.psi.ecma6.TypeScriptModule
import com.intellij.lang.javascript.psi.ecma6.TypeScriptObjectType
import com.intellij.lang.javascript.psi.ecma6.TypeScriptPropertySignature
import com.intellij.lang.javascript.psi.ecma6.TypeScriptTypeAlias
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.agadek.smartdiff.engine.api.TextRange

/**
 * The only place that touches the JavaScript plugin's PSI (languages/16-javascript-typescript-details §1). Every entry
 * point the provider calls is wrapped so a [LinkageError] from the plugin becomes a plain exception the engine turns
 * into a failed result (platform/01 R11).
 */
internal object JavaScriptInterop {

    data class Decl(
        val kind: String,
        val name: String,
        val signatureKey: String? = null,
        val signatureDisplay: String? = null,
        val members: List<PsiElement> = emptyList(),
        val body: PsiElement? = null,
        val group: PsiElement? = null,
        val range: TextRange? = null,
    )

    /** The language ids of §1: JavaScript dialects first, then TypeScript ones. */
    val LANGUAGE_IDS = setOf("JavaScript", "ECMAScript 6", "JSX Harmony", "Flow JS", "TypeScript", "TypeScript JSX")

    private const val ES6 = "ECMAScript 6"
    private const val TS = "TypeScript"
    private const val TSX = "TypeScript JSX"

    /**
     * The dialect a side is parsed in (§7): from the file name's extension when it has one of R2's, otherwise from the
     * request's language id. Every JavaScript dialect, Flow and JSX included, is parsed as ECMAScript 6, which reads JSX.
     */
    fun dialectId(fileName: String?, languageId: String?): String {
        val extension = fileName?.substringAfterLast('.', "")?.lowercase()
        return when (extension) {
            "ts", "mts", "cts" -> TS
            "tsx" -> TSX
            "js", "mjs", "cjs", "jsx" -> ES6
            else -> when (languageId) {
                TS -> TS
                TSX -> TSX
                else -> ES6
            }
        }
    }

    fun parse(project: Project, text: CharSequence, fileName: String?, languageId: String?): PsiFile = link {
        val dialect = dialectId(fileName, languageId)
        val language = Language.findLanguageByID(dialect) ?: error("the $dialect language is not registered")
        val name = fileName ?: if (dialect == ES6) "file.js" else if (dialect == TS) "file.ts" else "file.tsx"
        PsiFileFactory.getInstance(project).createFileFromText(name, language, text, false, false)
            ?: error("PsiFileFactory returned null for $dialect")
    }

    fun isTypeScript(file: PsiFile): Boolean = file.language.id == TS || file.language.id == TSX

    fun topLevel(file: PsiFile): List<PsiElement> = link { items(file) }

    fun decl(element: PsiElement): Decl? = link {
        when {
            element is TypeScriptInterface -> named(element)?.let { name ->
                Decl("INTERFACE", name, members = interfaceMembers(element))
            }
            element is TypeScriptEnum -> named(element)?.let { name ->
                Decl("ENUM", name, members = enumMembers(element))
            }
            element is TypeScriptTypeAlias -> named(element)?.let { Decl("TYPE_ALIAS", it) }
            element is TypeScriptModule -> moduleDecl(element)
            element is TypeScriptEnumField -> named(element)?.let { Decl("ENUM_CONSTANT", it) }
            element is TypeScriptPropertySignature -> named(element)?.let { Decl("PROPERTY", it) }
            element is TypeScriptFunctionSignature -> named(element)?.let { name ->
                val (key, display) = signature(element)
                Decl("METHOD", name, key, display)
            }
            element is ES6ExportDefaultAssignment -> exportDefaultDecl(element)
            element is JSFunction -> functionDecl(element)
            element is JSClass -> named(element)?.let { Decl("CLASS", it, members = classMembers(element)) }
            element is JSVariable -> variableDecl(element)
            element.parent is JSClass -> fieldDecl(element)
            else -> null
        }
    }

    // §4: the items of a file, a namespace or a declare module that may be declarations, in source order.
    private fun items(container: PsiElement): List<PsiElement> = buildList {
        for (child in container.children) {
            when {
                child is JSVarStatement -> addAll(variables(child))
                // `namespace A.B { }` is a module inside an implicit module of the same range.
                child is TypeScriptModule -> add(innerModule(child))
                child is JSFunction || child is JSClass || child is ES6ExportDefaultAssignment -> add(child)
                else -> Unit
            }
        }
    }

    private fun innerModule(module: PsiElement): PsiElement =
        module.children.singleOrNull { it is TypeScriptModule && it.textRange == module.textRange }?.let(::innerModule) ?: module

    /** The named variables of a variable statement; a destructuring declaration has none (R8). */
    private fun variables(statement: JSVarStatement): List<PsiElement> =
        statement.children.filter { it is JSVariable }

    private fun classMembers(element: JSClass): List<PsiElement> =
        element.children.filter { it is JSFunction || (it !is PsiWhiteSpace && it.children.any { c -> c is JSField }) }

    private fun interfaceMembers(element: TypeScriptInterface): List<PsiElement> =
        PsiTreeUtil.getChildOfType(element, TypeScriptObjectType::class.java)?.children
            ?.filter { it is TypeScriptPropertySignature || it is TypeScriptFunctionSignature }.orEmpty()

    private fun enumMembers(element: TypeScriptEnum): List<PsiElement> =
        element.children.flatMap { child -> if (child is TypeScriptEnumField) listOf(child) else child.children.filter { it is TypeScriptEnumField } }

    private fun moduleDecl(module: TypeScriptModule): Decl? {
        var keyword: String? = null
        val name = StringBuilder()
        for (child in module.children(includeLeaves = true)) {
            val type = type(child)
            when {
                type == "JS:LBRACE" -> break
                type == "JS:NAMESPACE_KEYWORD" || type == "JS:MODULE_KEYWORD" -> keyword = child.text
                type == "JS:GLOBAL_KEYWORD" -> return Decl("MODULE", "global", members = items(module))
                keyword != null && child !is PsiWhiteSpace && child !is PsiComment -> name.append(child.text)
            }
        }
        if (name.isEmpty()) return null
        val kind = if (name.startsWith("\"") || name.startsWith("'")) "MODULE" else "NAMESPACE"
        return Decl(kind, name.toString(), members = items(module))
    }

    private fun exportDefaultDecl(export: ES6ExportDefaultAssignment): Decl? {
        val value = export.children.firstOrNull { it is JSFunction || it is JSClass } ?: return null
        return when (value) {
            is JSFunction -> {
                val (key, display) = signature(value)
                Decl("FUNCTION", value.name?.takeIf { it.isNotEmpty() } ?: "default", key, display, body = body(value))
            }
            is JSClass -> Decl("CLASS", value.name?.takeIf { it.isNotEmpty() } ?: "default", members = classMembers(value))
            else -> null
        }
    }

    private fun functionDecl(function: JSFunction): Decl? {
        val name = named(function) ?: return null
        val (key, display) = signature(function)
        if (function.parent !is JSClass) return Decl("FUNCTION", name, key, display, body = body(function))
        return when {
            function.isConstructor || name == "constructor" -> Decl("CONSTRUCTOR", name, key, display, body = body(function))
            function.isGetProperty -> Decl("METHOD", "get $name", key, display, body = body(function))
            function.isSetProperty -> Decl("METHOD", "set $name", key, display, body = body(function))
            else -> Decl("METHOD", name, key, display, body = body(function))
        }
    }

    private fun fieldDecl(statement: PsiElement): Decl? {
        val field = statement.children.firstOrNull { it is JSField } ?: return null
        return fieldName(field)?.let { Decl("FIELD", it) }
    }

    private fun fieldName(field: PsiElement): String? {
        val leaf = field.children(includeLeaves = true).firstOrNull { type(it) == "JS:PRIVATE_IDENTIFIER" }
        return leaf?.text ?: named(field)
    }

    /** R4: one declaration per name; a single one owns the whole statement, several split it (§4, Several names). */
    private fun variableDecl(variable: JSVariable): Decl? {
        val statement = variable.parent as? JSVarStatement ?: return null
        val name = named(variable) ?: return null
        val function = variable.initializer as? JSFunction
        val (key, display) = function?.let(::signature) ?: (null to null)
        val kind = if (function != null) "FUNCTION" else "VARIABLE"
        val siblings = variables(statement)
        if (siblings.size == 1) {
            val range = TextRange(statement.textRange.startOffset, statement.textRange.endOffset)
            return Decl(kind, name, key, display, body = function?.let(::body), range = range)
        }
        val index = siblings.indexOf(variable)
        val start = if (index == 0) statement.textRange.startOffset else variable.textRange.startOffset
        val end = if (index == siblings.lastIndex) statement.textRange.endOffset else variable.textRange.endOffset
        return Decl(kind, name, key, display, group = statement, range = TextRange(start, end))
    }

    private fun named(element: PsiElement): String? =
        (element as? com.intellij.psi.PsiNamedElement)?.name?.takeIf { it.isNotEmpty() }

    /** A function's body (extraction detection §5.1): its block, or an arrow function's expression. */
    private fun body(function: JSFunction): PsiElement? =
        function.block ?: function.children.lastOrNull { it is JSExpression && it !is JSParameterList }

    // §5: the parameters of a function, by type in TypeScript (R10) and by name in JavaScript (R11).
    private fun signature(function: JSFunction): Pair<String, String> {
        val list = function.parameterList ?: PsiTreeUtil.getChildOfType(function, JSParameterList::class.java)
            ?: return "" to ""
        val typeScript = isTypeScript(function.containingFile)
        val parts = list.children.filter { it is JSParameterListElement }.map { if (typeScript) typedParameter(it) else namedParameter(it) }
        return parts.joinToString(",") { it.joinToString("") } to parts.joinToString(", ") { display(it) }
    }

    /** One TypeScript parameter's tokens: `this: T`, `...T`, `T?` or `T`, with `any` for a missing type. */
    private fun typedParameter(parameter: PsiElement): List<String> {
        val children = parameter.children(includeLeaves = true).filter { it !is PsiWhiteSpace && it !is PsiComment }
        val colon = children.indexOfFirst { type(it) == "JS:COLON" }
        val type = if (colon >= 0) children.getOrNull(colon + 1)?.let(::tokens) else null
        val rest = children.any { type(it) == "JS:DOT_DOT_DOT" }
        val optional = children.any { type(it) == "JS:QUEST" }
        val isThis = children.any { type(it) == "JS:THIS_KEYWORD" }
        return buildList {
            if (isThis) addAll(listOf("this", ":"))
            if (rest) add("...")
            addAll(type ?: listOf("any"))
            if (optional) add("?")
        }
    }

    /** One JavaScript parameter's tokens: its name or destructuring pattern, without default values. */
    private fun namedParameter(parameter: PsiElement): List<String> {
        val children = parameter.children(includeLeaves = true).filter { it !is PsiWhiteSpace && it !is PsiComment }
        val stop = children.indexOfFirst { type(it) == "JS:EQ" }.let { if (it < 0) children.size else it }
        return children.take(stop).flatMap { withoutDefaults(it) }
    }

    private fun withoutDefaults(element: PsiElement): List<String> {
        if (element.firstChild == null) return if (element is PsiWhiteSpace || element is PsiComment) emptyList() else listOf(element.text)
        val children = element.children(includeLeaves = true).filter { it !is PsiWhiteSpace && it !is PsiComment }
        val result = mutableListOf<String>()
        var skipping = false
        for (child in children) {
            val t = type(child)
            when {
                t == "JS:EQ" -> skipping = true
                skipping && (t == "JS:COMMA" || t == "JS:RBRACE" || t == "JS:RBRACKET") -> { skipping = false; result += child.text }
                skipping -> Unit
                else -> result += withoutDefaults(child)
            }
        }
        return result
    }

    private fun tokens(element: PsiElement): List<String> = buildList {
        fun visit(e: PsiElement) {
            if (e is PsiWhiteSpace || e is PsiComment) return
            if (e.firstChild == null) {
                if (e.textLength > 0) add(e.text)
                return
            }
            var child = e.firstChild
            while (child != null) {
                visit(child)
                child = child.nextSibling
            }
        }
        visit(element)
    }

    /** The display form (§5): one space after `,` and `:` and around `|`, `&`, `=>` and `=`, and between words. */
    private fun display(tokens: List<String>): String {
        val out = StringBuilder()
        var previous: String? = null
        for (token in tokens) {
            if (previous != null) {
                val space = when {
                    token in AROUND || previous in AROUND -> true
                    previous == "," || previous == ":" -> true
                    isWord(previous) && isWord(token) -> true
                    else -> false
                }
                if (space) out.append(' ')
            }
            out.append(token)
            previous = token
        }
        return out.toString()
    }

    private val AROUND = setOf("|", "&", "=>", "=")

    private fun isWord(token: String): Boolean =
        token.isNotEmpty() && (token[0].isLetterOrDigit() || token[0] == '_' || token[0] == '$' || token[0] == '"' || token[0] == '\'')

    /**
     * Ranges whose whitespace is compared exactly (§6): the whitespace after a statement that ends without `;` where a
     * line break can change the parse (R14), after `return`, `throw`, `break`, `continue` and `yield` (R14), and between
     * the tags of a JSX element (R15).
     */
    fun significantWhitespace(file: PsiFile): List<TextRange> = link {
        val ranges = mutableListOf<TextRange>()
        PsiTreeUtil.processElements(file) { element ->
            when {
                element.firstChild == null && type(element) in RESTRICTED_KEYWORDS -> ranges += whitespaceAfter(element)
                element is JSXmlLiteralExpression -> ranges += jsxText(element)
                endsWithoutSemicolon(element) -> ranges += whitespaceAfter(element, ignoreBefore = ASI_SAFE_NEXT)
            }
            true
        }
        ranges.sortedBy { it.start }
    }

    private val RESTRICTED_KEYWORDS = setOf("JS:RETURN_KEYWORD", "JS:THROW_KEYWORD", "JS:BREAK_KEYWORD", "JS:CONTINUE_KEYWORD", "JS:YIELD_KEYWORD")

    /** A following token before which a line break never changes the parse: the statement ends there either way. */
    private val ASI_SAFE_NEXT = setOf("JS:SEMICOLON", "JS:RBRACE", "JS:RPAR", "JS:COMMA")

    /** Whether [element] is a statement, field or type alias that ends without `;` and could continue on the next line. */
    private fun endsWithoutSemicolon(element: PsiElement): Boolean {
        val field = element.parent is JSClass && element.children.any { it is JSField }
        if (element !is JSStatement && element !is TypeScriptTypeAlias && !field) return false
        if (element is JSBlockStatement) return false
        val last = lastCodeLeaf(element) ?: return false
        return when (type(last)) {
            "JS:SEMICOLON" -> false
            // A statement that ends with a block, such as `if` or a function, needs no terminator.
            "JS:RBRACE" -> field || element is JSVarStatement || element is JSExpressionStatement ||
                element is ES6ExportDefaultAssignment || element is JSReturnStatement || element is JSThrowStatement ||
                element is TypeScriptTypeAlias
            else -> element is TypeScriptTypeAlias || field ||
                (element !is JSClass && element !is JSFunction && element !is TypeScriptModule)
        }
    }

    private fun lastCodeLeaf(element: PsiElement): PsiElement? {
        var leaf = PsiTreeUtil.getDeepestLast(element)
        while (leaf !== element && (leaf is PsiWhiteSpace || leaf is PsiComment || leaf.textLength == 0)) {
            leaf = PsiTreeUtil.prevLeaf(leaf) ?: return null
            if (!element.textRange.contains(leaf.textRange)) return null
        }
        return leaf
    }

    /** The whitespace leaves between [element] and the next code token, unless that token is in [ignoreBefore]. */
    private fun whitespaceAfter(element: PsiElement, ignoreBefore: Set<String> = emptySet()): List<TextRange> {
        val found = mutableListOf<TextRange>()
        var leaf = PsiTreeUtil.nextLeaf(element)
        while (leaf != null && (leaf is PsiWhiteSpace || leaf is PsiComment || leaf.textLength == 0)) {
            if (leaf is PsiWhiteSpace) found += TextRange(leaf.textRange.startOffset, leaf.textRange.endOffset)
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        if (leaf == null || type(leaf) in ignoreBefore) return emptyList()
        return found
    }

    /** R15: the whitespace that is direct content of a JSX element, after its start tag and before its end tag. */
    private fun jsxText(element: PsiElement): List<TextRange> {
        val children = element.children(includeLeaves = true)
        val open = children.indexOfFirst { type(it) == "XML_TAG_END" }
        val close = children.indexOfLast { type(it) == "XML_END_TAG_START" }
        if (open < 0 || close < 0 || close <= open) return emptyList()
        return children.subList(open + 1, close).filterIsInstance<PsiWhiteSpace>()
            .map { TextRange(it.textRange.startOffset, it.textRange.endOffset) }
    }

    /** Whether [element] is a statement for code move detection (§8): local functions and classes included. */
    fun isStatement(element: PsiElement): Boolean = link { element is JSStatement || element is JSFunction || element is JSClass }

    fun isStatementBlock(element: PsiElement): Boolean = link { element is JSBlockStatement }

    private fun type(element: PsiElement): String = element.node?.elementType?.toString().orEmpty()

    private fun PsiElement.children(includeLeaves: Boolean): List<PsiElement> = buildList {
        var child = firstChild
        while (child != null) {
            if (includeLeaves || child.firstChild != null) add(child)
            child = child.nextSibling
        }
    }

    private inline fun <T> link(block: () -> T): T = try {
        block()
    } catch (e: LinkageError) {
        throw IllegalStateException("JavaScript plugin PSI unavailable: ${e.javaClass.simpleName}: ${e.message}", e)
    }
}
