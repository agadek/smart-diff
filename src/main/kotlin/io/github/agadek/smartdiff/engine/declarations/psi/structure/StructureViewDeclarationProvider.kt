package io.github.agadek.smartdiff.engine.declarations.psi.structure

import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import io.github.agadek.smartdiff.engine.api.AnalysisNote
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.FileRegion
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationProvider
import io.github.agadek.smartdiff.engine.declarations.psi.LexedTokens
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationNode
import io.github.agadek.smartdiff.engine.declarations.psi.PsiTreeParts
import io.github.agadek.smartdiff.engine.declarations.psi.StructureViewFallback

/**
 * Builds a declaration tree from a language's structure view for files no dedicated provider handles
 * (languages/06-structure-view-fallback). Registered last, so a dedicated provider always wins (R2).
 */
class StructureViewDeclarationProvider : DeclarationProvider {

    override val analysisNote: String = AnalysisNote.STRUCTURE_VIEW

    override fun supports(languageId: String): Boolean {
        if (!StructureViewFallback.isEnabled()) return false
        val language = Language.findLanguageByID(languageId) ?: return false
        if (hasDedicatedProvider(languageId)) return false
        return hasStructureViewBuilder(language)
    }

    /** True when a dedicated provider supports [languageId]; the fallback never claims a language that has one (R2). */
    private fun hasDedicatedProvider(languageId: String): Boolean = try {
        DeclarationProvider.EP_NAME.extensionList.any {
            it !== this && it.analysisNote == null && it.supports(languageId)
        }
    } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
        throw e
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    override fun dialect(project: Project, texts: List<CharSequence>, fileName: String?, languageId: String): Language? {
        if (!fileName.isNullOrBlank()) {
            val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByFileName(fileName)
            (fileType as? com.intellij.openapi.fileTypes.LanguageFileType)?.language?.let { return it }
        }
        return Language.findLanguageByID(languageId)
    }

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile {
        val language = dialect ?: fileName?.let(::languageForFileName)
            ?: error("structure-view fallback needs a language")
        return createFile(project, text, fileName, language)
    }

    override fun parse(
        project: Project,
        text: CharSequence,
        fileName: String?,
        dialect: Language?,
        languageId: String,
    ): PsiFile {
        val language = dialect ?: Language.findLanguageByID(languageId)
            ?: error("structure-view fallback needs a language")
        return createFile(project, text, fileName, language)
    }

    private fun createFile(project: Project, text: CharSequence, fileName: String?, language: Language): PsiFile {
        val name = fileName?.takeIf { it.isNotBlank() } ?: defaultFileName(language)
        return PsiFileFactory.getInstance(project).createFileFromText(name, language, text, false, false)
    }

    private fun languageForFileName(fileName: String): Language? {
        val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        return (fileType as? com.intellij.openapi.fileTypes.LanguageFileType)?.language
    }

    override fun build(file: PsiFile, cancellation: Cancellation): DeclarationTree {
        val text = file.viewProvider.contents
        val builder = structureViewBuilder(file) as? TreeBasedStructureViewBuilder
            ?: error("no tree-based structure view for ${file.language.id}")
        val model = builder.createStructureViewModel(null)
        try {
            cancellation.checkCanceled()
            val roots = childrenOf(model.root, file, cancellation)
            val parserDefinition = LanguageParserDefinitions.INSTANCE.forLanguage(file.language)
                ?: error("no parser definition for ${file.language.id}")
            val tokens = LexedTokens.lex(parserDefinition.createLexer(file.project), parserDefinition, text, cancellation)
            val holders = roots.map(::buildHolder)
            // YAML emits its line breaks as an `Eol` token rather than whitespace; they are layout too.
            PsiTreeParts.assignLeading(holders, tokens, isLayout = ::isLayout)
            assignLayout(holders, tokens, text.length)
            val fileRegionError = PsiTreeParts.markParseErrors(file, holders, cancellation)
            val declarations = holders.map { toDeclaration(it, tokens, cancellation) }
            val whole = TextRange(0, text.length)
            val fileRegion = FileRegion(
                ranges = PsiTreeParts.gaps(whole, declarations.map { it.extent }),
                tokens = tokens.own(whole, declarations.map { it.extent }),
                hasParseError = fileRegionError,
            )
            return DeclarationTree(declarations.map { it.declaration }, fileRegion, WhitespacePolicy.SIGNIFICANT)
        } finally {
            model.dispose()
        }
    }

    private fun hasStructureViewBuilder(language: Language): Boolean {
        var current: Language? = language
        while (current != null) {
            if (LanguageStructureViewBuilder.INSTANCE.forLanguage(current) != null) return true
            current = current.baseLanguage
        }
        return false
    }

    private fun structureViewBuilder(file: PsiFile): com.intellij.ide.structureView.StructureViewBuilder? {
        var current: Language? = file.language
        while (current != null) {
            val factory = LanguageStructureViewBuilder.INSTANCE.forLanguage(current)
            val builder = factory?.getStructureViewBuilder(file)
            if (builder != null) return builder
            current = current.baseLanguage
        }
        return null
    }

