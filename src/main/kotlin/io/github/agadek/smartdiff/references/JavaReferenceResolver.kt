package io.github.agadek.smartdiff.references

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.diagnostic.Logger
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
class JavaReferenceResolver : ReferenceResolver {

    override fun supports(languageId: String): Boolean = languageId == JavaLanguage.INSTANCE.id

    override fun target(file: PsiFile, range: TextRange, subject: Subject.Declaration): PsiElement? {
        val candidates = PsiTreeUtil.collectElements(file) { element ->
            element.textRange?.let { it.startOffset == range.start && it.endOffset == range.end } == true
        }
        return candidates.firstOrNull { element -> element is PsiNamedElement && nameMatches(element, subject) }
            ?: candidates.firstOrNull { it is PsiMethod || it is PsiClass }
    }

    override fun usages(file: PsiFile, target: PsiElement, cancellation: Cancellation): List<TextRange> =
        usages(file, setOf(target), cancellation)[target].orEmpty()

    /** One reference walk and one resolve per reference, shared by every rename target in [targets] (R17). */
    override fun usages(
        file: PsiFile,
        targets: Set<PsiElement>,
        cancellation: Cancellation,
    ): Map<PsiElement, List<TextRange>> {
        if (targets.isEmpty()) return emptyMap()
        val names = targets.mapNotNullTo(HashSet()) { (it as? PsiNamedElement)?.name }
        if (names.isEmpty()) return emptyMap()
        val found = targets.associateWith { sortedSetOf<TextRange>(compareBy { it.start }) }
        val elements = PsiTreeUtil.collectElements(file) { it is PsiReferenceExpression || it is PsiJavaCodeReferenceElement }
        for (element in elements) {
            cancellation.checkCanceled()
            val range = nameRange(element) ?: continue
            // Resolve only the references that can name this file's rename endpoints, not every reference (R17).
            if (file.viewProvider.contents.subSequence(range.start, range.end).toString() !in names) continue
            for (reference in element.references) {
                val resolved = ReferenceResolution.resolve(reference, file, cancellation, LOG) ?: continue
                for (target in targets) {
                    if (resolvesTo(resolved, target)) found.getValue(target) += range
                }
            }
            cancellation.checkCanceled()
        }
        return found.mapValues { it.value.toList() }
    }

    override fun context(file: PsiFile, usage: TextRange): TextRange? {
        var element = file.findElementAt(usage.start.coerceIn(0, maxOf(0, file.textLength - 1))) ?: return null
        var statement: PsiElement? = null
        var member: PsiElement? = null
        while (element !is PsiFile) {
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

    private companion object {
        val LOG: Logger = Logger.getInstance(JavaReferenceResolver::class.java)
    }
}
