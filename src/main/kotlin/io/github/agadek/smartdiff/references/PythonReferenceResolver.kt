package io.github.agadek.smartdiff.references

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyStatement
import com.jetbrains.python.psi.PyTargetExpression
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.LanguageIds
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import java.util.concurrent.CancellationException

/**
 * Python usages (languages/04-python-details.md R19) through [PyReferenceExpression.reference.resolve()]. Only references
 * that resolve to a declaration in the compared file are grouped; `obj.helper` is left out because it resolves through
 * type inference in general, and unresolved names are left out.
 */
class PythonReferenceResolver : ReferenceResolver {

    override fun supports(languageId: String): Boolean = languageId == LanguageIds.PYTHON

    override fun target(file: PsiFile, range: TextRange, subject: Subject.Declaration): PsiElement? {
        return when (subject.kind) {
            DeclarationKind.CLASS -> {
                PsiTreeUtil.collectElements(file) { it is PyClass }.mapNotNull { it as PyClass }.firstOrNull { cls ->
                    val nameId = (cls as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange
                    nameId != null && nameId.startOffset >= range.start && nameId.endOffset <= range.end
                }
            }
            DeclarationKind.FUNCTION -> {
                PsiTreeUtil.collectElements(file) { it is PyFunction }.mapNotNull { it as PyFunction }.firstOrNull { fn ->
                    val nameId = (fn as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange
                    nameId != null && nameId.startOffset >= range.start && nameId.endOffset <= range.end
                }
            }
            DeclarationKind.VARIABLE -> {
                PsiTreeUtil.collectElements(file) { it is PyTargetExpression }.mapNotNull { it as PyTargetExpression }.firstOrNull { target ->
                    val nameId = (target as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange ?: target.textRange
                    nameId != null && nameId.startOffset >= range.start && nameId.endOffset <= range.end && target.qualifier == null &&
                        (target.name == subject.name || "(%s)".format(target.name ?: "") == subject.name)
                }
            }
            else -> null
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
        val found = LinkedHashMap<PsiElement, MutableSet<TextRange>>()
        val refs = PsiTreeUtil.collectElements(file) { it is PyReferenceExpression }
        for (element in refs) {
            cancellation.checkCanceled()
            val refExpr = element as PyReferenceExpression
            val qualifier = refExpr.qualifier
            val range = refExpr.nameElement?.textRange ?: continue
            val resolved = try {
                refExpr.reference?.resolve()
            } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LOG.debug(e)
                null
            } ?: continue
            if (qualifier != null) {
                // details section 8: only allow self.method() to methods of the enclosing class
                if (!isSelfMethodCallToClassMethod(refExpr, resolved)) continue
            }
            for (target in targets) {
                if (sameDeclaration(resolved, target)) {
                    found.getOrPut(target, ::linkedSetOf) += TextRange(range.startOffset, range.endOffset)
                    break
                }
            }
        }
        return found.mapValues { (_, ranges) -> ranges.sortedBy { it.start } }
    }

    private fun isSelfMethodCallToClassMethod(refExpr: PyReferenceExpression, resolved: PsiElement?): Boolean {
        val qualifier = refExpr.qualifier ?: return false
        if (qualifier !is PyReferenceExpression) return false
        if (qualifier.referencedName != "self") return false
        val fn = PsiTreeUtil.getParentOfType(refExpr, PyFunction::class.java) ?: return false
        val cls = PsiTreeUtil.getParentOfType(fn, PyClass::class.java) ?: return false
        if (resolved !is PyFunction) return false
        return PsiTreeUtil.getParentOfType(resolved, PyClass::class.java) == cls
    }

    private fun sameDeclaration(resolved: PsiElement, target: PsiElement): Boolean {
        if (resolved === target) return true
        if (resolved.javaClass == target.javaClass && resolved.textRange == target.textRange) return true
        return false
    }

    override fun context(file: PsiFile, usage: TextRange): TextRange? {
        val element = file.findElementAt(usage.start.coerceIn(0, maxOf(0, file.textLength - 1))) ?: return null
        var current: PsiElement? = element
        while (current != null && current !is PyFile) {
            val text = current.textRange
            if (text != null && text.startOffset <= usage.start && text.endOffset >= usage.end) {
                if (current is PyStatement || current is PyExpression) {
                    return TextRange(text.startOffset, text.endOffset)
                }
            }
            current = current.parent
        }
        return null
    }

    override fun whitespacePolicy(file: PsiFile): WhitespacePolicy = WhitespacePolicy.INDENTATION_SIGNIFICANT

    private companion object {
        val LOG: Logger = Logger.getInstance(PythonReferenceResolver::class.java)
    }
}
