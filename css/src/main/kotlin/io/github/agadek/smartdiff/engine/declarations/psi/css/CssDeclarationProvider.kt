package io.github.agadek.smartdiff.engine.declarations.psi.css

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.GuardedFeature
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider

/**
 * CSS, SCSS and Less declarations (languages/18-css R2–R18). All CSS plugin PSI calls go through [CssInterop].
 * Whitespace is insignificant except for descendant combinators and around `+` and `-` operators (details §8).
 */
class CssDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.INSIGNIFICANT) {

    override val guardedFeature: GuardedFeature get() = GuardedFeature.CSS_PROVIDER

    override fun supports(languageId: String): Boolean = languageId in CssInterop.LANGUAGE_IDS

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile =
        CssInterop.parse(project, text, languageOf(fileName))

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?, languageId: String): PsiFile =
        CssInterop.parse(project, text, languageId.takeIf { it in CssInterop.LANGUAGE_IDS } ?: languageOf(fileName))

    override fun topLevel(file: PsiFile): List<PsiElement> = CssInterop.topLevel(file)

    override fun describe(element: PsiElement): DeclarationInfo? {
        val d = CssInterop.decl(element) ?: return null
        val kind = kind(d.kind) ?: return null
        return DeclarationInfo(
            kind = kind,
            name = d.name,
            signature = d.signatureKey,
            members = d.members,
            displaySignature = d.signatureDisplay,
            range = d.range,
            body = d.body,
        )
    }

    override fun significantWhitespace(file: PsiFile): List<TextRange> = CssInterop.significantWhitespace(file)

    // relationships/08: the properties of a block can move (details §10).
    override val findsStatements: Boolean get() = true

    override fun isStatementBlock(element: PsiElement): Boolean = CssInterop.isBlock(element)

    private fun languageOf(fileName: String?): String = when (fileName?.substringAfterLast('.', "")?.lowercase()) {
        "scss" -> "SCSS"
        "less" -> "LESS"
        else -> "CSS"
    }

    private fun kind(name: String): DeclarationKind? = when (name) {
        "RULE" -> DeclarationKind.RULE
        "AT_RULE" -> DeclarationKind.AT_RULE
        "MIXIN" -> DeclarationKind.MIXIN
        "FUNCTION" -> DeclarationKind.FUNCTION
        "VARIABLE" -> DeclarationKind.VARIABLE
        else -> null
    }
}
