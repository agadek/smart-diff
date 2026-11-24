package io.github.agadek.smartdiff.engine.declarations.psi.php

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.GuardedFeature
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider

/**
 * PHP declarations (languages/19-php R3–R14). All PHP plugin PSI calls go through [PhpInterop]. Statements end with
 * `;`, so whitespace is insignificant as in Java; string, heredoc and nowdoc contents are tokens, so a change in their
 * spacing is a change (details §8).
 */
class PhpDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.INSIGNIFICANT) {

    override val guardedFeature: GuardedFeature get() = GuardedFeature.PHP_PROVIDER

    override fun supports(languageId: String): Boolean = languageId == "PHP"

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile =
        PhpInterop.parse(project, text)

    override fun topLevel(file: PsiFile): List<PsiElement> = PhpInterop.topLevel(file)

    override fun describe(element: PsiElement): DeclarationInfo? {
        val d = PhpInterop.decl(element) ?: return null
        val kind = kind(d.kind) ?: return null
        return DeclarationInfo(
            kind = kind,
            name = d.name,
            signature = d.signatureKey,
            members = d.members,
            displaySignature = d.signatureDisplay,
            group = d.group,
            range = d.range,
            body = d.body,
        )
    }

    // relationships/08: the statements of function bodies and of the blocks nested in them can move (details §10).
    override val findsStatements: Boolean get() = true

    override fun isStatementBlock(element: PsiElement): Boolean = PhpInterop.isBlock(element)

    private fun kind(name: String): DeclarationKind? = when (name) {
        "NAMESPACE" -> DeclarationKind.NAMESPACE
        "CLASS" -> DeclarationKind.CLASS
        "INTERFACE" -> DeclarationKind.INTERFACE
        "TRAIT" -> DeclarationKind.TRAIT
        "ENUM" -> DeclarationKind.ENUM
        "ENUM_CONSTANT" -> DeclarationKind.ENUM_CONSTANT
        "FUNCTION" -> DeclarationKind.FUNCTION
        "METHOD" -> DeclarationKind.METHOD
        "CONSTRUCTOR" -> DeclarationKind.CONSTRUCTOR
        "PROPERTY" -> DeclarationKind.PROPERTY
        "CONSTANT" -> DeclarationKind.CONSTANT
        else -> null
    }
}
