package io.github.agadek.smartdiff.engine.declarations.psi.yaml

import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.FileRegion
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.LexedTokens
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationNode
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider
import io.github.agadek.smartdiff.engine.declarations.psi.PsiTreeParts
import org.jetbrains.yaml.YAMLLanguage
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem
import org.jetbrains.yaml.psi.YAMLValue

/**
 * YAML declarations (languages/10-yaml): mapping entries as keys, nested by their mapping values. A multi-document file
 * has each document as a section. A sequence is content, except a sequence of mappings that share one identifying key,
 * whose items become keys named by that key.
 */
class YamlDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.SIGNIFICANT) {

    override fun supports(languageId: String): Boolean = languageId == "yaml"

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile {
        val name = fileName ?: DEFAULT_NAME
        val language = Language.findLanguageByID("yaml") ?: YAMLLanguage.INSTANCE
        return PsiFileFactory.getInstance(project).createFileFromText(name, language, text, false, false)
    }

    override fun topLevel(file: PsiFile): List<PsiElement> {
        val yaml = file as? YAMLFile ?: return emptyList()
        val documents = yaml.documents
        if (documents.isEmpty()) return emptyList()
        return if (documents.size == 1) {
            documentMembers(documents.single())
        } else {
            documents
        }
    }

    override fun describe(element: PsiElement): DeclarationInfo? = when (element) {
        is YAMLDocument -> describeDocument(element)
        is YAMLKeyValue -> describeKeyValue(element)
        is YAMLSequenceItem -> describeIdentifiedItem(element)
        else -> null
    }

    override fun build(file: PsiFile, cancellation: Cancellation): DeclarationTree {
        val text = file.viewProvider.contents
        activeIdentifyingKeys.get().clear()
        try {
            fun node(element: PsiElement): Node? {
                cancellation.checkCanceled()
                val info = describe(element) ?: return null
                val range = info.range ?: element.textRange.let { TextRange(it.startOffset, it.endOffset) }
                if (range.length == 0) return null
                return Node(info, range, info.members.mapNotNull(::node))
            }
            val topLevel = topLevel(file).mapNotNull(::node)

            val fileRegionError = PsiTreeParts.markParseErrors(file, topLevel, cancellation, ::isParseError)

            val definition = LanguageParserDefinitions.INSTANCE.forLanguage(file.language)
                ?: error("no parser definition for ${file.language.id}")
            val tokens = LexedTokens.lex(definition.createLexer(file.project), definition, text, cancellation, ::tokenKind)
            assignLeading(topLevel, tokens, text, 0)
            assignLayout(topLevel, tokens, text.length)

            fun declaration(node: Node): Declaration {
                cancellation.checkCanceled()
                val leading = node.leading?.let { tokens.own(it, emptyList()) }.orEmpty()
                val leadingStarts = node.leading?.let { tokens.ownStarts(it, emptyList()) }.orEmpty()
                val excluded = node.children.map { it.extent }
                return Declaration(
                    kind = node.info.kind,
                    name = node.info.name,
                    signature = node.info.signature,
                    range = node.range,
                    children = node.children.map(::declaration),
                    ownTokens = leading + tokens.own(node.range, excluded),
                    hasParseError = node.parseError,
                    leadingWhitespace = node.leading,
                    displaySignature = node.info.displaySignature,
                    receiver = node.info.receiver,
                    ownTokenStarts = leadingStarts + tokens.ownStarts(node.range, excluded),
                    body = null,
                    nameRange = node.info.nameRange,
                    layout = node.layout,
                )
            }
            val whole = TextRange(0, text.length)
            val fileRegion = FileRegion(
                ranges = PsiTreeParts.gaps(whole, topLevel.map { it.extent }),
                tokens = tokens.own(whole, topLevel.map { it.extent }),
                hasParseError = fileRegionError,
            )
            return DeclarationTree(topLevel.map(::declaration), fileRegion, whitespacePolicy(file))
        } finally {
            activeIdentifyingKeys.get().clear()
        }
    }

    private fun describeDocument(document: YAMLDocument): DeclarationInfo {
        return DeclarationInfo(
            DeclarationKind.SECTION,
            documentName(document),
            signature = null,
            members = documentMembers(document),
        )
    }

    private fun describeKeyValue(keyValue: YAMLKeyValue): DeclarationInfo {
        val name = keyName(keyValue)
        val value = keyValue.value
        val members = when (value) {
            is YAMLMapping -> value.keyValues.toList()
            is YAMLSequence -> identifiedItems(value)
            else -> null
        }
        return DeclarationInfo(
            DeclarationKind.KEY,
            name,
            signature = null,
            members = members.orEmpty(),
            nameRange = keyValue.key?.textRange?.let { TextRange(it.startOffset, it.endOffset) },
        )
    }

    private fun describeIdentifiedItem(sequenceItem: YAMLSequenceItem): DeclarationInfo? {
        val item = sequenceItem.value as? YAMLMapping ?: return null
        val sequence = sequenceItem.parent as? YAMLSequence ?: return null
        val key = activeIdentifyingKeys.get()[sequence] ?: identifyingKey(sequence)?.first ?: return null
        val name = itemName(item, key) ?: return null
        val identifyingValue = item.getKeyValueByKey(key)?.value
        return DeclarationInfo(
            DeclarationKind.KEY,
            name,
            signature = null,
            members = item.keyValues.toList(),
            nameRange = identifyingValue?.textRange?.let { TextRange(it.startOffset, it.endOffset) },
        )
    }

