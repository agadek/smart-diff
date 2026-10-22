package io.github.agadek.smartdiff.engine.declarations.psi.kotlin

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider
import io.github.agadek.smartdiff.engine.declarations.psi.Signatures
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtAnnotation
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.psi.KtScriptInitializer
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeReference

/**
 * Kotlin declarations (technical specification §6.5). Uses PSI only, no analysis API, so it works in K1 and K2 mode.
 * Local declarations, object literals and the bodies of enum entries are part of the declaration that contains them;
 * the primary constructor and its parameters are part of the class.
 */
class KotlinDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.LINE_BREAKS_SIGNIFICANT) {

    override fun supports(languageId: String): Boolean = languageId == KotlinLanguage.INSTANCE.id

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile {
        val name = if (fileName?.endsWith(".kts") == true) "SmartDiff.kts" else "SmartDiff.kt"
        return PsiFileFactory.getInstance(project).createFileFromText(name, KotlinLanguage.INSTANCE, text, false, false)
    }

    // A script's declarations are top-level declarations; its statements are part of the file region.
    override fun topLevel(file: PsiFile): List<PsiElement> =
        (file as KtFile).declarations.flatMap { if (it is KtScript) it.declarations else listOf(it) }

    override fun describe(element: PsiElement): DeclarationInfo? = when (element) {
        is KtEnumEntry -> element.name?.let { DeclarationInfo(DeclarationKind.ENUM_CONSTANT, it, signature = null) }
        is KtClass -> element.name?.let { DeclarationInfo(kind(element), it, signature = null, element.declarations) }
        is KtObjectDeclaration -> when {
            element.isObjectLiteral() -> null
            // The PSI already names an unnamed companion object "Companion".
            else -> element.name?.let { DeclarationInfo(DeclarationKind.OBJECT, it, signature = null, element.declarations) }
        }
        is KtNamedFunction -> element.name?.takeUnless { element.isLocal }?.let {
            DeclarationInfo(
                DeclarationKind.FUNCTION, it, functionSignature(element),
                displaySignature = parameterLabels(element), receiver = element.receiverTypeReference?.let(::typeLabel),
            )
        }
        is KtSecondaryConstructor -> DeclarationInfo(DeclarationKind.CONSTRUCTOR, "", parameters(element), displaySignature = parameterLabels(element))
        is KtProperty -> element.name?.takeUnless { element.isLocal }?.let {
            DeclarationInfo(
                DeclarationKind.PROPERTY, it, element.receiverTypeReference?.let(::type),
                displaySignature = null, receiver = element.receiverTypeReference?.let(::typeLabel),
            )
        }
        is KtTypeAlias -> element.name?.let { DeclarationInfo(DeclarationKind.TYPE_ALIAS, it, signature = null) }
        // A script's statements are wrapped in initializers; they are part of the file region.
        is KtScriptInitializer -> null
        is KtAnonymousInitializer -> DeclarationInfo(DeclarationKind.INITIALIZER, "", signature = null)
        else -> null
    }

    private fun kind(ktClass: KtClass): DeclarationKind = when {
        ktClass.isAnnotation() -> DeclarationKind.ANNOTATION_TYPE
        ktClass.isInterface() -> DeclarationKind.INTERFACE
        ktClass.isEnum() -> DeclarationKind.ENUM
        else -> DeclarationKind.CLASS
    }

    /** The receiver type followed by `.`, if there is one, then the parameter types: `List<T>.Int,String...`. */
    private fun functionSignature(function: KtNamedFunction): String =
        (function.receiverTypeReference?.let { type(it) + "." } ?: "") + parameters(function)

    /** A `vararg` parameter's type ends in `...`, as in Java, so `f(x: T)` and `f(vararg x: T)` stay apart. */
    private fun parameters(declaration: KtCallableDeclaration): String =
        Signatures.of(declaration.valueParameters.map { parameter ->
            (parameter.typeReference?.let(::type) ?: "") + if (parameter.isVarArg) "..." else ""
        })

    /** The parameter types for labels, as Kotlin writes them: `vararg Class<out Annotation>`. */
    private fun parameterLabels(declaration: KtCallableDeclaration): String =
        Signatures.of(declaration.valueParameters.map { parameter ->
            (if (parameter.isVarArg) "vararg " else "") + (parameter.typeReference?.let(::typeLabel) ?: "")
        })

    /** The type as written, without annotations; modifiers that are part of the type, such as `suspend`, stay. */
    private fun type(reference: KtTypeReference): String =
        Signatures.text(reference) { it is KtAnnotationEntry || it is KtAnnotation }

    private fun typeLabel(reference: KtTypeReference): String =
        Signatures.label(reference) { it is KtAnnotationEntry || it is KtAnnotation }
}
