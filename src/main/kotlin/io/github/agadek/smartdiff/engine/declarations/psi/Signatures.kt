package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace

/** Signature normalization (technical specification §6.6). */
internal object Signatures {

    /**
     * The text of [element] without whitespace, comments and the subtrees [skip] matches, such as annotations,
     * modifiers and the parameter name.
     */
    fun text(element: PsiElement, skip: (PsiElement) -> Boolean): String = text(element, skip, spacing = null)

    /**
     * [text] for labels: where the source separates two words, as in `Class<out Annotation>` or `? extends E`, one space
     * stays. Next to brackets, commas and dots there is none, so labels read like signatures (`Map<String,List<T>>`).
     */
    fun label(element: PsiElement, skip: (PsiElement) -> Boolean): String = text(element, skip, Spacing(TIGHT, TIGHT))

    /**
     * [label] for Scala, where `<`, `>` and `=` make operators and arrows rather than brackets, so they keep their spaces
     * (`=> Int`, `A <:< B`, `(Int) => Int`): there is no space only inside brackets and next to commas and dots.
     */
    fun scalaLabel(element: PsiElement, skip: (PsiElement) -> Boolean): String =
        text(element, skip, Spacing(noSpaceAfter = "([{,.", noSpaceBefore = ")]},."))

    /** Where a label never puts a space: after the characters of [noSpaceAfter] and before those of [noSpaceBefore]. */
    private class Spacing(val noSpaceAfter: String, val noSpaceBefore: String)

    private fun text(element: PsiElement, skip: (PsiElement) -> Boolean, spacing: Spacing?): String = buildString {
        // Whether whitespace, a comment or a skipped subtree came after the last text appended.
        var separated = false
        fun visit(e: PsiElement) {
            if (e is PsiWhiteSpace || e is PsiComment || skip(e)) {
                separated = true
                return
            }
            val first = e.firstChild
            if (first == null) {
                val text = e.text
                if (text.isEmpty()) return
                if (spacing != null && separated && isNotEmpty() && last() !in spacing.noSpaceAfter && text.first() !in spacing.noSpaceBefore) append(' ')
                separated = false
                append(text)
                return
            }
            var child: PsiElement? = first
            while (child != null) {
                visit(child)
                child = child.nextSibling
            }
        }
        visit(element)
    }

    /** Joins normalized parameter types into a signature. */
    fun of(parameterTypes: List<String>): String = parameterTypes.joinToString(",")

    /** Characters a label never puts a space next to. */
    private const val TIGHT = "<>()[]{},."
}
