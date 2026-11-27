package io.github.agadek.smartdiff.engine.declarations.psi.javascript

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.GuardedFeature
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.EquivalenceRuleImpl
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider

/**
 * JavaScript and TypeScript declarations (languages/16-javascript-typescript R2–R16), JSX and TSX included. All
 * JavaScript plugin PSI calls go through [JavaScriptInterop].
 */
class JavaScriptDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.INSIGNIFICANT) {

    override val guardedFeature: GuardedFeature get() = GuardedFeature.JAVASCRIPT_PROVIDER

    override fun supports(languageId: String): Boolean = languageId in JavaScriptInterop.LANGUAGE_IDS

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile =
        JavaScriptInterop.parse(project, text, fileName, null)

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?, languageId: String): PsiFile =
        JavaScriptInterop.parse(project, text, fileName, languageId)

    override fun topLevel(file: PsiFile): List<PsiElement> = JavaScriptInterop.topLevel(file)

    // R16: the statements of a block, such as a function's body (details §8).
    override val findsStatements: Boolean get() = true

    override fun isStatementBlock(element: PsiElement): Boolean = JavaScriptInterop.isStatementBlock(element)

    override fun isStatement(element: PsiElement): Boolean = JavaScriptInterop.isStatement(element)

    override fun describe(element: PsiElement): DeclarationInfo? {
        val d = JavaScriptInterop.decl(element) ?: return null
        val kind = kind(d.kind) ?: return null
        return DeclarationInfo(
            kind = kind,
            name = d.name,
            signature = d.signatureKey,
            members = d.members,
            displaySignature = d.signatureDisplay ?: d.signatureKey,
            group = d.group,
            range = d.range,
            body = d.body,
        )
    }

    override fun significantWhitespace(file: PsiFile): List<TextRange> = JavaScriptInterop.significantWhitespace(file)

    // declarations/11 R4 to R8, R20, R21
    override val equivalenceRules: List<EquivalenceRuleImpl> get() = JavaScriptEquivalenceRules.ALL

    private fun kind(name: String): DeclarationKind? = when (name) {
        "FUNCTION" -> DeclarationKind.FUNCTION
        "CLASS" -> DeclarationKind.CLASS
        "METHOD" -> DeclarationKind.METHOD
        "CONSTRUCTOR" -> DeclarationKind.CONSTRUCTOR
        "FIELD" -> DeclarationKind.FIELD
        "VARIABLE" -> DeclarationKind.VARIABLE
        "INTERFACE" -> DeclarationKind.INTERFACE
        "PROPERTY" -> DeclarationKind.PROPERTY
        "TYPE_ALIAS" -> DeclarationKind.TYPE_ALIAS
        "ENUM" -> DeclarationKind.ENUM
        "ENUM_CONSTANT" -> DeclarationKind.ENUM_CONSTANT
        "NAMESPACE" -> DeclarationKind.NAMESPACE
        "MODULE" -> DeclarationKind.MODULE
        else -> null
    }
}
