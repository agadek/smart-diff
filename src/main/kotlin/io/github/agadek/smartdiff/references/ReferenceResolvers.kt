package io.github.agadek.smartdiff.references

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.agadek.smartdiff.engine.api.ApiGuard
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.GuardedFeature
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy

/**
 * The per-language resolvers, in the order they are registered (relationships/04 R14). A resolver with a
 * [ReferenceResolver.guardedFeature] runs through [ApiGuard] (platform/01 R12).
 */
internal object ReferenceResolvers {
    val all: List<ReferenceResolver> get() = ReferenceResolver.EP_NAME.extensionList.map(::guarded)

    /** [resolver] through [ApiGuard] when it needs an optional-plugin API; otherwise [resolver] itself. */
    fun guarded(resolver: ReferenceResolver): ReferenceResolver =
        resolver.guardedFeature?.let { GuardedReferenceResolver(resolver, it) } ?: resolver
}

/**
 * A resolver whose feature [ApiGuard] turns off when a missing class, method or field reaches it. A resolver that is off
 * supports no language and resolves nothing, so a rename keeps no grouped usages, as for an unresolved usage
 * (platform/01 R12).
 */
internal class GuardedReferenceResolver(
    private val delegate: ReferenceResolver,
    override val guardedFeature: GuardedFeature,
) : ReferenceResolver {

    private fun <T> guard(fallback: () -> T, block: () -> T): T = ApiGuard.run(guardedFeature, fallback, block)

    override fun supports(languageId: String): Boolean = guard({ false }) { delegate.supports(languageId) }

    override fun target(file: PsiFile, range: TextRange, subject: Subject.Declaration): PsiElement? =
        guard({ null }) { delegate.target(file, range, subject) }

    override fun usages(file: PsiFile, target: PsiElement, cancellation: Cancellation): List<TextRange> =
        guard({ emptyList() }) { delegate.usages(file, target, cancellation) }

    override fun usages(file: PsiFile, targets: Set<PsiElement>, cancellation: Cancellation): Map<PsiElement, List<TextRange>> =
        guard({ emptyMap() }) { delegate.usages(file, targets, cancellation) }

    override fun context(file: PsiFile, usage: TextRange): TextRange? = guard({ null }) { delegate.context(file, usage) }

    override fun whitespacePolicy(file: PsiFile): WhitespacePolicy =
        guard({ WhitespacePolicy.SIGNIFICANT }) { delegate.whitespacePolicy(file) }
}
