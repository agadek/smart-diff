package io.github.agadek.smartdiff.references

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy

/**
 * Resolves same-file usages for one language (relationships/04 R14). The reference pass parses both sides with the
 * declaration provider and asks the resolver for the declaration a relationship endpoint names, every usage that
 * resolves to it, and the smallest expression or statement around a usage.
 *
 * Step 1 adds the Java resolver; Kotlin K2 and Scala add their own resolver in step 2 without changing the pass.
 */
internal interface ReferenceResolver {
    /** Whether this resolver handles [languageId], an IntelliJ `Language.getID()`. */
    fun supports(languageId: String): Boolean

    /**
     * The declaration at [range] whose subject is [subject], or null when the light copy does not hold it. The endpoint
     * range identifies the declaration; [subject] tells a constructor and its class apart.
     */
    fun target(file: PsiFile, range: TextRange, subject: Subject.Declaration): PsiElement?

    /**
     * Every usage of [target] in [file], as the usage's own name range, in source order. A reference that does not
     * resolve, or resolves to another declaration, is left out. The resolver checks [cancellation] before and after
     * every resolve, and lets its exception propagate.
     */
    fun usages(file: PsiFile, target: PsiElement, cancellation: Cancellation): List<TextRange>

    /**
     * The usages of every target in [targets], resolved once per reference where the language can. The default calls
     * [usages] for each target; a resolver with an expensive reference walk overrides it to resolve each reference once.
     */
    fun usages(file: PsiFile, targets: Set<PsiElement>, cancellation: Cancellation): Map<PsiElement, List<TextRange>> =
        targets.associateWith { usages(file, it, cancellation) }

    /**
     * The smallest expression containing [usage], or the enclosing statement or member when the language has no
     * expression node; null when no such context exists.
     */
    fun context(file: PsiFile, usage: TextRange): TextRange?

    /** The language's whitespace policy, as the declaration engine built its tree with. */
    fun whitespacePolicy(file: PsiFile): WhitespacePolicy
}
