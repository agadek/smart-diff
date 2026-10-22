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
    fun text(element: PsiElement, skip: (PsiElement) -> Boolean): String = text(element, skip, readable = false)

    /**
     * [text] for labels: where the source separates two words, as in `Class<out Annotation>` or `? extends E`, one space
     * stays. Next to brackets, commas and dots there is none, so labels read like signatures (`Map<String,List<T>>`).
     */
    fun label(element: PsiElement, skip: (PsiElement) -> Boolean): String = text(element, skip, readable = true)

    private fun text(element: PsiElement, skip: (PsiElement) -> Boolean, readable: Boolean): String = buildString {
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
                if (readable && separated && isNotEmpty() && last() !in TIGHT && text.first() !in TIGHT) append(' ')
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
