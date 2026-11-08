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
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.ScPrimaryConstructor
import org.jetbrains.plugins.scala.lang.psi.api.base.ScReference
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScDeclaration
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScConstructorOwner

/**
 * Scala usages (relationships/04 R14) in the dialect the declaration provider chose, through
 * `ScReferenceExpression`/`ScReference.resolve()`. The Scala PSI is not public API, so this class is version-checked
 * against the Scala plugin of each target IDE; it is registered through `smartdiff-scala.xml`, so a plugin without
 * Scala never loads it.
 *
 * A usage's range is its own name. A reference whose source name is not the declaration's name, such as an operator
 * call to a renamed `+` method, is not a usage that follows the rename and is left out.
 */
class ScalaReferenceResolver : ReferenceResolver {

    override fun supports(languageId: String): Boolean = languageId == SCALA

    override fun target(file: PsiFile, range: TextRange, subject: Subject.Declaration): PsiElement? {
        val candidates = PsiTreeUtil.collectElements(file) { element ->
            element.textRange?.let { it.startOffset == range.start && it.endOffset == range.end } == true
        }
        return if (subject.kind == DeclarationKind.CONSTRUCTOR) {
            candidates.firstOrNull { it is ScFunction && it.isConstructor }
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
            (target as? PsiNamedElement)?.name ?: (target as? ScPrimaryConstructor)?.containingClass()?.name
        }
        if (names.isEmpty()) return emptyMap()
        val found = LinkedHashMap<PsiElement, MutableSet<TextRange>>()
        val elements = PsiTreeUtil.collectElements(file) { it is ScReference }
        for (element in elements) {
            cancellation.checkCanceled()
            val reference = element as ScReference
            val nameId = reference.nameId() ?: continue
            if (nameId.text !in names) continue
            val range = nameId.textRange
            val resolved = ReferenceResolution.resolve(reference, file, cancellation, LOG) ?: continue
            val resolvedName = (resolved as? PsiNamedElement)?.name
            if (resolvedName != null && resolvedName.isNotEmpty() && nameId.text != resolvedName) continue
            for (target in targetsOf(resolved)) {
                found.getOrPut(target, ::linkedSetOf) += TextRange(range.startOffset, range.endOffset)
            }
            cancellation.checkCanceled()
        }
        return found.mapValues { (_, ranges) -> ranges.sortedBy { it.start } }
    }

    /** A constructor reference also names its class; a class name also names the class's constructors. */
    private fun targetsOf(resolved: PsiElement): List<PsiElement> {
        val targets = mutableListOf<PsiElement>()
        targets += resolved
        when (resolved) {
            is ScPrimaryConstructor -> resolved.containingClass()?.let { targets.add(it) }
            is ScConstructorOwner -> for (constructor in resolved.constructors()) targets.add(constructor as PsiElement)
        }
        return targets
    }

    override fun context(file: PsiFile, usage: TextRange): TextRange? {
        var element = file.findElementAt(usage.start.coerceIn(0, maxOf(0, file.textLength - 1))) ?: return null
        var expression: ScExpression? = null
        var type: ScTypeElement? = null
        var declaration: ScDeclaration? = null
        while (element !is ScalaFile) {
            val range = element.textRange
            if (range.startOffset <= usage.start && range.endOffset >= usage.end) {
                when (element) {
                    is ScExpression -> if (expression == null) expression = element
                    is ScTypeElement -> if (type == null) type = element
                    is ScDeclaration -> if (declaration == null) declaration = element
                }
            }
            element = element.parent
        }
        return (expression ?: type ?: declaration)?.textRange?.let { TextRange(it.startOffset, it.endOffset) }
    }

    override fun whitespacePolicy(file: PsiFile): WhitespacePolicy =
        if (file.language == Scala3Language.INSTANCE) WhitespacePolicy.INDENTATION_SIGNIFICANT else WhitespacePolicy.LINE_BREAKS_SIGNIFICANT

    private fun nameMatches(element: PsiNamedElement, subject: Subject.Declaration): Boolean {
        if (subject.name.isEmpty()) return true
        return element.name == subject.name
    }

    private companion object {
        const val SCALA = "Scala"
        val LOG: Logger = Logger.getInstance(ScalaReferenceResolver::class.java)
    }
}
