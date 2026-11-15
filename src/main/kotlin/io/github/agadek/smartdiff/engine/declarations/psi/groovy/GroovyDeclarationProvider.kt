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
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.LexedTokens
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationNode
import io.github.agadek.smartdiff.engine.declarations.psi.PsiTreeParts
import io.github.agadek.smartdiff.engine.declarations.psi.Signatures
import org.jetbrains.plugins.groovy.GroovyLanguage
import org.jetbrains.plugins.groovy.lang.groovydoc.psi.api.GrDocComment
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrClassInitializer
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrField
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrCall
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrLiteral
import org.jetbrains.plugins.groovy.lang.psi.api.statements.params.GrParameter
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrAnonymousClassDefinition
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinition
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrAccessorMethod
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

    override fun build(file: PsiFile, cancellation: Cancellation): io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree {
        if (isGradleBuildScript(file.name)) return buildWithTrailingSeparators(file, cancellation)
        return super.build(file, cancellation)
    }

    private fun buildWithTrailingSeparators(file: PsiFile, cancellation: Cancellation): io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree {
        val text = file.viewProvider.contents
        fun node(element: PsiElement): Node? {
            cancellation.checkCanceled()
            val info = describe(element) ?: return null
            val range = info.range ?: element.textRange.let { TextRange(it.startOffset, it.endOffset) }
            if (range.length == 0) return null
            return Node(info, range, info.members.mapNotNull(::node))
        }
        val topLevel = topLevel(file).mapNotNull(::node)
        val fileRegionError = PsiTreeParts.markParseErrors(file, topLevel, cancellation, ::isParseError)
        val significant = significantWhitespace(file)
        val tokens = LexedTokens.lex(createLexer(file), parserDefinition(file), text, cancellation, ::tokenKind)
        PsiTreeParts.assignLeading(topLevel, tokens, isLayout = ::isLayoutToken)
        assignTrailingSeparators(topLevel, tokens, text.length)

        fun declaration(node: Node): io.github.agadek.smartdiff.engine.declarations.model.Declaration {
            cancellation.checkCanceled()
            val leading = node.leading?.let { tokens.own(it, emptyList(), significant) }.orEmpty()
            val leadingStarts = node.leading?.let { tokens.ownStarts(it, emptyList()) }.orEmpty()
            val excluded = node.children.map { it.extent }
            return io.github.agadek.smartdiff.engine.declarations.model.Declaration(
                kind = node.info.kind,
                name = node.info.name,
                signature = node.info.signature,
                range = node.range,
                children = node.children.map(::declaration),
                ownTokens = leading + tokens.own(node.range, excluded, significant),
                hasParseError = node.parseError,
                leadingWhitespace = node.leading,
                displaySignature = node.info.displaySignature,
                receiver = node.info.receiver,
                ownTokenStarts = leadingStarts + tokens.ownStarts(node.range, excluded),
                body = node.info.body?.let(::bodyRange),
                nameRange = node.info.nameRange,
                layout = node.trailing,
            )
        }
        val whole = TextRange(0, text.length)
        val covered = topLevel.map { node ->
            val start = node.leading?.start ?: node.range.start
            val end = node.trailing?.end ?: node.range.end
            TextRange(start, end)
        }
        val fileRegion = io.github.agadek.smartdiff.engine.declarations.model.FileRegion(
            ranges = PsiTreeParts.gaps(whole, covered),
            tokens = tokens.own(whole, covered, significant),
            hasParseError = fileRegionError,
        )
        return io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree(topLevel.map(::declaration), fileRegion, whitespacePolicy(file))
    }

    // Between top-level script declarations the separator is trailing layout of the previous declaration, so adding,
    // removing or moving one leaves the others unchanged (languages/08-groovy R21; declarations/02-change-classification R14).
    private fun assignTrailingSeparators(nodes: List<Node>, tokens: LexedTokens, containerEnd: Int) {
        for ((index, node) in nodes.withIndex()) {
            val limit = nodes.getOrNull(index + 1)?.range?.start ?: containerEnd
            val end = tokens.layoutEnd(node.range.end, limit, ::isLayoutToken)
            if (end > node.range.end) node.trailing = TextRange(node.range.end, end)
            val next = nodes.getOrNull(index + 1) ?: continue
            val leading = next.leading ?: continue
            if (leading.start < end) {
                next.leading = if (end < leading.end) TextRange(end, leading.end) else null
            }
        }
    }

    private fun isLayoutToken(token: Token): Boolean =
        token.kind == TokenKind.WHITESPACE || token.text.all { it.isWhitespace() }

    /** The inside of a block's braces, or the whole of any other body. */
    private fun bodyRange(element: PsiElement): TextRange {
        val range = element.textRange
        val first = element.firstChild
        val last = element.lastChild
        return if (first != null && last != null && first !== last && first.text == "{" && last.text == "}") {
            TextRange(first.textRange.endOffset, last.textRange.startOffset)
        } else {
            TextRange(range.startOffset, range.endOffset)
        }
    }

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile {
        val name = when {
            fileName != null && isGradleBuildScript(fileName) -> "SmartDiff.gradle"
            fileName?.let(::groovyFileName) == true -> fileName
            else -> FILE_NAME
        }
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
        is GrCall -> describeSectionCall(element)
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

    // languages/08-groovy R11-R13: a trailing closure argument, or a closure as the last argument inside the
    // parentheses, makes a top-level call a build-script section; command forms pass the call as an argument.
    private fun isSectionCall(call: GrCall): Boolean {
        if (call.closureArguments.isNotEmpty()) return true
        val args = callArguments(call)
        if (args.lastOrNull() is GrClosableBlock) return true
        return isCommandSectionCall(call)
    }

    private fun isCommandSectionCall(call: GrCall): Boolean {
        if (call.closureArguments.isNotEmpty()) return false
        val args = callArguments(call).filterNot { it is GrClosableBlock }
        return args.size == 1 && args.single().let { it is GrCall && isSectionCall(it) }
    }

    private fun callArguments(call: GrCall): List<GrExpression> =
        call.argumentList?.expressionArguments?.toList() ?: call.expressionArguments.toList()

    private fun describeSectionCall(call: GrCall): DeclarationInfo? {
        if (!isSectionCall(call)) return null
        return DeclarationInfo(DeclarationKind.SECTION, sectionName(call), signature = null)
    }

    private fun sectionName(call: GrCall): String {
        if (isCommandSectionCall(call)) {
            val inner = callArguments(call).filterNot { it is GrClosableBlock }.single() as GrCall
            return "${calleeName(call)} ${sectionName(inner)}".trim()
        }
        val callee = calleeName(call)
        val argument = nameArgument(call) ?: return callee
        return "$callee $argument"
    }

    /** Receiver chain names joined with `.`, without argument lists or type arguments, then the called name. */
    private fun calleeName(call: GrCall): String {
        val invoked = (call as? GrMethodCall)?.invokedExpression ?: return stripTypeArguments(call.text.substringBefore('{').trim())
        return stripTypeArguments(calleeExpressionName(invoked))
    }

    private fun stripTypeArguments(text: String): String {
        var result = text
        while (true) {
            val stripped = result.replace(TYPE_ARGUMENTS, "")
            if (stripped == result) return result
            result = stripped
        }
    }

    private fun calleeExpressionName(expression: GrExpression): String = when (expression) {
        is GrReferenceExpression -> {
            val name = expression.referenceName ?: expression.text
            val qualifier = expression.qualifierExpression
            if (qualifier != null) "${calleeExpressionName(qualifier)}.$name" else name
        }
        is GrMethodCall -> calleeName(expression)
        is GrCall -> (expression as? GrMethodCall)?.invokedExpression?.let(::calleeExpressionName)
            ?: expression.text.substringBefore('(').substringBefore('{').trim()
        else -> expression.text
    }

    /** The name argument of the last call: first string-literal argument when present, otherwise absent. */
    private fun nameArgument(call: GrCall): String? {
        val first = callArguments(call).firstOrNull() ?: return null
        if (first is GrClosableBlock) return null
        stringLiteralValue(first)?.let { return it }
        // Command forms such as `task hello { }` pass the name as a non-string argument (languages/08-groovy R13).
        if (!isCommandSectionCall(call)) return null
        return argumentText(first)
    }

    private fun argumentText(expression: GrExpression): String = when (expression) {
        is GrReferenceExpression -> expression.referenceName ?: expression.text
        is GrCall -> calleeName(expression)
        else -> expression.text
    }

    private fun stringLiteralValue(expression: GrExpression): String? {
        val literal = expression as? GrLiteral ?: return null
        return literal.value as? String
    }

    private fun groovyFileName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".groovy") || lower.endsWith(".gradle") || lower.endsWith(".gvy")
    }

    private fun isGradleBuildScript(name: String): Boolean = name.lowercase().endsWith(".gradle")

    private class Node(
        val info: DeclarationInfo,
        override val range: TextRange,
        override val children: List<Node>,
    ) : PsiDeclarationNode {
        override var parseError = false
        override var leading: TextRange? = null
        var trailing: TextRange? = null
    }

    private companion object {
        const val FILE_NAME = "SmartDiff.groovy"
        val TYPE_ARGUMENTS = Regex("<[^<>]*>")
    }
}
