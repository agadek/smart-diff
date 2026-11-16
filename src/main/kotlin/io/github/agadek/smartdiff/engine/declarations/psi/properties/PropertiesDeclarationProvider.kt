package io.github.agadek.smartdiff.engine.declarations.psi.properties

import com.intellij.lang.Language
import com.intellij.lang.properties.IProperty
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.lang.properties.psi.Property
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider

/**
 * Java properties declarations (languages/11-properties): each property is a top-level [DeclarationKind.KEY] named by
 * its unescaped key, flat and in source order.
 */
class PropertiesDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.INSIGNIFICANT) {

    override fun supports(languageId: String): Boolean = languageId == "Properties"

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile {
        val name = fileName ?: DEFAULT_NAME
        val language = Language.findLanguageByID("Properties") ?: error("Properties language is not available")
        return PsiFileFactory.getInstance(project).createFileFromText(name, language, text, false, false)
    }

    override fun topLevel(file: PsiFile): List<PsiElement> {
        val propertiesFile = file as? PropertiesFile ?: return emptyList()
        return propertiesFile.properties.mapNotNull { it.psiElement }
    }

    override fun describe(element: PsiElement): DeclarationInfo? {
        val iProperty: IProperty = when (element) {
            is IProperty -> element
            else -> return null
        }
        val name = iProperty.unescapedKey ?: iProperty.key ?: return null
        val psi = iProperty.psiElement ?: element
        val nameRange = psi.children.firstOrNull()?.textRange ?: psi.textRange
        return DeclarationInfo(
            kind = DeclarationKind.KEY,
            name = name,
            signature = null,
            members = emptyList(),
            nameRange = nameRange.let { TextRange(it.startOffset, it.endOffset) },
        )
    }

    private companion object {
        const val DEFAULT_NAME = "SmartDiff.properties"
    }
}