    /**
     * The structure-view children that are declarations (R3): same-file PSI-backed items with a non-empty range. A
     * structure view that throws propagates, so the engine reports `FAILED` (R9) rather than a shorter outline.
     */
    private fun childrenOf(element: StructureViewTreeElement, file: PsiFile, cancellation: Cancellation): List<Node> {
        cancellation.checkCanceled()
        val result = mutableListOf<Node>()
        val seenRanges = HashSet<Pair<Int, Int>>()
        for (child in element.children) {
            cancellation.checkCanceled()
            val childElement = child as? StructureViewTreeElement ?: continue
            val psi = childElement.value as? PsiElement ?: continue
            val containing = psi.containingFile ?: continue
            if (!sameFile(containing, file)) continue
            val tr = psi.textRange ?: continue
            val range = TextRange(tr.startOffset, tr.endOffset)
            if (range.length == 0 || range.start < 0 || range.end > file.textLength) continue
            if (!seenRanges.add(range.start to range.end)) continue
            val name = childElement.presentation.presentableText?.trim().orEmpty().ifBlank { elementName(psi) }
            if (name.isBlank()) continue
            val kind = kindOf(psi)
            result += Node(name.trim(), kind, range, childrenOf(childElement, file, cancellation))
        }
        return result
    }

    /** Same file by identity, as text-built files have no virtual file to compare (R3). */
    private fun sameFile(containing: PsiFile, file: PsiFile): Boolean =
        containing === file ||
            containing.originalFile === file.originalFile ||
            containing.viewProvider === file.viewProvider

    private fun elementName(psi: PsiElement): String =
        (psi as? PsiNameIdentifierOwner)?.name?.trim()
            ?: psi.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                ?.invoke(psi)?.toString()?.trim().orEmpty()

    private fun kindOf(psi: PsiElement): DeclarationKind {
        val name = psi.javaClass.simpleName
        return when {
            name.contains("Interface") -> DeclarationKind.INTERFACE
            name.contains("Class") -> DeclarationKind.CLASS
            name.contains("Function") -> DeclarationKind.FUNCTION
            name.contains("Method") -> DeclarationKind.METHOD
            name.contains("Field") -> DeclarationKind.FIELD
            name.contains("Property") -> DeclarationKind.PROPERTY
            else -> DeclarationKind.ITEM
        }
    }

    private fun buildHolder(node: Node): NodeHolder = NodeHolder(node, node.children.map(::buildHolder))

    /**
     * Attaches the separator and line break after an item to it as [Declaration.layout] (coverage only, not compared).
     * In a JSON object the comma after a key would otherwise sit uncovered between swapped keys, so a pure reorder
     * could not be aligned even though both keys are unchanged (R7).
     */
    private fun assignLayout(siblings: List<NodeHolder>, tokens: LexedTokens, containerEnd: Int) {
        for ((index, node) in siblings.withIndex()) {
            val limit = siblings.getOrNull(index + 1)?.range?.start ?: containerEnd
            val end = tokens.layoutEnd(node.range.end, limit, ::isLayout)
            if (end > node.range.end) node.layout = TextRange(node.range.end, end)
            assignLayout(node.children, tokens, node.range.end)
        }
    }

    private fun isLayout(token: Token): Boolean =
        token.kind == TokenKind.WHITESPACE ||
            (token.text.isNotEmpty() && token.text.all(Char::isWhitespace)) ||
            token.text == "," || token.text == ";"

    /**
     * Own tokens leave out the leading whitespace: the structure view does not say whether the line break before an
     * item belongs to it, and under `SIGNIFICANT` counting it made a pure reorder look changed. The run is still
     * recorded in `leadingWhitespace` and shown in `details` when the declaration changes (R7).
     */
    private fun toDeclaration(holder: NodeHolder, tokens: LexedTokens, cancellation: Cancellation): DeclNode {
        cancellation.checkCanceled()
        val excluded = holder.children.map { it.extent }
        val (indentation, indentationStarts) = leadingIndentation(holder, tokens)
        val decl = Declaration(
            kind = holder.node.kind,
            name = holder.node.name,
            signature = null,
            range = holder.range,
            children = holder.children.map { toDeclaration(it, tokens, cancellation).declaration },
            ownTokens = indentation + tokens.own(holder.range, excluded),
            hasParseError = holder.parseError,
            leadingWhitespace = holder.leading,
            displaySignature = null,
            receiver = null,
            ownTokenStarts = indentationStarts + tokens.ownStarts(holder.range, excluded),
            body = null,
            layout = holder.layout,
        )
        return DeclNode(decl, holder.extent)
    }

    /**
     * The indentation after the last line break of the leading whitespace, as one token. The line break itself stays
     * layout, so a pure reorder of equally indented items is unchanged, while re-indenting one is `CHANGED` (R7).
     */
    private fun leadingIndentation(holder: NodeHolder, tokens: LexedTokens): Pair<List<Token>, List<Int>> {
        val leading = holder.leading ?: return emptyList<Token>() to emptyList()
        val all = tokens.own(leading, emptyList())
        if (all.isEmpty()) return emptyList<Token>() to emptyList()
        val text = all.joinToString("") { it.text }
        val indentation = text.substringAfterLast('\n')
        if (indentation.isEmpty()) return emptyList<Token>() to emptyList()
        val start = leading.end - indentation.length
        return listOf(Token(all.last().type, indentation, TokenKind.WHITESPACE)) to listOf(start)
    }

    private fun defaultFileName(language: Language): String {
        val ext = language.associatedFileType?.defaultExtension?.ifBlank { null }
            ?: language.id.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "txt" }
        return "SmartDiffFallback.$ext"
    }

    private data class Node(val name: String, val kind: DeclarationKind, val range: TextRange, val children: List<Node>)

    private class NodeHolder(val node: Node, override val children: List<NodeHolder>) : PsiDeclarationNode {
        override val range: TextRange get() = node.range
        override var leading: TextRange? = null
        override var parseError = false
        var layout: TextRange? = null
    }

    private class DeclNode(val declaration: Declaration, val extent: TextRange)
}
