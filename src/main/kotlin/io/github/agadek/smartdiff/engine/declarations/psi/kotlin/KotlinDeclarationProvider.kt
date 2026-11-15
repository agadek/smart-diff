package io.github.agadek.smartdiff.engine.declarations.psi.kotlin

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.LexedTokens
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationNode
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider
import io.github.agadek.smartdiff.engine.declarations.psi.PsiTreeParts
import io.github.agadek.smartdiff.engine.declarations.psi.Signatures
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtAnnotation
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.psi.KtScriptInitializer
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeReference

/**
 * Kotlin declarations (technical specification §6.5). Uses PSI only, no analysis API, so it works in K1 and K2 mode.
 * Local declarations, object literals and the bodies of enum entries are part of the declaration that contains them;
 * the primary constructor and its parameters are part of the class. In Gradle Kotlin build scripts, top-level calls
 * with a trailing lambda are section declarations.
 */
class KotlinDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.LINE_BREAKS_SIGNIFICANT) {

    override fun supports(languageId: String): Boolean = languageId == KotlinLanguage.INSTANCE.id

    override fun build(file: PsiFile, cancellation: io.github.agadek.smartdiff.engine.api.Cancellation): io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree {
        if (isGradleKtsBuildScript(file.name)) return buildWithTrailingSeparators(file, cancellation)
        return super.build(file, cancellation)
    }

    private fun buildWithTrailingSeparators(
        file: PsiFile,
        cancellation: io.github.agadek.smartdiff.engine.api.Cancellation,
    ): io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree {
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
    // removing or moving one leaves the others unchanged (languages/02-kotlin R22; declarations/02-change-classification R14).
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

    private class Node(
        val info: DeclarationInfo,
        override val range: TextRange,
        override val children: List<Node>,
    ) : PsiDeclarationNode {
        override var parseError = false
        override var leading: TextRange? = null
        var trailing: TextRange? = null
    }

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile {
        val name = when {
            fileName != null && isGradleKtsBuildScript(fileName) -> "SmartDiff.gradle.kts"
            fileName?.endsWith(".kts") == true -> "SmartDiff.kts"
            else -> "SmartDiff.kt"
        }
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
                body = element.bodyBlockExpression ?: element.bodyExpression,
            )
        }
        is KtSecondaryConstructor -> DeclarationInfo(
            DeclarationKind.CONSTRUCTOR, "", parameters(element), displaySignature = parameterLabels(element), body = element.bodyExpression,
        )
        is KtProperty -> element.name?.takeUnless { element.isLocal }?.let {
            DeclarationInfo(
                DeclarationKind.PROPERTY, it, element.receiverTypeReference?.let(::type),
                displaySignature = null, receiver = element.receiverTypeReference?.let(::typeLabel),
            )
        }
        is KtTypeAlias -> element.name?.let { DeclarationInfo(DeclarationKind.TYPE_ALIAS, it, signature = null) }
        // In a .gradle.kts build script, a top-level call with a trailing lambda is a section (languages/02-kotlin R13).
        is KtScriptInitializer -> describeSection(element)
        is KtAnonymousInitializer -> DeclarationInfo(DeclarationKind.INITIALIZER, "", signature = null)
        else -> null
    }

    private fun kind(ktClass: KtClass): DeclarationKind = when {
        ktClass.isAnnotation() -> DeclarationKind.ANNOTATION_TYPE
        ktClass.isInterface() -> DeclarationKind.INTERFACE
        ktClass.isEnum() -> DeclarationKind.ENUM
        else -> DeclarationKind.CLASS
    }

    private fun describeSection(initializer: KtScriptInitializer): DeclarationInfo? {
        if (!isGradleKtsBuildScript(initializer.containingFile.name)) return null
        val body = initializer.body ?: return null
        val call = callWithTrailingLambda(body) ?: return null
        return DeclarationInfo(DeclarationKind.SECTION, sectionName(call), signature = null)
    }

    private fun callWithTrailingLambda(expression: KtExpression): KtCallExpression? {
        return when (expression) {
            is KtCallExpression -> if (expression.lambdaArguments.isNotEmpty()) expression else null
            is KtQualifiedExpression -> callWithTrailingLambda(expression.selectorExpression ?: return null)
            else -> null
        }
    }

    private fun sectionName(call: KtCallExpression): String {
        val callee = calleeName(call)
        val argument = nameArgument(call) ?: return callee
        return "$callee $argument"
    }

    /** Receiver chain names joined with `.`, without argument lists or type arguments, then the called name. */
    private fun calleeName(call: KtCallExpression): String {
        val qualified = call.parent as? KtQualifiedExpression
        val callee = if (qualified != null && qualified.selectorExpression == call) {
            calleeExpressionName(qualified)
        } else {
            call.calleeExpression?.text ?: call.text.substringBefore('(').substringBefore('{').trim()
        }
        return stripTypeArguments(callee)
    }

    private fun stripTypeArguments(text: String): String {
        var result = text
        while (true) {
            val stripped = result.replace(TYPE_ARGUMENTS, "")
            if (stripped == result) return result
            result = stripped
        }
    }

    private fun calleeExpressionName(expression: KtExpression): String = when (expression) {
        is KtCallExpression -> {
            val receiver = (expression.parent as? KtQualifiedExpression)
                ?.takeIf { it.selectorExpression == expression }
                ?.receiverExpression
            val name = expression.calleeExpression?.text ?: expression.text.substringBefore('(').trim()
            if (receiver != null) "${calleeExpressionName(receiver)}.$name" else name
        }
        is KtQualifiedExpression -> calleeExpressionName(expression.selectorExpression ?: expression)
        else -> expression.text
    }

    private fun nameArgument(call: KtCallExpression): String? {
        val first = call.valueArguments.firstOrNull()?.getArgumentExpression() ?: return null
        return stringLiteralValue(first)
    }

    private fun stringLiteralValue(expression: KtExpression): String? {
        val template = expression as? KtStringTemplateExpression ?: return null
        if (template.hasInterpolation()) return null
        return template.entries.joinToString("") { it.text }
    }

    private fun isGradleKtsBuildScript(name: String): Boolean = name.lowercase().endsWith(".gradle.kts")

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

    private companion object {
        val TYPE_ARGUMENTS = Regex("<[^<>]*>")
    }
}
