package io.github.agadek.smartdiff.engine.declarations.psi.css

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.css.CssAtRule
import com.intellij.psi.css.CssBlock
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssImportList
import com.intellij.psi.css.CssKeyframesRule
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.css.CssRulesetList
import com.intellij.psi.css.CssSelector
import com.intellij.psi.css.CssSelectorList
import com.intellij.psi.css.CssStylesheet
import com.intellij.psi.util.PsiTreeUtil
import io.github.agadek.smartdiff.engine.api.TextRange

/**
 * The only place that touches the CSS plugin's PSI (languages/18-css-details §1, §4). The SCSS and Less constructs are
 * recognized by their element type names, so no class of the Sass or Less plugins is referenced (details §1). Every
 * entry point the provider calls is wrapped so a [LinkageError] from the plugin becomes a plain exception the engine
 * turns into a failed result.
 */
internal object CssInterop {

    /** The language ids the provider reads (details §1): the CSS plugin's own and those of its two block dialects. */
    val LANGUAGE_IDS = setOf("CSS", "SCSS", "LESS")

    data class Decl(
        val kind: String,
        val name: String,
        val signatureKey: String?,
        val signatureDisplay: String?,
        val members: List<PsiElement>,
        val body: PsiElement?,
        val range: TextRange,
    )

    fun parse(project: Project, text: CharSequence, languageId: String): PsiFile = link {
        val language = Language.findLanguageByID(languageId) ?: error("the $languageId language is not registered")
        val extension = languageId.lowercase()
        PsiFileFactory.getInstance(project).createFileFromText("file.$extension", language, text, false, false)
            ?: error("PsiFileFactory returned null for $languageId")
    }

    /** The members of the file's stylesheet and of its top-level rule set list, in source order (details §4). */
    fun topLevel(file: PsiFile): List<PsiElement> = link {
        // A `@layer` rule before the first rule set belongs to the stylesheet's import list, not to its rule set list.
        val list = PsiTreeUtil.findChildOfType(file, CssRulesetList::class.java)
        val stylesheet = list?.parent ?: PsiTreeUtil.findChildrenOfType(file, CssStylesheet::class.java).lastOrNull()
        stylesheet?.let(::members).orEmpty()
    }

    fun decl(element: PsiElement): Decl? = link {
        val type = type(element)
        when {
            type == SCSS_MIXIN -> callable("MIXIN", element, scssName(element), SCSS_PARAMETERS)
            type == SCSS_FUNCTION -> callable("FUNCTION", element, scssName(element), SCSS_PARAMETERS)
            type == LESS_MIXIN -> callable("MIXIN", element, lessMixinName(element), LESS_PARAMETERS)
            type == SCSS_VARIABLE || type == LESS_VARIABLE -> variable(element)
            element is CssRuleset -> rule(element, element)
            element is CssDeclaration -> if (element.isCustomProperty) customProperty(element) else null
            element is CssAtRule -> atRule(element)
            else -> wrapped(element)?.let { rule(it, element) }
        }
    }

    /** Whether [element] is a block whose children are properties and statements (details §10). */
    fun isBlock(element: PsiElement): Boolean = link { element is CssBlock || type(element) == SCSS_FUNCTION_BODY }

    /**
     * The whitespace whose presence and spelling count (details §8): descendant combinators, and whitespace next to a `+`
     * or `-` operator outside a selector.
     */
    fun significantWhitespace(file: PsiFile): List<TextRange> = link {
        val ranges = mutableListOf<TextRange>()
        // A walk by first child and next sibling: the stylesheet is a lazily parsed element that PsiTreeUtil's element
        // walk does not enter.
        fun walk(element: PsiElement) {
            var child = element.firstChild
            while (child != null) {
                if (child is PsiWhiteSpace && isSignificant(child)) ranges += TextRange(child.textRange.startOffset, child.textRange.endOffset)
                walk(child)
                child = child.nextSibling
            }
        }
        walk(file)
        ranges
    }

    private fun isSignificant(whitespace: PsiWhiteSpace): Boolean = when {
        whitespace.parent is CssSelector -> !nextToCombinator(whitespace)
        PsiTreeUtil.getParentOfType(whitespace, CssSelectorList::class.java) != null -> false
        else -> isSign(PsiTreeUtil.prevLeaf(whitespace)) || isSign(PsiTreeUtil.nextLeaf(whitespace))
    }

