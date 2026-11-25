package io.github.agadek.smartdiff.engine.declarations.psi.sql

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
 * SQL declarations (languages/20-sql R3–R14). All SQL plugin PSI calls go through [SqlInterop]. Statements end with `;`
 * and a line break never ends one, so whitespace is insignificant as in Java; string literals and quoted identifiers are
 * tokens, so a change in their spacing is a change (details §8).
 */
class SqlDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.INSIGNIFICANT) {

    override val guardedFeature: GuardedFeature get() = GuardedFeature.SQL_PROVIDER

    override fun supports(languageId: String): Boolean = languageId == "SQL"

    // languages/20-sql R3: one dialect for every text of the comparison, the other side of a merge included, chosen by
    // how well each parses them (details §2).
    override fun dialect(project: Project, texts: List<CharSequence>, fileName: String?): Language? =
        SqlInterop.dialect(project, texts, fileName)

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile =
        SqlInterop.parse(project, text, fileName, dialect)

    override fun topLevel(file: PsiFile): List<PsiElement> = SqlInterop.topLevel(file)

    override fun describe(element: PsiElement): DeclarationInfo? {
        val d = SqlInterop.decl(element) ?: return null
        val kind = kind(d.kind) ?: return null
        return DeclarationInfo(
            kind = kind,
            name = d.name,
            signature = d.signatureKey,
            members = d.members,
            displaySignature = d.signatureDisplay,
            range = d.range,
        )
    }

    private fun kind(name: String): DeclarationKind? = when (name) {
        "TABLE" -> DeclarationKind.TABLE
        "VIEW" -> DeclarationKind.VIEW
        "COLUMN" -> DeclarationKind.COLUMN
        "INDEX" -> DeclarationKind.INDEX
        "SEQUENCE" -> DeclarationKind.SEQUENCE
        "FUNCTION" -> DeclarationKind.FUNCTION
        "PROCEDURE" -> DeclarationKind.PROCEDURE
        "TRIGGER" -> DeclarationKind.TRIGGER
        "TYPE" -> DeclarationKind.TYPE
        "SCHEMA" -> DeclarationKind.SCHEMA
        else -> null
    }
}
