package io.github.agadek.smartdiff.engine.declarations.psi.toml

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider
import org.toml.lang.TomlLanguage
import org.toml.lang.psi.TomlArray
import org.toml.lang.psi.TomlArrayTable
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlInlineTable
import org.toml.lang.psi.TomlKey
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlLiteral
import org.toml.lang.psi.TomlTable
import org.toml.lang.psi.TomlValue

/**
 * TOML declarations (languages/12-toml): key-values as [DeclarationKind.KEY]s and tables / array-of-tables entries as
 * flat [DeclarationKind.SECTION]s.
 */
class TomlDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.LINE_BREAKS_SIGNIFICANT) {

    private var arrayTableNames: Map<TomlArrayTable, String>? = null

    override fun supports(languageId: String): Boolean = languageId == "TOML"

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile {
        val name = fileName ?: DEFAULT_NAME
        val language = Language.findLanguageByID("TOML") ?: TomlLanguage
        return PsiFileFactory.getInstance(project).createFileFromText(name, language, text, false, false)
    }

    override fun build(file: PsiFile, cancellation: Cancellation): DeclarationTree {
        arrayTableNames = computeArrayTableNames(file)
        return try {
            super.build(file, cancellation)
        } finally {
            arrayTableNames = null
        }
    }

    override fun topLevel(file: PsiFile): List<PsiElement> {
        val toml = file as? TomlFile ?: return emptyList()
        val result = mutableListOf<PsiElement>()
        for (child in toml.children) {
            when (child) {
                is TomlKeyValue -> result += child
                is TomlTable -> result += child
                is TomlArrayTable -> result += child
            }
        }
        return result
    }

    override fun describe(element: PsiElement): DeclarationInfo? = when (element) {
        is TomlKeyValue -> describeKeyValue(element)
        is TomlTable -> describeTable(element)
        is TomlArrayTable -> describeArrayTable(element)
        is TomlInlineTable -> describeInlineTableItem(element)
        else -> null
    }

    private fun describeInlineTableItem(item: TomlInlineTable): DeclarationInfo? {
        val array = item.parent as? TomlArray ?: return null
        val elements = arrayElements(array).filterIsInstance<TomlInlineTable>()
        val key = identifyingKeyForInlineTables(elements) ?: return null
        val value = identifyingValue(inlineEntries(item), key) ?: return null
        val identifyingKv = inlineEntries(item).firstOrNull { it.key?.let(::keyText) == key }
        return DeclarationInfo(
            kind = DeclarationKind.KEY,
            name = "[$key=$value]",
            signature = null,
            members = inlineEntries(item),
            nameRange = identifyingKv?.value?.textRange?.let { TextRange(it.startOffset, it.endOffset) },
        )
    }

    private fun describeKeyValue(keyValue: TomlKeyValue): DeclarationInfo? {
        val key = keyValue.key ?: return null
        val name = keyText(key)
        val value = keyValue.value
        val members = when (value) {
            is TomlInlineTable -> inlineEntries(value)
            is TomlArray -> identifiedInlineItems(value)
            else -> null
        }
        return DeclarationInfo(
            kind = DeclarationKind.KEY,
            name = name,
            signature = null,
            members = members.orEmpty(),
            nameRange = TextRange(key.textRange.startOffset, key.textRange.endOffset),
        )
    }

    private fun describeTable(table: TomlTable): DeclarationInfo? {
        val header = table.header ?: return null
        val key = header.key ?: return null
        val name = keyText(key)
        return DeclarationInfo(
            kind = DeclarationKind.SECTION,
            name = name,
            signature = null,
            members = tableEntries(table),
            nameRange = TextRange(key.textRange.startOffset, key.textRange.endOffset),
        )
    }

    private fun describeArrayTable(entry: TomlArrayTable): DeclarationInfo? {
        val header = entry.header ?: return null
        val key = header.key ?: return null
        val headerName = keyText(key)
        val name = arrayTableName(entry, headerName)
        return DeclarationInfo(
            kind = DeclarationKind.SECTION,
            name = name,
            signature = null,
            members = arrayTableEntries(entry),
            nameRange = TextRange(key.textRange.startOffset, key.textRange.endOffset),
        )
    }

    private fun tableEntries(table: TomlTable): List<TomlKeyValue> = table.entries.toList()

    private fun arrayTableEntries(table: TomlArrayTable): List<TomlKeyValue> = table.entries.toList()

    /** Items of an identified-item array of inline tables, or null when the array is content. */
    private fun identifiedInlineItems(array: TomlArray): List<PsiElement>? {
        val elements = arrayElements(array)
        if (elements.isEmpty() || elements.any { it !is TomlInlineTable }) return null
        val tables = elements.filterIsInstance<TomlInlineTable>()
        identifyingKeyForInlineTables(tables) ?: return null
        return tables
    }