    private fun isSign(leaf: PsiElement?): Boolean = leaf != null && leaf !is PsiComment && (leaf.text == "+" || leaf.text == "-")

    private fun nextToCombinator(whitespace: PsiElement): Boolean {
        val before = PsiTreeUtil.skipSiblingsBackward(whitespace, PsiWhiteSpace::class.java, PsiComment::class.java)
        val after = PsiTreeUtil.skipSiblingsForward(whitespace, PsiWhiteSpace::class.java, PsiComment::class.java)
        return before == null || after == null || before.text in COMBINATORS || after.text in COMBINATORS
    }

    /**
     * The possible declarations among [container]'s children, in source order (details §4): rule sets, at-rules, custom
     * properties and dialect declarations, through a Less wrapper around a rule set. The block of a rule set or an
     * at-rule is looked through, and a `@keyframes` rule holds its keyframe rule sets directly.
     */
    private fun members(container: PsiElement): List<PsiElement> = buildList {
        var child = container.firstChild
        while (child != null) {
            if (child is CssRulesetList || child is CssImportList) addAll(members(child)) else if (isCandidate(child)) add(child)
            child = child.nextSibling
        }
    }

    private fun isCandidate(element: PsiElement): Boolean {
        val type = type(element)
        return when {
            type in CALLABLES || type == SCSS_VARIABLE || type == LESS_VARIABLE -> true
            type.startsWith("SCSS_") || type.startsWith("LESS_") -> wrapped(element) != null
            element is CssRuleset || element is CssAtRule -> true
            element is CssDeclaration -> element.isCustomProperty
            else -> wrapped(element) != null
        }
    }

    /** The rule set of a Less element that only wraps one, as every Less rule set is wrapped; null otherwise. */
    private fun wrapped(element: PsiElement): CssRuleset? {
        if (element is CssRuleset || element.firstChild == null) return null
        val code = element.children.filter { it !is PsiWhiteSpace && it !is PsiComment }
        val only = code.singleOrNull() as? CssRuleset ?: return null
        return if (only.textRange == element.textRange) only else null
    }

    private fun rule(ruleset: CssRuleset, owner: PsiElement): Decl? {
        val selectors = ruleset.selectorList ?: return null
        val name = selectorName(selectors).ifEmpty { return null }
        val members = ruleset.block?.let(::members).orEmpty()
        return Decl("RULE", name, null, null, members, null, rangeOf(owner))
    }

    private fun atRule(rule: CssAtRule): Decl? {
        val block = if (rule is CssKeyframesRule) rule else PsiTreeUtil.getChildOfType(rule, CssBlock::class.java) ?: return null
        val name = preludeName(rule, if (block === rule) brace(rule) else block).ifEmpty { return null }
        return Decl("AT_RULE", name, null, null, members(block), null, rangeOf(rule))
    }

    private fun brace(rule: PsiElement): PsiElement? {
        var child = rule.firstChild
        while (child != null) {
            if (child.text == "{") return child
            child = child.nextSibling
        }
        return null
    }

    private fun customProperty(declaration: CssDeclaration): Decl? {
        val name = declaration.propertyName.ifEmpty { return null }
        return Decl("VARIABLE", name, null, null, emptyList(), null, withSemicolon(declaration, rangeOf(declaration)))
    }

    private fun variable(element: PsiElement): Decl? {
        val name = element.firstChild?.takeIf { type(it) == SCSS_VARIABLE_NAME || type(it) == LESS_VARIABLE_NAME }?.text
            ?: return null
        return Decl("VARIABLE", name, null, null, emptyList(), null, withSemicolon(element, rangeOf(element)))
    }

    /** A mixin or SCSS function (details §6): its parameter names as the signature, its block as the body. */
    private fun callable(kind: String, element: PsiElement, name: String?, parameterList: String): Decl? {
        if (name.isNullOrEmpty()) return null
        val list = element.children.firstOrNull { type(it) == parameterList }
        val parameters = list?.children.orEmpty().filter { type(it) == SCSS_PARAMETER || type(it) == LESS_PARAMETER }.map(::parameterName)
        val body = element.children.lastOrNull { it is CssBlock || type(it) == SCSS_FUNCTION_BODY }
        return Decl(kind, name, parameters.joinToString(","), parameters.joinToString(", "), emptyList(), body, rangeOf(element))
    }

