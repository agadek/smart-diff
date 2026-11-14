package io.github.agadek.smartdiff.engine.declarations.psi.groovy

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierList
import com.intellij.psi.util.PsiTreeUtil
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider
import io.github.agadek.smartdiff.engine.declarations.psi.Signatures
import org.jetbrains.plugins.groovy.GroovyLanguage
import org.jetbrains.plugins.groovy.lang.groovydoc.psi.api.GrDocComment
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrField
import org.jetbrains.plugins.groovy.lang.psi.api.statements.params.GrParameter
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinition
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrAccessorMethod
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrClassInitializer
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrEnumConstant
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrTypeElement

/**
 * Groovy declarations (languages/08-groovy): types and their members, and script-level methods and classes. Closures,
 * anonymous classes, local variables and local classes are part of the declaration that contains them.
 */
class GroovyDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.LINE_BREAKS_SIGNIFICANT) {

    override fun supports(languageId: String): Boolean = languageId == GroovyLanguage.id

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile {
        val name = fileName?.takeIf(::groovyFileName) ?: FILE_NAME
        return PsiFileFactory.getInstance(project).createFileFromText(name, GroovyLanguage, text, false, false)
    }

    // In a script, top-level script methods and classes are declarations; statements and script variables stay in the file region.
    override fun topLevel(file: PsiFile): List<PsiElement> {
        val groovy = file as GroovyFile
        if (groovy.isScript) {
            return groovy.children.filter { it is GrMethod || it is GrTypeDefinition }
        }
        return groovy.typeDefinitions.toList()
    }

    // Errors in Groovydoc are markup, not parse errors (languages/08-groovy R9).
    override fun isParseError(error: PsiErrorElement): Boolean =
        PsiTreeUtil.getParentOfType(error, GrDocComment::class.java) == null

    override fun describe(element: PsiElement): DeclarationInfo? = when (element) {
        is GrEnumConstant -> DeclarationInfo(DeclarationKind.ENUM_CONSTANT, element.name, signature = null)
        is GrTypeDefinition -> describeType(element)
        is GrAccessorMethod -> null
        is GrMethod -> describeMethod(element)
        is GrField -> describeField(element)
        is GrClassInitializer -> {
            val name = if (element.isStatic) "static" else ""
            DeclarationInfo(DeclarationKind.INITIALIZER, name, signature = null)
        }
        else -> null
    }

    private fun describeType(type: GrTypeDefinition): DeclarationInfo? {
        val name = type.name?.takeIf { it.isNotEmpty() } ?: return null
        return DeclarationInfo(kind(type), name, signature = null, members(type))
    }

    private fun kind(type: GrTypeDefinition): DeclarationKind = when {
        runCatching { type.isTrait }.getOrDefault(false) -> DeclarationKind.TRAIT
        runCatching { type.isEnum }.getOrDefault(false) -> DeclarationKind.ENUM
        runCatching { type.isAnnotationType }.getOrDefault(false) -> DeclarationKind.ANNOTATION_TYPE
        runCatching { type.isInterface }.getOrDefault(false) -> DeclarationKind.INTERFACE
        runCatching { type.isRecord }.getOrDefault(false) -> DeclarationKind.RECORD
        else -> DeclarationKind.CLASS
    }

    private fun members(type: GrTypeDefinition): List<PsiElement> {
        val body = runCatching { type.body }.getOrNull() ?: return emptyList()
        val result = mutableListOf<PsiElement>()
        for (child in body.children) {
            when (child) {
                is GrEnumConstant -> result += child
                is GrMethod -> result += child
                is GrClassInitializer -> result += child
                is GrField -> result += child
                else -> {
                    // Field declarations can be wrapped (e.g. GrVariableDeclaration).
                    child.children.filterIsInstance<GrField>().forEach { result += it }
                    child.children.filterIsInstance<GrEnumConstant>().forEach { if (it !in result) result += it }
                }
            }
        }
        return result
    }

    private fun describeMethod(method: GrMethod): DeclarationInfo? {
        val name = method.name.takeIf { it.isNotEmpty() } ?: return null
        val kind = if (runCatching { method.isConstructor }.getOrDefault(false)) DeclarationKind.CONSTRUCTOR else DeclarationKind.METHOD
        val parameters = runCatching { method.parameters.toList() }.getOrDefault(emptyList())
        return DeclarationInfo(
            kind,
            name,
            Signatures.of(parameters.map(::parameterType)),
            displaySignature = Signatures.of(parameters.map(::parameterTypeLabel)),
            body = runCatching { method.block }.getOrNull(),
        )
    }

    private fun describeField(field: GrField): DeclarationInfo? {
        val name = field.name.takeIf { it.isNotEmpty() } ?: return null
        val kind = if (isProperty(field)) DeclarationKind.PROPERTY else DeclarationKind.FIELD
        return DeclarationInfo(kind, name, signature = null)
    }

    // A field without an explicit access modifier is a Groovy property (languages/08-groovy R4).
    private fun isProperty(field: GrField): Boolean {
        if (runCatching { field.isProperty }.getOrDefault(false)) return true
        val modifierList = runCatching { field.modifierList }.getOrNull() ?: return true
        return !hasVisibility(modifierList)
    }

    private fun hasVisibility(modifierList: PsiModifierList): Boolean =
        modifierList.hasModifierProperty(PsiModifier.PUBLIC) ||
            modifierList.hasModifierProperty(PsiModifier.PROTECTED) ||
            modifierList.hasModifierProperty(PsiModifier.PRIVATE)

    // An untyped parameter has no declared type element and appears as `def` (languages/08-groovy R7).
    private fun parameterType(parameter: GrParameter): String =
        typeElement(parameter)?.let { Signatures.text(it) { false } } ?: "def"

    private fun parameterTypeLabel(parameter: GrParameter): String =
        typeElement(parameter)?.let { Signatures.label(it) { false } } ?: "def"

    private fun typeElement(parameter: GrParameter): GrTypeElement? =
        runCatching { parameter.typeElementGroovy }.getOrNull()

    private fun groovyFileName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".groovy") || lower.endsWith(".gradle") || lower.endsWith(".gvy")
    }

    private companion object {
        const val FILE_NAME = "SmartDiff.groovy"
    }
}
