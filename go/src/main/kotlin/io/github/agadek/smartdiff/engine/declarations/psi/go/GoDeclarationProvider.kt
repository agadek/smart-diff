package io.github.agadek.smartdiff.engine.declarations.psi.go

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.GuardedFeature
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider

/**
 * Go declarations (languages/17-go R3–R14). All Go plugin PSI calls go through [GoInterop]. A line break can end a Go
 * statement, so line breaks are significant and spaces are not (details §8).
 */
class GoDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.LINE_BREAKS_SIGNIFICANT) {

    override val guardedFeature: GuardedFeature get() = GuardedFeature.GO_PROVIDER

    override fun supports(languageId: String): Boolean = languageId == "go"

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile =
        GoInterop.parse(project, text)

    override fun topLevel(file: PsiFile): List<PsiElement> = GoInterop.topLevel(file)

    override fun describe(element: PsiElement): DeclarationInfo? {
        val d = GoInterop.decl(element) ?: return null
        val kind = kind(d.kind) ?: return null
        return DeclarationInfo(
            kind = kind,
            name = d.name,
            signature = d.signatureKey,
            members = d.members,
            displaySignature = d.signatureDisplay,
            receiver = d.receiver,
            range = d.range,
            body = d.body,
        )
    }

    /**
     * An empty side, as of a new or deleted file, has no package clause; the missing clause is no parse error there
     * (details §9). Every other error is.
     */
    override fun isParseError(error: PsiErrorElement): Boolean = !blank(error.containingFile)

    private fun blank(file: PsiFile): Boolean {
        var code = false
        PsiTreeUtil.processElements(file) { element ->
            if (element.firstChild == null && element !is PsiWhiteSpace && element !is PsiComment && element.textLength > 0) code = true
            !code
        }
        return !code
    }

    // relationships/08: the statements of function bodies and of the blocks nested in them can move (details §10).
    override val findsStatements: Boolean get() = true

    override fun isStatementBlock(element: PsiElement): Boolean = GoInterop.isBlock(element)

    private fun kind(name: String): DeclarationKind? = when (name) {
        "FUNCTION" -> DeclarationKind.FUNCTION
        "METHOD" -> DeclarationKind.METHOD
        "STRUCT" -> DeclarationKind.STRUCT
        "INTERFACE" -> DeclarationKind.INTERFACE
        "TYPE_ALIAS" -> DeclarationKind.TYPE_ALIAS
        "TYPE" -> DeclarationKind.TYPE
        "FIELD" -> DeclarationKind.FIELD
        "CONSTANT" -> DeclarationKind.CONSTANT
        "VARIABLE" -> DeclarationKind.VARIABLE
        else -> null
    }
}