    /** A parameter's name: its variable with a `...` rest marker and without a default value; a Less pattern as written. */
    private fun parameterName(parameter: PsiElement): String {
        val declaration = parameter.children.firstOrNull { type(it) == SCSS_VARIABLE || type(it) == LESS_VARIABLE }
        val variable = declaration?.firstChild?.takeIf { type(it) == SCSS_VARIABLE_NAME || type(it) == LESS_VARIABLE_NAME }
        val rest = if (parameter.text.trimEnd().endsWith("...")) "..." else ""
        return (variable?.text ?: compact(tokens(parameter), prelude = true)) + rest
    }

    /** An SCSS mixin's or function's name: the identifier after its keyword. */
    private fun scssName(element: PsiElement): String? {
        var child = element.firstChild?.nextSibling
        while (child != null) {
            if (child !is PsiWhiteSpace && child !is PsiComment) return child.text.takeIf { type(child) in NAME_TOKENS }
            child = child.nextSibling
        }
        return null
    }

    /** A Less mixin's name as written, such as `.border` or `#ns`. */
    private fun lessMixinName(element: PsiElement): String? =
        element.children.firstOrNull { type(it) == LESS_MIXIN_NAME }?.let { compact(tokens(it)) }

    /**
     * The selectors' name (R4, details §5): a descendant combinator is one space, `>`, `+` and `~` have one space on each
     * side, selectors are joined with `, `, and no other whitespace or comment is kept.
     */
    private fun selectorName(list: CssSelectorList): String = buildString {
        var first = true
        var child = list.firstChild
        while (child != null) {
            if (child is CssSelector) {
                if (!first) append(", ")
                append(selector(child))
                first = false
            }
            child = child.nextSibling
        }
        if (first) append(compact(tokens(list)))
    }

    private fun selector(selector: CssSelector): String = buildString {
        var pendingSpace = false
        var child = selector.firstChild
        while (child != null) {
            when {
                child is PsiComment -> {}
                child is PsiWhiteSpace -> pendingSpace = isNotEmpty()
                child.firstChild == null && child.text in COMBINATORS -> {
                    append(' ').append(child.text).append(' ')
                    pendingSpace = false
                }
                else -> {
                    if (pendingSpace && !endsWith(' ')) append(' ')
                    append(compact(tokens(child)))
                    pendingSpace = false
                }
            }
            child = child.nextSibling
        }
    }.trim()

