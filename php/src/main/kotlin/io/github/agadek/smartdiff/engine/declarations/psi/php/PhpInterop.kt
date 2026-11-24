package io.github.agadek.smartdiff.engine.declarations.psi.php

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.Constant
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.GroupStatement
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpEnumCase
import com.jetbrains.php.lang.psi.elements.PhpNamespace
import com.jetbrains.php.lang.psi.elements.PhpTypeDeclaration
import io.github.agadek.smartdiff.engine.api.TextRange

/**
 * The only place that touches the PHP plugin's PSI (languages/19-php-details §1, §4). Every entry point the provider
 * calls is wrapped so a [LinkageError] from the plugin becomes a plain exception the engine turns into a failed result.
 */
internal object PhpInterop {

    data class Decl(
        val kind: String,
        val name: String,
        val signatureKey: String?,
        val signatureDisplay: String?,
        val members: List<PsiElement>,
        val body: PsiElement?,
        val group: PsiElement?,
        val range: TextRange,
    )

    private val PHP: Language
        get() = Language.findLanguageByID("PHP") ?: error("the PHP language is not registered")

    fun parse(project: Project, text: CharSequence): PsiFile = link {
        PsiFileFactory.getInstance(project).createFileFromText("file.php", PHP, text, false, false)
            ?: error("PsiFileFactory returned null for PHP")
    }

    /**
     * The file's namespaces, classes, functions and constants (§4): the statements of the file's group statements, with
     * the statements of a braced namespace without a name in its place, and each constant of a `const` statement.
     */
    fun topLevel(file: PsiFile): List<PsiElement> = link {
        buildList {
            for (child in children(file)) {
                if (child is GroupStatement) addAll(statements(child, unnamedNamespaces = true))
            }
        }
    }

    fun decl(element: PsiElement): Decl? = link {
        when (element) {
            is PhpNamespace -> namespaceDecl(element)
            is PhpClass -> classDecl(element)
            is Method -> functionDecl(element, if (element.name.equals("__construct", ignoreCase = true)) "CONSTRUCTOR" else "METHOD")
            is Function -> if (element.isClosure) null else functionDecl(element, "FUNCTION")
            is PhpEnumCase -> named(element.name)?.let { Decl("ENUM_CONSTANT", it, null, null, emptyList(), null, null, rangeOf(element)) }
            is Field -> named(element.name)?.let { part(element, if (element.isConstant) "CONSTANT" else "PROPERTY", it) }
            is Constant -> named(element.name)?.let { part(element, "CONSTANT", it) }
            else -> null
        }
    }

    /** Whether [element] is a code block whose children are statements (§10): a group statement inside a function. */
    fun isBlock(element: PsiElement): Boolean = link {
        element is GroupStatement && element.parent !is PsiFile && element.parent !is PhpNamespace
    }

    /** The declarations among [group]'s statements: constants one by one, and an unnamed namespace's statements. */
    private fun statements(group: GroupStatement, unnamedNamespaces: Boolean): List<PsiElement> = buildList {
        for (statement in children(group)) {
            when (statement) {
                is PhpNamespace -> if (unnamedNamespaces && namespaceName(statement).isEmpty()) {
                    statement.statements?.let { addAll(statements(it, unnamedNamespaces = false)) }
                } else {
                    add(statement)
                }
                is PhpClass, is Function -> add(statement)
                else -> addAll(children(statement).filterIsInstance<Constant>())
            }
        }
    }

    private fun namespaceDecl(namespace: PhpNamespace): Decl? {
        val name = namespaceName(namespace).ifEmpty { return null }
        val members = namespace.statements?.let { statements(it, unnamedNamespaces = false) }.orEmpty()
        return Decl("NAMESPACE", name, null, null, members, null, null, rangeOf(namespace))
    }

    /** The namespace's name as written, `App\Model`; empty for `namespace { … }`. */
    private fun namespaceName(namespace: PhpNamespace): String = namespace.fqn.removePrefix("\\")

    private fun classDecl(type: PhpClass): Decl? {
        if (type.isAnonymous) return null
        val name = named(type.name) ?: return null
        val kind = when {
            type.isInterface -> "INTERFACE"
            type.isTrait -> "TRAIT"
            type.isEnum -> "ENUM"
            else -> "CLASS"
        }
        return Decl(kind, name, null, null, members(type), null, null, rangeOf(type))
    }

    /** A class's methods, enum cases, and each property and constant of its field lists, in source order (§4). */
    private fun members(type: PhpClass): List<PsiElement> = buildList {
        for (child in children(type)) {
            when (child) {
                is Method, is PhpEnumCase -> add(child)
                else -> addAll(children(child).filterIsInstance<Field>())
            }
        }
    }

