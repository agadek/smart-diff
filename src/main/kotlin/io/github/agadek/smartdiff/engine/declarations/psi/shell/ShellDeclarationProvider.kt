package io.github.agadek.smartdiff.engine.declarations.psi.shell

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sh.ShLanguage
import com.intellij.sh.psi.ShFile
import com.intellij.sh.psi.ShFunctionDefinition
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider

/**
 * Shell function definitions (languages/09-shell). A shell function has no declared parameters, so its signature is empty;
 * functions nested inside another function are nested declarations, and functions inside compound commands at top level
 * are top-level declarations.
 */
class ShellDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.SEPARATION_SIGNIFICANT) {

    override fun supports(languageId: String): Boolean = languageId == ShLanguage.INSTANCE.id

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText(fileNameFor(fileName), ShLanguage.INSTANCE, text, false, false)

    // Top-level declarations are function definitions not nested inside another function, in source order (R4, R5).
    override fun topLevel(file: PsiFile): List<PsiElement> =
        if (file is ShFile) {
            PsiTreeUtil.findChildrenOfType(file, ShFunctionDefinition::class.java)
                .filter { PsiTreeUtil.getParentOfType(it, ShFunctionDefinition::class.java, true) == null }
                .sortedBy { it.textRange.startOffset }
        } else {
            emptyList()
        }

    override fun describe(element: PsiElement): DeclarationInfo? {
        if (element !is ShFunctionDefinition) return null
        val name = element.name?.takeIf { it.isNotEmpty() } ?: return null
        val body = element.block ?: element.parenthesesBlock
        val range = declarationRange(element)
        return DeclarationInfo(
            kind = DeclarationKind.FUNCTION,
            name = name,
            signature = "",
            members = members(element),
            body = body,
            range = range,
        )
    }

    /**
     * Includes a comment directly above the function (no blank line between) as documentation (R10). The comment is part
     * of the declaration range, as Javadoc/KDoc are part of Java/Kotlin declaration elements.
     */
    private fun declarationRange(function: ShFunctionDefinition): TextRange {
        val own = function.textRange
        val comment = precedingDocComment(function) ?: return TextRange(own.startOffset, own.endOffset)
        return TextRange(comment.textRange.startOffset, own.endOffset)
    }

    private fun precedingDocComment(function: ShFunctionDefinition): PsiComment? {
        val floor = containingFunctionBodyStart(function)
        var element: PsiElement? = elementBefore(function)
        var newlineCount = 0
        while (element != null && element.textRange.startOffset >= floor) {
            when {
                element is PsiComment -> return if (newlineCount <= 1) element else null
                element is PsiWhiteSpace || element.text.all { it.isWhitespace() } -> {
                    newlineCount += element.text.count { it == '\n' }
                    if (newlineCount > 1) return null
                }
                else -> return null
            }
            element = element.prevSibling
        }
        return null
    }

    /** The PSI element immediately before [function], walking up through wrapper elements that start at the function. */
    private fun elementBefore(function: ShFunctionDefinition): PsiElement? {
        var anchor: PsiElement = function
        while (true) {
            anchor.prevSibling?.let { return it }
            val parent = anchor.parent
            if (parent == null || parent is PsiFile || parent.textRange.startOffset != anchor.textRange.startOffset) return null
            anchor = parent
        }
    }

    /**
     * The earliest offset a nested function's doc comment may reach: the start of its containing function's body. A
     * top-level function has no floor.
     */
    private fun containingFunctionBodyStart(function: ShFunctionDefinition): Int {
        val parent = PsiTreeUtil.getParentOfType(function, ShFunctionDefinition::class.java, true) ?: return 0
        val body = parent.block ?: parent.parenthesesBlock ?: parent
        return body.textRange.startOffset
    }

    /**
     * Nested function definitions: every function definition inside this function whose nearest enclosing function is
     * this one, in source order.
     */
    private fun members(function: ShFunctionDefinition): List<PsiElement> =
        PsiTreeUtil.findChildrenOfType(function, ShFunctionDefinition::class.java).filter { definition ->
            definition !== function &&
                PsiTreeUtil.getParentOfType(definition, ShFunctionDefinition::class.java, true) === function
        }.sortedBy { it.textRange.startOffset }

    private fun fileNameFor(fileName: String?): String {
        val name = fileName?.substringAfterLast('/')?.substringAfterLast('\\')
        return if (name.isNullOrEmpty()) "SmartDiff.sh" else name
    }
}

