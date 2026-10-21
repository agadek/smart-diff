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
    fun text(element: PsiElement, skip: (PsiElement) -> Boolean): String = buildString {
        fun visit(e: PsiElement) {
            if (e is PsiWhiteSpace || e is PsiComment || skip(e)) return
            val first = e.firstChild
            if (first == null) {
                append(e.text)
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
}
