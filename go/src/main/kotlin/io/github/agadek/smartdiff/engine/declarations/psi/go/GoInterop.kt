package io.github.agadek.smartdiff.engine.declarations.psi.go

import com.goide.psi.GoBlock
import com.goide.psi.GoConstDeclaration
import com.goide.psi.GoConstSpec
import com.goide.psi.GoFieldDeclaration
import com.goide.psi.GoFunctionDeclaration
import com.goide.psi.GoInterfaceType
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoMethodSpec
import com.goide.psi.GoPointerType
import com.goide.psi.GoSignature
import com.goide.psi.GoStructType
import com.goide.psi.GoType
import com.goide.psi.GoTypeDeclaration
import com.goide.psi.GoTypeSpec
import com.goide.psi.GoVarDeclaration
import com.goide.psi.GoVarSpec
import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.agadek.smartdiff.engine.api.TextRange

/**
 * The only place that touches the Go plugin's PSI (languages/17-go-details §1, §4). Every entry point the provider calls
 * is wrapped so a [LinkageError] from the plugin becomes a plain exception the engine turns into a failed result.
 */
internal object GoInterop {

    data class Decl(
        val kind: String,
        val name: String,
        val signatureKey: String?,
        val signatureDisplay: String?,
        val receiver: String?,
        val members: List<PsiElement>,
        val body: PsiElement?,
        val range: TextRange,
    )

    private val GO: Language
        get() = Language.findLanguageByID("go") ?: error("the Go language is not registered")

    fun parse(project: Project, text: CharSequence): PsiFile = link {
        PsiFileFactory.getInstance(project).createFileFromText("file.go", GO, text, false, false)
            ?: error("PsiFileFactory returned null for Go")
    }

    /**
     * The file's functions, methods and specs (§4 Groups): a grouped `type`, `const` or `var` declaration gives each of
     * its specs, a single one gives itself, so its keyword is the declaration's own token.
     */
    fun topLevel(file: PsiFile): List<PsiElement> = link {
        buildList {
            for (child in file.children) {
                when (child) {
                    is GoFunctionDeclaration, is GoMethodDeclaration -> add(child)
                    is GoTypeDeclaration -> if (child.lparen == null) add(child) else addAll(child.typeSpecList)
                    is GoConstDeclaration -> if (child.lparen == null) add(child) else addAll(child.constSpecList)
                    is GoVarDeclaration -> if (child.lparen == null) add(child) else addAll(child.varSpecList)
                }
            }
        }
    }

    fun decl(element: PsiElement): Decl? = link {
        when (element) {
            is GoFunctionDeclaration -> functionDecl(element)
            is GoMethodDeclaration -> methodDecl(element)
            is GoTypeDeclaration -> element.typeSpecList.singleOrNull()?.let { typeDecl(it, element) }
            is GoConstDeclaration -> element.constSpecList.singleOrNull()?.let { constDecl(it, element) }
            is GoVarDeclaration -> element.varSpecList.singleOrNull()?.let { varDecl(it, element) }
            is GoTypeSpec -> typeDecl(element, element)
            is GoConstSpec -> constDecl(element, element)
            is GoVarSpec -> varDecl(element, element)
            is GoFieldDeclaration -> fieldDecl(element)
            is GoMethodSpec -> methodSpecDecl(element)
            else -> null
        }
    }

    /** Whether [element] is a code block whose children are statements (§10). */
    fun isBlock(element: PsiElement): Boolean = link { element is GoBlock }

    private fun functionDecl(function: GoFunctionDeclaration): Decl? {
        val name = function.identifier?.text ?: return null
        if (name == "_") return null
        val (key, display) = parameters(function.signature)
        return Decl("FUNCTION", name, key, display, null, emptyList(), function.block, rangeOf(function))
    }

    private fun methodDecl(method: GoMethodDeclaration): Decl? {
        val name = method.identifier?.text ?: return null
        val receiver = receiverName(method.receiver?.type)
        val (key, display) = parameters(method.signature)
        return Decl("METHOD", name, "$receiver.$key", display, receiver.ifEmpty { null }, emptyList(), method.block, rangeOf(method))
    }

    /** The receiver's type name without `*` and type arguments (§6); empty when it has none. */
    private fun receiverName(type: GoType?): String {
        val base = if (type is GoPointerType) type.type else type
        return base?.typeReferenceExpression?.identifier?.text.orEmpty()
    }

    private fun typeDecl(spec: GoTypeSpec, owner: PsiElement): Decl? {
        val name = spec.identifier?.text ?: return null
        if (name == "_") return null
        val specType = spec.specType
        val type = specType.type
        val (kind, members) = when {
            specType.assign != null -> "TYPE_ALIAS" to emptyList()
            type is GoStructType -> "STRUCT" to type.fieldDeclarationList
            type is GoInterfaceType -> "INTERFACE" to type.methodSpecList.filter { it.identifier != null }
            else -> "TYPE" to emptyList<PsiElement>()
        }
        return Decl(kind, name, null, null, null, members, null, rangeOf(owner))
    }

    private fun constDecl(spec: GoConstSpec, owner: PsiElement): Decl? {
        val name = names(spec.constDefinitionList.map { it.identifier.text }) ?: return null
        return Decl("CONSTANT", name, null, null, null, emptyList(), null, rangeOf(owner))
    }

    private fun varDecl(spec: GoVarSpec, owner: PsiElement): Decl? {
        val name = names(spec.varDefinitionList.map { it.identifier.text }) ?: return null
        return Decl("VARIABLE", name, null, null, null, emptyList(), null, rangeOf(owner))
    }