    private fun identifyingKeyForInlineTables(tables: List<TomlInlineTable>): String? {
        for (candidate in IDENTIFIERS) {
            val values = tables.map { table -> identifyingValue(inlineEntries(table), candidate) }
            if (values.any { it == null }) continue
            val distinct = values.requireNoNulls()
            if (distinct.toSet().size == distinct.size) return candidate
        }
        return null
    }

    private fun arrayElements(array: TomlArray): List<PsiElement> = array.elements.toList()

    private fun inlineEntries(table: TomlInlineTable): List<TomlKeyValue> = table.entries.toList()

    /** Name of an array-of-tables entry: `bin[name=web]` when identified, else the header name. */
    private fun arrayTableName(entry: TomlArrayTable, headerName: String): String =
        arrayTableNames?.get(entry) ?: headerName

    private fun computeArrayTableNames(file: PsiFile): Map<TomlArrayTable, String> {
        val toml = file as? TomlFile ?: return emptyMap()
        val entries = toml.children.filterIsInstance<TomlArrayTable>()
        val byHeader = entries.groupBy { it.header?.key?.let(::keyText) ?: return@groupBy "" }
        val names = mutableMapOf<TomlArrayTable, String>()
        for ((headerName, group) in byHeader) {
            if (headerName.isEmpty()) continue
            var identified = false
            for (candidate in IDENTIFIERS) {
                val values = group.map { e -> identifyingValue(arrayTableEntries(e), candidate) }
                if (values.any { it == null }) continue
                val distinct = values.requireNoNulls()
                if (distinct.toSet().size == distinct.size) {
                    for ((entry, value) in group.zip(distinct)) {
                        names[entry] = "$headerName[$candidate=$value]"
                    }
                    identified = true
                    break
                }
            }
            if (!identified) {
                for (entry in group) names[entry] = headerName
            }
        }
        return names
    }

    private fun identifyingValue(entries: Collection<TomlKeyValue>, key: String): String? {
        val kv = entries.firstOrNull { (it.key?.let(::keyText) ?: return@firstOrNull false) == key } ?: return null
        val value = kv.value ?: return null
        return scalarValueText(value)
    }

    private fun scalarValueText(value: TomlValue): String? = when (value) {
        is TomlLiteral -> literalIdentifierText(value.text)
        else -> null
    }

    private fun literalIdentifierText(text: String): String? {
        if (text.startsWith("\"\"\"") || text.startsWith("'''")) return null
        if (text.length >= 2 && ((text.first() == '"' && text.last() == '"') || (text.first() == '\'' && text.last() == '\''))) {
            return text.substring(1, text.length - 1)
        }
        if (text == "true" || text == "false") return null
        return if (NUMBER_LITERAL.matches(text)) text else null
    }

    private fun keyText(key: TomlKey): String {
        val segments = PsiTreeUtil.getChildrenOfTypeAsList(key, TomlKeySegment::class.java)
        return if (segments.isNotEmpty()) {
            segments.joinToString(".") { segment -> unquoteKeySegment(segment.text) }
        } else {
            key.text.split('.').joinToString(".") { part -> unquoteKeySegment(part.trim()) }
        }
    }

    private fun unquoteKeySegment(text: String): String {
        if (text.length >= 6 && ((text.startsWith("\"\"\"") && text.endsWith("\"\"\"")) || (text.startsWith("'''") && text.endsWith("'''")))) {
            return text.substring(3, text.length - 3)
        }
        if (text.length >= 2 && ((text.first() == '"' && text.last() == '"') || (text.first() == '\'' && text.last() == '\''))) {
            return text.substring(1, text.length - 1)
        }
        return text
    }

    override fun tokenKind(type: String, text: String, kind: TokenKind): TokenKind {
        // Commas separate array/inline-table items: treat as separators like JSON to avoid noise.
        return if (kind == TokenKind.OTHER && text == ",") TokenKind.SEPARATOR else kind
    }

    private companion object {
        const val DEFAULT_NAME = "SmartDiff.toml"
        val IDENTIFIERS = listOf("name", "id", "key")
        val NUMBER_LITERAL = Regex("""[+-]?(?:0x[0-9A-Fa-f_]+|0o[0-7_]+|0b[01_]+|(?:\d[\d_]*(?:\.\d[\d_]*)?|\.\d[\d_]*)(?:[eE][+-]?\d[\d_]*)?|(?:inf|nan))""")
    }
}
