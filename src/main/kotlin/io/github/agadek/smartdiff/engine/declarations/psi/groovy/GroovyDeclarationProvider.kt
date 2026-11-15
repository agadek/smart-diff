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
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrCall
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrLiteral
import org.jetbrains.plugins.groovy.lang.psi.api.statements.params.GrParameter
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrAnonymousClassDefinition
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinition
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrAccessorMethod
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrClassInitializer
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrEnumConstant
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrTypeElement

/**
 * Groovy declarations (languages/08-groovy): types and their members, and script-level methods and classes. Closures,
 * anonymous classes, local variables and local classes are part of the declaration that contains them. In Gradle build
 * scripts, top-level calls with a closure are section declarations.
 */
class GroovyDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.LINE_BREAKS_SIGNIFICANT) {

    override fun supports(languageId: String): Boolean = languageId == GroovyLanguage.id

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile {
        val name = fileName?.takeIf(::groovyFileName) ?: FILE_NAME
        return PsiFileFactory.getInstance(project).createFileFromText(name, GroovyLanguage, text, false, false)
    }

    // In a script, top-level script methods, classes and build-script section calls are declarations in source order.
    override fun topLevel(file: PsiFile): List<PsiElement> {
        val groovy = file as GroovyFile
        if (groovy.isScript) {
            val buildScript = isGradleBuildScript(file.name)
            return groovy.children.filter { child ->
                when (child) {
                    is GrMethod, is GrTypeDefinition -> true
                    is GrCall -> buildScript && isSectionCall(child)
                    is GrExpression -> buildScript && isFallbackSectionExpression(child)
                    else -> false
                }
            }
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
        is GrCall -> if (element is GrMethodCall) describeSection(element) else describeSectionCall(element)
        is GrExpression -> describeFallbackSection(element)
        else -> null
    }

    private fun describeType(type: GrTypeDefinition): DeclarationInfo? {
        val name = type.name?.takeIf { it.isNotEmpty() } ?: return null
        return DeclarationInfo(kind(type), name, signature = null, members(type))
    }

    private fun kind(type: GrTypeDefinition): DeclarationKind = when {
        type.isTrait -> DeclarationKind.TRAIT
        type.isEnum -> DeclarationKind.ENUM
        type.isAnnotationType -> DeclarationKind.ANNOTATION_TYPE
        type.isInterface -> DeclarationKind.INTERFACE
        else -> DeclarationKind.CLASS
    }

    private fun members(type: GrTypeDefinition): List<PsiElement> {
        val body = type.body ?: return emptyList()
        val result = mutableListOf<PsiElement>()
        for (child in body.children) {
            when (child) {
                is GrEnumConstant -> result += child
                is GrMethod -> result += child
                is GrClassInitializer -> result += child
                is GrField -> result += child
                is GrAnonymousClassDefinition -> Unit
                is GrTypeDefinition -> result += child
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
        val kind = if (method.isConstructor) DeclarationKind.CONSTRUCTOR else DeclarationKind.METHOD
        val parameters = method.parameters.toList()
        return DeclarationInfo(
            kind,
            name,
            Signatures.of(parameters.map(::parameterType)),
            displaySignature = Signatures.of(parameters.map(::parameterTypeLabel)),
            body = method.block,
        )
    }

    private fun describeField(field: GrField): DeclarationInfo? {
        val name = field.name.takeIf { it.isNotEmpty() } ?: return null
        val kind = if (isProperty(field)) DeclarationKind.PROPERTY else DeclarationKind.FIELD
        return DeclarationInfo(kind, name, signature = null)
    }

    // A field without an explicit access modifier is a Groovy property (languages/08-groovy R4).
    private fun isProperty(field: GrField): Boolean {
        if (field.isProperty) return true
        val modifierList = field.modifierList ?: return true
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

    private fun typeElement(parameter: GrParameter): GrTypeElement? = parameter.typeElementGroovy

    // languages/08-groovy R11-R13: top-level method calls with a trailing closure are build-script sections.
    private fun isSectionCall(call: GrCall): Boolean {
        if (call.closureArguments.isNotEmpty()) return true
        val args = call.argumentList
        if (args != null) {
            if (args.allArguments.any { it is org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock }) return true
            if (args.expressionArguments.any { it is org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock }) return true
        }
        if (call.expressionArguments.lastOrNull() is org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock) return true
        // Command forms like `task hello { }` can be parsed with the task call as an argument (languages/08-groovy R13).
        return call.expressionArguments.size == 1 && call.expressionArguments.single() is GrCall &&
            isSectionCall(call.expressionArguments.single() as GrCall)
    }

    private fun isFallbackSectionExpression(expression: GrExpression): Boolean =
        expression !is GrCall && com.intellij.psi.util.PsiTreeUtil.findChildOfType(
            expression,
            org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock::class.java,
        ) != null

    private fun describeSection(call: GrMethodCall): DeclarationInfo? = describeSectionCall(call)

    private fun describeFallbackSection(expression: GrExpression): DeclarationInfo? {
        if (!isFallbackSectionExpression(expression)) return null
        return DeclarationInfo(DeclarationKind.SECTION, fallbackSectionName(expression), signature = null)
    }

    private fun describeSectionCall(call: GrCall): DeclarationInfo? {
        if (!isSectionCall(call)) return null
        return DeclarationInfo(DeclarationKind.SECTION, sectionName(call), signature = null)
    }

    private fun sectionName(call: GrCall): String {
        // Command forms like `task hello { }`: outer call has the keyword, inner call has the name and closure.
        if (call.closureArguments.isEmpty() && call.expressionArguments.size == 1) {
            val inner = call.expressionArguments.single() as? GrCall
            if (inner != null && isSectionCall(inner)) {
                val outer = calleeName(call)
                return "$outer ${sectionName(inner)}".trim()
            }
        }
        val callee = calleeName(call)
        val argument = nameArgument(call) ?: return callee
        return "$callee $argument"
    }

    private fun calleeName(call: GrCall): String {
        val invoked = (call as? GrMethodCall)?.invokedExpression
        if (invoked is GrReferenceExpression) return referenceName(invoked)
        val text = invoked?.text ?: call.text
        return text.substringBefore('(').substringBefore('{').substringBefore('<').trim()
    }

    private fun fallbackSectionName(expression: GrExpression): String {
        // Fallback for misparsed build-script calls with a closure, e.g. a generic call parsed as a relational expression.
        val text = expression.text
        val beforeBlock = text.substringBefore('{').trim()
        val callee = beforeBlock.substringBefore('(').substringBefore('<').trim()
        val arg = Regex("""['"]([^'"]*)['"]""").find(beforeBlock)?.groupValues?.get(1)
        return if (arg != null) "$callee $arg" else callee
    }

    private fun referenceName(reference: GrReferenceExpression): String {
        val qualifier = reference.qualifierExpression
        val name = reference.referenceName ?: reference.text.substringBefore('<')
        return if (qualifier != null) "${qualifier.text}.$name" else name
    }

    private fun nameArgument(call: GrCall): String? {
        val first = call.expressionArguments.firstOrNull() ?: return null
        if (first is org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock) return null
        stringLiteralValue(first)?.let { return it }
        // Command forms such as `task hello { }` pass the name as a non-string argument (languages/08-groovy R13).
        return when (first) {
            is GrReferenceExpression -> first.referenceName ?: first.text
            is GrCall -> (first as? GrMethodCall)?.invokedExpression?.let { (it as? GrReferenceExpression)?.referenceName ?: it.text } ?: first.text.substringBefore('(').trim()
            else -> first.text
        }
    }

    private fun stringLiteralValue(expression: GrExpression): String? = when (expression) {
        is GrLiteral -> if (expression.value is String) expression.value as String else null
        else -> {
            // GString literals with no injections are treated as plain string names.
            val text = expression.text
            if (text.length >= 2) {
                val quote = text.first()
                if ((quote == '"' || quote == '\'') && text.last() == quote && '$' !in text) {
                    text.substring(1, text.length - 1)
                } else null
            } else null
        }
    }

    private fun groovyFileName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".groovy") || lower.endsWith(".gradle") || lower.endsWith(".gvy")
    }

    private fun isGradleBuildScript(name: String): Boolean = name.lowercase().endsWith(".gradle")

    private companion object {
        const val FILE_NAME = "SmartDiff.groovy"
    }
}