    private fun functionDecl(function: Function, kind: String): Decl? {
        val name = named(function.name) ?: return null
        val (key, display) = parameters(function.parameters)
        val body = PsiTreeUtil.getChildOfType(function, GroupStatement::class.java)
        return Decl(kind, name, key, display, emptyList(), body, null, rangeOf(function))
    }

    /**
     * A property or constant of a `const` or property statement, such as `public $a, $b;` (§4). As for Java fields, the
     * modifiers, type and keyword before the first part, and the comments above the statement, belong to the first; the
     * `,` after a part belongs to it, and the closing `;` to the last.
     */
    private fun part(element: PsiElement, kind: String, name: String): Decl {
        val group = element.parent
        val parts = children(group).filter { it is Field || it is Constant }
        val index = parts.indexOf(element)
        val whole = rangeOf(group)
        val own = element.textRange
        val start = if (index <= 0) whole.start else own.startOffset
        val end = if (index == parts.lastIndex) whole.end else comma(element) ?: own.endOffset
        return Decl(kind, name, null, null, emptyList(), null, group.takeIf { parts.size > 1 }, TextRange(start, end))
    }

    /** The end of the `,` after [element]. */
    private fun comma(element: PsiElement): Int? {
        var sibling = element.nextSibling
        while (sibling is PsiWhiteSpace || sibling is PsiComment) sibling = sibling.nextSibling
        return sibling?.takeIf { it.text == "," }?.textRange?.endOffset
    }

    /** A name the parser read; null for a missing one. */
    private fun named(name: String?): String? = name?.takeIf { it.isNotEmpty() && it != "%EMPTY%" }

    /**
     * Each parameter's type, `&` for one passed by reference, `...` for a variadic one, and its name, as a key without
     * whitespace and as a display with one space after the type (§5): `int $x, string ...$tags`.
     */
    private fun parameters(parameters: Array<Parameter>): Pair<String, String> {
        val displays = parameters.map { parameter ->
            val type = PsiTreeUtil.getChildOfType(parameter, PhpTypeDeclaration::class.java)?.let { tokens(it).joinToString("") }
            val marks = (if (parameter.isPassByRef) "&" else "") + (if (parameter.isVariadic) "..." else "")
            listOfNotNull(type, marks + "$" + parameter.name).joinToString(" ")
        }
        return displays.joinToString(",") { it.replace(" ", "") } to displays.joinToString(", ")
    }

    /**
     * [element]'s range extended back over the comments directly above it (§7): PHPDoc, `//`, `#` and `/* */` comments
     * that end on the line before, with no blank line in the run or after it. A comment after code on its line stays
     * outside. Attributes (`#[…]`) are already inside the element.
     */
    private fun rangeOf(element: PsiElement): TextRange {
        val text = element.containingFile.viewProvider.contents
        val range = element.textRange
        var start = range.startOffset
        var sibling = element.prevSibling
        while (sibling != null) {
            if (sibling is PsiComment) {
                val comment = sibling.textRange
                if (lineBreaks(text, comment.endOffset, start) > 1) break
                if (!startsLine(text, comment.startOffset) && !afterComment(sibling)) break
                start = comment.startOffset
            } else if (sibling !is PsiWhiteSpace) {
                break
            }
            sibling = sibling.prevSibling
        }
        return TextRange(start, range.endOffset)
    }

    private fun lineBreaks(text: CharSequence, from: Int, to: Int): Int = (from until to).count { text[it] == '\n' }

    /** Whether only whitespace is between [offset] and the start of its line. */
    private fun startsLine(text: CharSequence, offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0 && text[i] != '\n') {
            if (!text[i].isWhitespace()) return false
            i--
        }
        return true
    }

    /** Whether the code before [comment] on its line is another comment, as in `/* a */ /* b */`. */
    private fun afterComment(comment: PsiElement): Boolean {
        var sibling = comment.prevSibling
        while (sibling is PsiWhiteSpace && '\n' !in sibling.text) sibling = sibling.prevSibling
        return sibling is PsiComment
    }

    private fun children(element: PsiElement): List<PsiElement> = generateSequence(element.firstChild) { it.nextSibling }.toList()

    private fun tokens(element: PsiElement): List<String> = buildList {
        fun visit(e: PsiElement) {
            var child = e.firstChild
            while (child != null) {
                when {
                    child is PsiWhiteSpace || child is PsiComment -> {}
                    child.firstChild == null -> if (child.textLength > 0) add(child.text)
                    else -> visit(child)
                }
                child = child.nextSibling
            }
        }
        if (element.firstChild == null) add(element.text) else visit(element)
    }

    private inline fun <T> link(block: () -> T): T = try {
        block()
    } catch (e: LinkageError) {
        throw IllegalStateException("PHP plugin PSI unavailable: ${e.javaClass.simpleName}: ${e.message}", e)
    }
}
