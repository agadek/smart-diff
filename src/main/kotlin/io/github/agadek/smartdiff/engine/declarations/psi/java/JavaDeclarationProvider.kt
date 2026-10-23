package io.github.agadek.smartdiff.engine.declarations.psi.java

import com.intellij.lang.Language
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.Project
import com.intellij.pom.java.LanguageLevel
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassInitializer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierList
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.util.JavaPsiRecordUtil
import com.intellij.psi.util.PsiUtil
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider
import io.github.agadek.smartdiff.engine.declarations.psi.Signatures

/**
 * Java declarations (technical specification §6.4): named classes and their members. Anonymous and local classes,
 * lambdas, local variables and record components are part of the declaration that contains them.
 */
class JavaDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.INSIGNIFICANT) {

    override fun supports(languageId: String): Boolean = languageId == JavaLanguage.INSTANCE.id

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile {
        val file = PsiFileFactory.getInstance(project).createFileFromText(FILE_NAME, JavaLanguage.INSTANCE, text, false, false)
        // Parse at the highest level the platform knows, not at the default project's level, which the user can change (§6.2).
        file.putUserData(PsiUtil.FILE_LANGUAGE_LEVEL_KEY, LanguageLevel.HIGHEST)
        return file
    }

    // A class without a name identifier is implicitly declared (a file of top-level methods and fields, JEP 512), or its
    // name was not parsed; either way its members are top-level declarations.
    override fun topLevel(file: PsiFile): List<PsiElement> =
        (file as PsiJavaFile).classes.flatMap { if (it.nameIdentifier == null) members(it) else listOf(it) }

    override fun describe(element: PsiElement): DeclarationInfo? = when (element) {
        is PsiAnonymousClass, is PsiTypeParameter -> null
        is PsiClass -> element.nameIdentifier?.let { DeclarationInfo(kind(element), it.text, signature = null, members(element)) }
        is PsiMethod -> element.name.takeIf { it.isNotEmpty() }?.let { name ->
            val kind = if (element.isConstructor) DeclarationKind.CONSTRUCTOR else DeclarationKind.METHOD
            // A compact record constructor writes no parameter list; the PSI gives it the record components.
            val parameters = if (JavaPsiRecordUtil.isCompactConstructor(element)) emptyList() else element.parameterList.parameters.toList()
            DeclarationInfo(
                kind, name, Signatures.of(parameters.map { parameterType(it, Signatures::text) }),
                displaySignature = Signatures.of(parameters.map { parameterType(it, Signatures::label) }),
                body = element.body,
            )
        }
        is PsiEnumConstant -> DeclarationInfo(DeclarationKind.ENUM_CONSTANT, element.name, signature = null)
        is PsiField -> DeclarationInfo(DeclarationKind.FIELD, element.name, signature = null)
        is PsiClassInitializer -> {
            val name = if (element.hasModifierProperty(PsiModifier.STATIC)) "static" else ""
            DeclarationInfo(DeclarationKind.INITIALIZER, name, signature = null)
        }
        else -> null
    }

    private fun kind(psiClass: PsiClass): DeclarationKind = when {
        psiClass.isAnnotationType -> DeclarationKind.ANNOTATION_TYPE
        psiClass.isInterface -> DeclarationKind.INTERFACE
        psiClass.isEnum -> DeclarationKind.ENUM
        psiClass.isRecord -> DeclarationKind.RECORD
        else -> DeclarationKind.CLASS
    }

    /** The members written directly in the class body, in source order; record components are in the header. */
    private fun members(psiClass: PsiClass): List<PsiElement> = psiClass.children.filter { it is PsiMember }

    /** The type as written, with C-style array brackets after the name, without modifiers, annotations or the name. */
    private fun parameterType(parameter: PsiParameter, text: (PsiElement, (PsiElement) -> Boolean) -> String): String {
        val name = parameter.nameIdentifier
        return text(parameter) { it is PsiModifierList || it is PsiAnnotation || it == name }
    }

    private companion object {
        const val FILE_NAME = "SmartDiff.java"
    }
}
