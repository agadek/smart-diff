package io.github.agadek.smartdiff.engine.declarations.psi.markdown

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.MarkdownLanguage
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFrontMatterHeader
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader

/**
 * Markdown declarations (languages/14-markdown): headings are nested [DeclarationKind.SECTION]s. S10 (Markdown part)
 * records the PSI shape on the build target: on 2025.1.3 headers are direct children of the [MarkdownFile] and front
 * matter has its own element type; the walk below also handles a wrapper around a run of headings, without descending
 * into code blocks, block quotes, lists or HTML blocks (R9).
 */
class MarkdownDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.SIGNIFICANT) {

    override fun supports(languageId: String): Boolean = languageId == "Markdown"

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile {
        val name = fileName ?: DEFAULT_NAME
        val language = Language.findLanguageByID("Markdown") ?: MarkdownLanguage.INSTANCE
        return PsiFileFactory.getInstance(project).createFileFromText(name, language, text, false, false)
    }

    override fun build(file: PsiFile, cancellation: Cancellation): DeclarationTree {
        val previous = activeSections.get()
        activeSections.set(computeSections(file, cancellation))
        return try {
            super.build(file, cancellation)
        } finally {
            if (previous == null) activeSections.remove() else activeSections.set(previous)
        }
    }

    override fun topLevel(file: PsiFile): List<PsiElement> =
        activeSections.get()?.values?.filter { it.parent == null }?.map { it.element }.orEmpty()

    override fun describe(element: PsiElement): DeclarationInfo? = activeSections.get()?.get(element)?.let { section ->
        DeclarationInfo(
            kind = DeclarationKind.SECTION,
            name = section.name,
            signature = null,
            members = section.members.map { it.element },
            range = section.range,
            nameRange = section.nameRange,
        )
    }

    private fun computeSections(file: PsiFile, cancellation: Cancellation): Map<PsiElement, Section> {
        val textLength = file.viewProvider.contents.length
        val headings = sectionElements(file)
        val result = linkedMapOf<PsiElement, Section>()
        val open = ArrayDeque<Section>()
        for (element in headings) {
            cancellation.checkCanceled()
            val frontMatter = element is MarkdownFrontMatterHeader
            val header = element as? MarkdownHeader
            val start = element.textRange.startOffset
            val content = header?.contentElement
            val trimmed = content?.text?.trim().orEmpty()
            val name = when {
                frontMatter -> FRONT_MATTER
                trimmed.isEmpty() -> EMPTY_HEADING
                else -> trimmed
            }
            val nameRange = if (frontMatter || trimmed.isEmpty()) element.textRange else content!!.textRange
            // Front matter is a standalone top-level section: any heading closes it rather than nesting under it.
            val level = if (frontMatter) Int.MAX_VALUE else header!!.level
            // A heading ends every section still open at its level or deeper (R6). Closing sections here, in the same
            // pass that builds the stack, avoids re-scanning all later headings for each section (quadratic).
            while (open.isNotEmpty() && open.last().level >= level) {
                val closed = open.removeLast()
                if (closed.element !is MarkdownFrontMatterHeader) {
                    closed.range = TextRange(closed.range.start, start)
                }
            }
            // Front matter covers only its own text; a heading runs to the next one of its level or higher, or to the
            // end of the file when no such heading appears (sections still open after the pass keep textLength).
            val range = if (frontMatter) {
                element.textRange.let { TextRange(it.startOffset, it.endOffset) }
            } else {
                TextRange(start, textLength)
            }
            val section = Section(element, level, name, nameRange.let { TextRange(it.startOffset, it.endOffset) }, range)
            section.parent = open.lastOrNull()
            section.parent?.members?.add(section)
            open.addLast(section)
            result[element] = section
        }
        return result
    }

    /** Front matter and headings, in document order, skipping nested content that can hold heading-like text (R9). */
    private fun sectionElements(file: PsiFile): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        fun visit(parent: PsiElement) {
            for (child in parent.children) {
                when {
                    child is MarkdownFrontMatterHeader -> result += child
                    child is MarkdownHeader -> result += child
                    child.node.elementType in CONTAINERS -> Unit
                    else -> visit(child)
                }
            }
        }
        visit(file)
        return result.sortedBy { it.textRange.startOffset }
    }

    private class Section(
        val element: PsiElement,
        val level: Int,
        val name: String,
        val nameRange: TextRange,
        var range: TextRange,
    ) {
        var parent: Section? = null
        val members = mutableListOf<Section>()
    }

    private companion object {
        private val activeSections = ThreadLocal<Map<PsiElement, Section>>()
        const val DEFAULT_NAME = "SmartDiff.md"
        const val EMPTY_HEADING = "(empty heading)"
        const val FRONT_MATTER = "front matter"
        val CONTAINERS = setOf(
            MarkdownElementTypes.CODE_FENCE,
            MarkdownElementTypes.CODE_BLOCK,
            MarkdownElementTypes.BLOCK_QUOTE,
            MarkdownElementTypes.UNORDERED_LIST,
            MarkdownElementTypes.ORDERED_LIST,
            MarkdownElementTypes.LIST_ITEM,
            MarkdownElementTypes.HTML_BLOCK,
            MarkdownElementTypes.TABLE,
        )
    }
}
