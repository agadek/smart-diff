package io.github.agadek.smartdiff.references

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTypeReference

/**
 * Kotlin usages (relationships/04 R14) through the Kotlin PSI's own references in K2: `KtReferenceExpression.references`,
 * `PsiReference.resolve()`. No Kotlin Analysis API is called, so there is no `KaSession` or `KaExperimentalApi` use.
 *
 * A usage's range is its own name. Calls, qualified names and constructor calls are matched through the same name
 * reference the IDE uses, and a usage whose name does not change, such as an operator call to a renamed `plus`, is
 * left out because it is not a usage that follows the rename.
 */
class KotlinReferenceResolver : ReferenceResolver {

    override fun supports(languageId: String): Boolean = languageId == KotlinLanguage.INSTANCE.id

    override fun target(file: PsiFile, range: TextRange, subject: Subject.Declaration): PsiElement? {
        val candidates = PsiTreeUtil.collectElements(file) { element ->
            element.textRange?.let { it.startOffset == range.start && it.endOffset == range.end } == true
        }
        return if (subject.kind == DeclarationKind.CONSTRUCTOR) {
            candidates.firstOrNull { it is KtConstructor<*> }
        } else {
            candidates.firstOrNull { element -> element is PsiNamedElement && nameMatches(element, subject) }
        }
    }

    override fun usages(file: PsiFile, target: PsiElement, cancellation: Cancellation): List<TextRange> =
        index(file, setOf(target), cancellation)[target].orEmpty()

    override fun usages(
        file: PsiFile,
        targets: Set<PsiElement>,
        cancellation: Cancellation,
    ): Map<PsiElement, List<TextRange>> {
        val index = index(file, targets, cancellation)
        return targets.associateWith { index[it].orEmpty() }
    }

    private fun index(file: PsiFile, targets: Set<PsiElement>, cancellation: Cancellation): Map<PsiElement, List<TextRange>> {
        val names = targets.mapNotNullTo(HashSet()) { target ->
            (target as? PsiNamedElement)?.name ?: (target as? KtConstructor<*>)?.getContainingClassOrObject()?.name
        }
        if (names.isEmpty()) return emptyMap()
        val found = LinkedHashMap<PsiElement, MutableSet<TextRange>>()
        val elements = PsiTreeUtil.collectElements(file) { element ->
            element is KtSimpleNameExpression && element !is KtOperationReferenceExpression
        }
        for (element in elements) {
            cancellation.checkCanceled()
            val nameElement = (element as KtSimpleNameExpression).getReferencedNameElement() ?: continue
            if (nameElement.text !in names) continue
            val range = TextRange(nameElement.textRange.startOffset, nameElement.textRange.endOffset)
            for (reference in element.references) {
                val resolved = ReferenceResolution.resolve(reference, file, cancellation, LOG) ?: continue
                // An operator or other reference whose source name is not the declaration's name (for example `+` for
                // a renamed `plus`) did not change with the rename and is not a usage of it.
                val resolvedName = (resolved as? PsiNamedElement)?.name
                if (resolvedName != null && resolvedName.isNotEmpty() && nameElement.text != resolvedName) continue
                for (target in targetsOf(resolved)) {
                    found.getOrPut(target, ::linkedSetOf) += range
                }
            }
            cancellation.checkCanceled()
        }
        return found.mapValues { (_, ranges) -> ranges.sortedBy { it.start } }
    }

    override fun context(file: PsiFile, usage: TextRange): TextRange? {
        var element = file.findElementAt(usage.start.coerceIn(0, maxOf(0, file.textLength - 1))) ?: return null
        var chain: PsiElement? = null
        var smallest: PsiElement? = null
        var type: PsiElement? = null
        var member: PsiElement? = null
        while (element !is KtFile) {
            val range = element.textRange
            if (range.startOffset <= usage.start && range.endOffset >= usage.end) {
                when (element) {
                    is KtCallExpression, is KtQualifiedExpression -> chain = element
                    is KtTypeReference -> if (type == null) type = element
                    is KtExpression -> if (smallest == null) smallest = element
                    is KtImportDirective -> if (member == null) member = element
                    is KtDeclaration -> if (member == null) member = element
                }
            }
            element = element.parent
        }
        return (chain ?: smallest ?: type ?: member)?.textRange?.let { TextRange(it.startOffset, it.endOffset) }
    }

    override fun whitespacePolicy(file: PsiFile): WhitespacePolicy = WhitespacePolicy.LINE_BREAKS_SIGNIFICANT

    private fun nameMatches(element: PsiNamedElement, subject: Subject.Declaration): Boolean {
        if (subject.name.isEmpty()) return true
        return element.name == subject.name
    }

    /** A constructor call also names its class; a class name also names the class's constructors. */
    private fun targetsOf(resolved: PsiElement): List<PsiElement> = when (resolved) {
        is KtConstructor<*> -> listOfNotNull(resolved, resolved.getContainingClassOrObject())
        is KtClassOrObject -> listOf(resolved) + listOfNotNull(resolved.primaryConstructor) + resolved.secondaryConstructors
        else -> listOf(resolved)
    }

    private companion object {
        val LOG: Logger = Logger.getInstance(KotlinReferenceResolver::class.java)
    }
}