    /**
     * An at-rule's name (R5, details §5): its keyword and prelude up to [end], with whitespace collapsed to one space,
     * none inside brackets or before `,` and `)`, and `, ` and `: ` inside parentheses.
     */
    private fun preludeName(rule: PsiElement, end: PsiElement?): String {
        val limit = end?.textRange?.startOffset ?: rule.textRange.endOffset
        val leaves = mutableListOf<PsiElement>()
        var leaf: PsiElement? = PsiTreeUtil.firstChild(rule)
        while (leaf != null && leaf.textRange.startOffset < limit && PsiTreeUtil.isAncestor(rule, leaf, false)) {
            leaves += leaf
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return compact(leaves.map { it.text to (it is PsiWhiteSpace || it is PsiComment) }, prelude = true)
    }

    /** The leaves of [element] as (text, whether it is whitespace or a comment). */
    private fun tokens(element: PsiElement): List<Pair<String, Boolean>> = buildList {
        fun visit(e: PsiElement) {
            if (e.firstChild == null) {
                if (e.textLength > 0) add(e.text to (e is PsiWhiteSpace || e is PsiComment))
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

    /**
     * Joins [tokens] by the rules of details §5. In a [prelude], `:` inside parentheses is followed by a space, as in
     * `(max-width: 600px)`; in a selector it starts a pseudo-class and is not.
     */
    private fun compact(tokens: List<Pair<String, Boolean>>, prelude: Boolean = false): String = buildString {
        var depth = 0
        var brackets = 0
        var space = false
        for ((text, blank) in tokens) {
            if (blank) {
                space = isNotEmpty()
                continue
            }
            if (isNotEmpty()) {
                val previous = last()
                val separated = when {
                    text == ")" || text == "]" || text == "," -> false
                    previous == ',' -> true
                    previous == ':' && depth > 0 && prelude -> true
                    previous == '(' || previous == '[' -> false
                    text == ":" && depth > 0 && prelude -> false
                    // Inside an attribute selector only a space between two words, as before the `i` flag, is kept.
                    brackets > 0 -> space && isWord(previous) && isWord(text.first())
                    else -> space
                }
                if (separated) append(' ')
            }
            append(text)
            for (c in text) {
                when (c) {
                    '(' -> depth++
                    ')' -> depth = maxOf(0, depth - 1)
                    '[' -> brackets++
                    ']' -> brackets = maxOf(0, brackets - 1)
                }
            }
            space = false
        }
    }

    /**
     * [element]'s range extended back over the comments directly above it (R13, details §7): a run of comments that ends
     * on the line before, with no blank line in it or after it. A comment after code on its line stays outside.
     */
    private fun rangeOf(element: PsiElement): TextRange {
        val text = element.containingFile.viewProvider.contents
        val range = element.textRange
        var start = range.startOffset
        var sibling = element.prevSibling
        while (sibling != null) {
            if (sibling is PsiComment) {
                if (lineBreaks(text, sibling.textRange.endOffset, start) > 1) break
                if (!startsLine(text, sibling.textRange.startOffset) && !afterComment(sibling)) break
                start = sibling.textRange.startOffset
            } else if (sibling !is PsiWhiteSpace) {
                break
            }
            sibling = sibling.prevSibling
        }
        return TextRange(start, range.endOffset)
    }

    /** [range] extended over the `;` that ends [element], on the same line (details §7). */
    private fun withSemicolon(element: PsiElement, range: TextRange): TextRange {
        var next = element.nextSibling
        while (next is PsiWhiteSpace && '\n' !in next.text) next = next.nextSibling
        return if (next != null && next.firstChild == null && next.text == ";") TextRange(range.start, next.textRange.endOffset) else range
    }

    private fun lineBreaks(text: CharSequence, from: Int, to: Int): Int = (from until to).count { text[it] == '\n' }

    private fun startsLine(text: CharSequence, offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0 && text[i] != '\n') {
            if (!text[i].isWhitespace()) return false
            i--
        }
        return true
    }

    private fun afterComment(comment: PsiElement): Boolean {
        var previous = comment.prevSibling
        while (previous is PsiWhiteSpace && '\n' !in previous.text) previous = previous.prevSibling
        return previous is PsiComment
    }

    /**
     * The element type's name, such as `SCSS_MIXIN_DECLARATION` (details §1): its `toString`, without the `PsiElement(…)`
     * a stub element type wraps it in. `IElementType.getDebugName` would give it directly, but is internal API.
     */
    private fun type(element: PsiElement?): String {
        val text = element?.node?.elementType?.toString().orEmpty()
        return if (text.startsWith(STUB_PREFIX) && text.endsWith(")")) text.substring(STUB_PREFIX.length, text.length - 1) else text
    }

    private const val STUB_PREFIX = "PsiElement("

    private fun isWord(c: Char): Boolean = c.isLetterOrDigit() || c == '"' || c == '\'' || c == '_' || c == '-'

    private val COMBINATORS = setOf(">", "+", "~", "||")

    private const val SCSS_MIXIN = "SCSS_MIXIN_DECLARATION"
    private const val SCSS_FUNCTION = "SCSS_FUNCTION_DECLARATION"
    private const val SCSS_FUNCTION_BODY = "SCSS_FUNCTION_BODY"
    private const val SCSS_VARIABLE = "SCSS_VARIABLE_DECLARATION"
    private const val SCSS_VARIABLE_NAME = "SCSS_VARIABLE"
    private const val SCSS_PARAMETERS = "SCSS_PARAMETER_LIST"
    private const val SCSS_PARAMETER = "SCSS_PARAMETER"
    private const val LESS_MIXIN = "LESS_MIXIN"
    private const val LESS_MIXIN_NAME = "LESS_MIXIN_NAME"
    private const val LESS_VARIABLE = "LESS_VARIABLE_DECLARATION"
    private const val LESS_VARIABLE_NAME = "LESS_VARIABLE"
    private const val LESS_PARAMETERS = "LESS_PARAMETER_LIST"
    private const val LESS_PARAMETER = "LESS_PARAMETER"

    private val CALLABLES = setOf(SCSS_MIXIN, SCSS_FUNCTION, LESS_MIXIN)
    private val NAME_TOKENS = setOf("CSS_FUNCTION_TOKEN", "CSS_IDENT")

    private inline fun <T> link(block: () -> T): T = try {
        block()
    } catch (e: LinkageError) {
        throw IllegalStateException("CSS plugin PSI unavailable: ${e.javaClass.simpleName}: ${e.message}", e)
    }
}