    private fun documentMembers(document: YAMLDocument): List<PsiElement> = when (val root = document.topLevelValue) {
        is YAMLMapping -> root.keyValues.toList()
        is YAMLSequence -> identifiedItems(root) ?: emptyList()
        else -> emptyList()
    }

    /** The identified sequence items of [sequence], or null when the sequence is content. */
    private fun identifiedItems(sequence: YAMLSequence): List<PsiElement>? {
        val (key, _) = identifyingKey(sequence) ?: return null
        activeIdentifyingKeys.get()[sequence] = key
        return sequence.items.filter { it.value is YAMLMapping }
    }

    /**
     * The identifying key name and its values for [sequence], or null when the sequence is content. The first key in
     * [IDENTIFIERS] that every item mapping has, with a distinct scalar value on every item, is used.
     */
    private fun identifyingKey(sequence: YAMLSequence): Pair<String, List<String>>? {
        val items = sequence.items
        if (items.isEmpty()) return null
        val mappings = items.map { it.value as? YAMLMapping ?: return null }
        for (key in IDENTIFIERS) {
            val itemValues = mappings.map { mapping -> mapping.getKeyValueByKey(key)?.value?.let(::identifierValue) }
            if (itemValues.any { it == null }) continue
            val distinct = itemValues.requireNoNulls()
            if (distinct.toSet().size == distinct.size) return key to distinct
        }
        return null
    }

    /** The identifying value text for a scalar, or null for other values. */
    private fun identifierValue(value: YAMLValue): String? = when (value) {
        is YAMLScalar -> scalarText(value)
        else -> null
    }

    /** The name of an identified item, `[key=value]`, or null when the key or value is missing. */
    private fun itemName(item: YAMLMapping, key: String): String? {
        val value = item.getKeyValueByKey(key)?.value as? YAMLScalar ?: return null
        return "[$key=${scalarText(value)}]"
    }

    /** The key name without quotes. */
    private fun keyName(keyValue: YAMLKeyValue): String {
        val key = keyValue.key
        if (key is YAMLScalar) return key.textValue
        val text = keyValue.keyText
        return unquote(text)
    }

    private fun scalarText(scalar: YAMLScalar): String = scalar.textValue

    private fun unquote(text: String): String {
        if (text.length >= 2) {
            val first = text.first()
            val last = text.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) return text.substring(1, text.length - 1)
        }
        return text
    }

    private fun documentName(document: YAMLDocument): String {
        val root = document.topLevelValue as? YAMLMapping
        if (root != null) {
            val kind = root.getKeyValueByKey("kind")?.value?.let { it as? YAMLScalar }?.let(::scalarText)
            val metadataName = (root.getKeyValueByKey("metadata")?.value as? YAMLMapping)
                ?.getKeyValueByKey("name")?.value?.let { it as? YAMLScalar }?.let(::scalarText)
            if (!kind.isNullOrEmpty() && !metadataName.isNullOrEmpty()) return "$kind/$metadataName"
            val name = root.getKeyValueByKey("name")?.value?.let { it as? YAMLScalar }?.let(::scalarText)
            if (!name.isNullOrEmpty()) return name
            val id = root.getKeyValueByKey("id")?.value?.let { it as? YAMLScalar }?.let(::scalarText)
            if (!id.isNullOrEmpty()) return id
        }
        val index = (document.parent as? YAMLFile)?.documents?.indexOf(document)?.takeIf { it >= 0 } ?: 0
        return "document ${index + 1}"
    }

    private fun assignLeading(
        roots: List<Node>,
        tokens: LexedTokens,
        text: CharSequence,
        containerStart: Int,
    ) {
        var previousEnd = containerStart
        for (node in roots) {
            val start = tokens.layoutStart(node.range.start, notBefore = previousEnd, ::isLayout)
            if (start < node.range.start) node.leading = TextRange(start, node.range.start)
            val childContainerStart = if (node.info.kind == DeclarationKind.SECTION) {
                documentContentStart(text, node.range.start)
            } else {
                node.range.start
            }
            assignLeading(node.children, tokens, text, childContainerStart)
            previousEnd = node.range.end
        }
    }

    /** Start of a document's content, after a leading `---` marker line when present. */
    private fun documentContentStart(text: CharSequence, documentStart: Int): Int {
        if (!text.startsWith("---", documentStart)) return documentStart
        var i = documentStart + 3
        while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
        if (i < text.length && text[i] == '\r') i++
        if (i < text.length && text[i] == '\n') i++
        return i
    }

    private fun assignLayout(siblings: List<Node>, tokens: LexedTokens, containerEnd: Int) {
        for ((index, node) in siblings.withIndex()) {
            val limit = siblings.getOrNull(index + 1)?.range?.start ?: containerEnd
            val end = tokens.layoutEnd(node.range.end, limit, ::isLayout)
            if (end > node.range.end) node.layout = TextRange(node.range.end, end)
            assignLayout(node.children, tokens, node.range.end)
        }
    }

    private fun isLayout(token: Token): Boolean =
        token.kind == TokenKind.WHITESPACE || (token.text.isNotEmpty() && token.text.all(Char::isWhitespace))

    private class Node(
        val info: DeclarationInfo,
        override val range: TextRange,
        override val children: List<Node>,
    ) : PsiDeclarationNode {
        override var parseError = false
        override var leading: TextRange? = null
        var layout: TextRange? = null
    }

    private companion object {
        const val DEFAULT_NAME = "SmartDiff.yaml"
        val IDENTIFIERS = listOf("name", "id", "key")
        private val activeIdentifyingKeys = ThreadLocal.withInitial { mutableMapOf<YAMLSequence, String>() }
    }
}
