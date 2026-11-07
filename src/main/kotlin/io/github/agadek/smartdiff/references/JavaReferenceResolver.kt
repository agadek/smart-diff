package io.github.agadek.smartdiff.references

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiStatement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.progress.ProcessCanceledException
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy

/**
 * Java usages (relationships/04 R14): public platform PSI only, no analysis API or internal class
 * (`PsiReferenceExpression`, `PsiJavaCodeReferenceElement`, `PsiReference.resolve()`).
 *
 * A usage's range is its own name, so `order.total()` highlights `total` and `new Order()` highlights `Order`. A
 * constructor reference is a usage of its class when the relationship renamed the class, and of the constructor when
 * the relationship renamed the constructor.
 */
internal object JavaReferenceResolver : ReferenceResolver {

    override fun supports(languageId: String): Boolean = languageId == JavaLanguage.INSTANCE.id

    override fun target(file: PsiFile, range: TextRange, subject: Subject.Declaration): PsiElement? {
        val candidates = PsiTreeUtil.collectElements(file) { element ->
            element.textRange?.let { it.startOffset == range.start && it.endOffset == range.end } == true
        }
        return candidates.firstOrNull { element -> element is PsiNamedElement && nameMatches(element, subject) }
            ?: candidates.firstOrNull { it is PsiMethod || it is PsiClass }
    }

    override fun usages(file: PsiFile, target: PsiElement, cancellation: Cancellation): List<TextRange> {
        val found = sortedSetOf<TextRange>(compareBy { it.start })
        val elements = PsiTreeUtil.collectElements(file) { it is PsiReferenceExpression || it is PsiJavaCodeReferenceElement }
        for (element in elements) {
            cancellation.checkCanceled()
            for (reference in element.references) {
                val range = nameRange(element) ?: continue
                val resolved = try {
                    reference.resolve()
                } catch (e: ProcessCanceledException) {
                    // A canceled read action is a cancellation when the pass asked for one; otherwise this usage is
                    // simply unresolved (relationships/04, Scala resolver note).
                    cancellation.checkCanceled()
                    null
                } catch (e: Exception) {
                    null
                }
                cancellation.checkCanceled()
                if (resolved != null && resolvesTo(resolved, target)) found += range
            }
        }
        return found.toList()
    }

    override fun context(file: PsiFile, usage: TextRange): TextRange? {
        var element = file.findElementAt(usage.start.coerceIn(0, maxOf(0, file.textLength - 1))) ?: return null
        var statement: PsiElement? = null
        var member: PsiElement? = null
        while (element != null && element !is PsiFile) {
            val range = element.textRange
            if (range.startOffset <= usage.start && range.endOffset >= usage.end) {
                when (element) {
                    is PsiExpression -> return TextRange(range.startOffset, range.endOffset)
                    is PsiStatement -> if (statement == null) statement = element
                    is PsiMember -> if (member == null) member = element
                }
            }
            element = element.parent
        }
        return (statement ?: member)?.textRange?.let { TextRange(it.startOffset, it.endOffset) }
    }

    override fun whitespacePolicy(file: PsiFile): WhitespacePolicy = WhitespacePolicy.INSIGNIFICANT

    private fun nameMatches(element: PsiNamedElement, subject: Subject.Declaration): Boolean {
        if (subject.name.isEmpty()) return true
        return element.name == subject.name || subject.kind == DeclarationKind.CONSTRUCTOR && element is PsiMethod && element.isConstructor
    }

    /** The reference's own name identifier; the `new` of `Order::new` is not a class name and is skipped. */
    private fun nameRange(element: PsiElement): TextRange? {
        val range = when (element) {
            is PsiReferenceExpression -> element.referenceNameElement?.textRange
            is PsiJavaCodeReferenceElement -> element.referenceNameElement?.textRange
            else -> null
        } ?: return null
        if (element is PsiReferenceExpression && element.referenceNameElement?.text == "new") return null
        return TextRange(range.startOffset, range.endOffset)
    }

    /** A class target accepts its constructor reference; every other target must be the resolved declaration itself. */
    private fun resolvesTo(resolved: PsiElement, target: PsiElement): Boolean {
        if (resolved === target) return true
        if (target is PsiClass && resolved is PsiMethod && resolved.isConstructor) return resolved.containingClass === target
        if (target is PsiMethod && target.isConstructor && resolved is PsiClass) return target.containingClass === resolved
        return false
    }
}
