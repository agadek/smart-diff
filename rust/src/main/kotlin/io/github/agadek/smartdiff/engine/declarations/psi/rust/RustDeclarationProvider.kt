package io.github.agadek.smartdiff.engine.declarations.psi.rust

import io.github.agadek.smartdiff.engine.api.GuardedFeature
import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider

/**
 * Rust declarations (languages/05-rust R3–R14). All Rust plugin PSI calls go through [RustInterop].
 */
class RustDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.INSIGNIFICANT) {

    override val guardedFeature: GuardedFeature get() = GuardedFeature.RUST_PROVIDER

    override fun supports(languageId: String): Boolean = languageId == "Rust"

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile =
        RustInterop.parse(project, text)

    override fun topLevel(file: PsiFile): List<PsiElement> = RustInterop.topLevel(file)

    override fun describe(element: PsiElement): DeclarationInfo? {
        val d = RustInterop.decl(element) ?: return null
        val kind = kind(d.kind) ?: return null
        return DeclarationInfo(
            kind = kind,
            name = d.name,
            signature = d.signatureKey,
            members = d.members,
            displaySignature = d.signatureDisplay ?: d.signatureKey,
            body = d.body,
            range = d.range,
        )
    }

    override fun significantWhitespace(file: PsiFile): List<TextRange> = RustInterop.significantWhitespace(file)

    private fun kind(name: String): DeclarationKind? = when (name) {
        "FUNCTION" -> DeclarationKind.FUNCTION
        "STRUCT" -> DeclarationKind.STRUCT
        "UNION" -> DeclarationKind.UNION
        "ENUM" -> DeclarationKind.ENUM
        "VARIANT" -> DeclarationKind.VARIANT
        "FIELD" -> DeclarationKind.FIELD
        "TRAIT" -> DeclarationKind.TRAIT
        "IMPL" -> DeclarationKind.IMPL
        "MODULE" -> DeclarationKind.MODULE
        "TYPE_ALIAS" -> DeclarationKind.TYPE_ALIAS
        "CONSTANT" -> DeclarationKind.CONSTANT
        "STATIC" -> DeclarationKind.STATIC
        "MACRO" -> DeclarationKind.MACRO
        else -> null
    }
}