    private fun fieldDecl(field: GoFieldDeclaration): Decl? {
        val definitions = field.fieldDefinitionList
        val name = if (definitions.isNotEmpty()) {
            names(definitions.map { it.identifier.text })
        } else {
            // An embedded field is named by its Go field name: `Stringer` for `fmt.Stringer`, `Base` for `*Base` (§4).
            val anonymous = field.anonymousFieldDefinition ?: return null
            anonymous.identifier?.text ?: anonymous.typeReferenceExpression?.identifier?.text
        } ?: return null
        return Decl("FIELD", name, null, null, null, emptyList(), null, withTrailingComment(field, rangeOf(field)))
    }

    private fun methodSpecDecl(spec: GoMethodSpec): Decl? {
        val name = spec.identifier?.text ?: return null
        val (key, display) = parameters(spec.signature)
        return Decl("METHOD", name, key, display, null, emptyList(), null, rangeOf(spec))
    }

    /** Names declared together, joined with `, ` (R8); null when every name is `_` (R9). */
    private fun names(names: List<String>): String? =
        if (names.isEmpty() || names.all { it == "_" }) null else names.joinToString(", ")

    /**
     * The parameter types as a key without whitespace and as a display (§5): each type once per name, a variadic one
     * with its `...`, no type parameters and no results.
     */
    private fun parameters(signature: GoSignature?): Pair<String, String> {
        val keys = mutableListOf<String>()
        val displays = mutableListOf<String>()
        for (declaration in signature?.parameters?.parameterDeclarationList.orEmpty()) {
            val type = declaration.type
            val prefix = if (declaration.tripleDot != null) "..." else ""
            val key = prefix + (type?.let { tokens(it).joinToString("") } ?: "_")
            val display = prefix + (type?.let { display(tokens(it)) } ?: "_")
            repeat(maxOf(1, declaration.paramDefinitionList.size)) {
                keys += key
                displays += display
            }
        }
        return keys.joinToString(",") to displays.joinToString(", ")
    }

    /**
     * [element]'s range extended back over the comments directly above it (§7): a run of comments that ends on the line
     * before, with no blank line in it or after it. A comment after code on its line and a build constraint stay outside.
     */
    private fun rangeOf(element: PsiElement): TextRange {
        val text = element.containingFile.viewProvider.contents
        val range = element.textRange
        var bound = element.parent?.textRange?.startOffset ?: 0
        var sibling = element.prevSibling
        while (sibling != null) {
            if (sibling !is PsiWhiteSpace && sibling !is PsiComment) {
                bound = sibling.textRange.endOffset
                break
            }
            sibling = sibling.prevSibling
        }
        var start = range.startOffset
        var leaf = PsiTreeUtil.prevLeaf(element)
        while (leaf != null && leaf.textRange.startOffset >= bound) {
            if (leaf is PsiComment) {
                val leafStart = leaf.textRange.startOffset
                if (lineBreaks(text, leaf.textRange.endOffset, start) > 1) break
                if (isBuildConstraint(leaf.text)) break
                if (!startsLine(text, leafStart) && !afterComment(leaf)) break
                start = leafStart
            } else if (leaf !is PsiWhiteSpace) {
                break
            }
            leaf = PsiTreeUtil.prevLeaf(leaf)
        }
        return TextRange(start, range.endOffset)
    }

    /** [range] extended over a comment after [element] on the same line, as in `X int // pixels` (§7). */
    private fun withTrailingComment(element: PsiElement, range: TextRange): TextRange {
        var leaf = PsiTreeUtil.nextLeaf(element)
        while (leaf is PsiWhiteSpace && '\n' !in leaf.text) leaf = PsiTreeUtil.nextLeaf(leaf)
        return if (leaf is PsiComment) TextRange(range.start, leaf.textRange.endOffset) else range
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
        var leaf = PsiTreeUtil.prevLeaf(comment)
        while (leaf is PsiWhiteSpace && '\n' !in leaf.text) leaf = PsiTreeUtil.prevLeaf(leaf)
        return leaf is PsiComment
    }

    private fun isBuildConstraint(comment: String): Boolean = comment.startsWith("//go:build") || comment.startsWith("// +build")

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

    /** One space between two words and none next to `[](),.*` (§5): `func(int) error`, `map[string]int`. */
    private fun display(tokens: List<String>): String = buildString {
        var previous: String? = null
        for (token in tokens) {
            if (previous != null) {
                val space = when {
                    previous == ")" && token == "(" -> true
                    previous in NO_SPACE_AFTER -> false
                    token in NO_SPACE_BEFORE -> false
                    previous == "," -> true
                    else -> isWord(previous) || isWord(token)
                }
                if (space) append(' ')
            }
            append(token)
            previous = token
        }
    }

    private val NO_SPACE_BEFORE = setOf("(", ")", "[", "]", ",", ".", "{", "}", ";")
    private val NO_SPACE_AFTER = setOf("(", "[", "]", ".", "*", "...", "<-", "{", "~")

    private fun isWord(token: String): Boolean = token.isNotEmpty() && (token[0].isLetterOrDigit() || token[0] == '_')

    private inline fun <T> link(block: () -> T): T = try {
        block()
    } catch (e: LinkageError) {
        throw IllegalStateException("Go plugin PSI unavailable: ${e.javaClass.simpleName}: ${e.message}", e)
    }
}
