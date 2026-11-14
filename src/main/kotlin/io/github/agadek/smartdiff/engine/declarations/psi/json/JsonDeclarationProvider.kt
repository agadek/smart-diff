package io.github.agadek.smartdiff.engine.declarations.psi.json

import com.intellij.json.JsonLanguage
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonNumberLiteral
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonValue
import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationInfo
import io.github.agadek.smartdiff.engine.declarations.psi.PsiDeclarationProvider

/**
 * JSON, JSON with comments and JSON5 declarations (languages/07-json): object members as keys, nested by their object
 * values. An array is content, except an array of objects that share one identifying key, whose items become keys named
 * by that key.
 */
class JsonDeclarationProvider : PsiDeclarationProvider(WhitespacePolicy.INSIGNIFICANT) {

    override fun supports(languageId: String): Boolean = languageId == "JSON" || languageId == "JSON5"

    /** A comma separates keys or items: it covers source for nothing-hidden without changing a key by itself. */
    override fun tokenKind(type: String, text: String, kind: TokenKind): TokenKind =
        if (kind == TokenKind.OTHER && text == ",") TokenKind.SEPARATOR else kind

    override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile {
        val name = fileName ?: DEFAULT_NAME
        // JSON with comments (for example .jsonc) is the platform's JSON language; JSON5 has its own language.
        val language = when {
            name.endsWith(".json5", ignoreCase = true) -> Language.findLanguageByID("JSON5") ?: JsonLanguage.INSTANCE
            else -> JsonLanguage.INSTANCE
        }
        return PsiFileFactory.getInstance(project).createFileFromText(name, language, text, false, false)
    }

    override fun topLevel(file: PsiFile): List<PsiElement> {
        val root = (file as? JsonFile)?.topLevelValue ?: return emptyList()
        return when (root) {
            is JsonObject -> root.propertyList
            is JsonArray -> identifiedItems(root) ?: emptyList()
            else -> emptyList()
        }
    }

    override fun describe(element: PsiElement): DeclarationInfo? = when (element) {
        is JsonProperty -> describeProperty(element)
        is JsonObject -> describeIdentifiedItem(element)
        else -> null
    }

    private fun describeProperty(property: JsonProperty): DeclarationInfo? {
        val name = keyName(property) ?: return null
        val value = property.value
        val members = when (value) {
            is JsonObject -> value.propertyList
            is JsonArray -> identifiedItems(value)
            else -> null
        }
        return DeclarationInfo(
            DeclarationKind.KEY, name, signature = null, members = members.orEmpty(),
            nameRange = property.nameElement?.textRange?.let { TextRange(it.startOffset, it.endOffset) },
        )
    }

    private fun describeIdentifiedItem(item: JsonObject): DeclarationInfo? {
        val array = item.parent as? JsonArray ?: return null
        val (key, _) = identifyingKey(array) ?: return null
        val name = itemName(item, key) ?: return null
        val identifyingValue = item.findProperty(key)?.value
        return DeclarationInfo(
            DeclarationKind.KEY, name, signature = null, members = item.propertyList,
            nameRange = identifyingValue?.textRange?.let { TextRange(it.startOffset, it.endOffset) },
        )
    }

    /** The identified item objects of [array], or null when the array is content. */
    private fun identifiedItems(array: JsonArray): List<PsiElement>? {
        identifyingKey(array) ?: return null
        return array.valueList.filterIsInstance<JsonObject>()
    }

    /**
     * The identifying key name and its values for [array], or null when the array is content. The first key in
     * [IDENTIFIERS] that every item object has, with a distinct string or number value on every item, is used.
     */
    private fun identifyingKey(array: JsonArray): Pair<String, List<String>>? {
        val values = array.valueList
        if (values.isEmpty() || values.any { it !is JsonObject }) return null
        val objects = values.filterIsInstance<JsonObject>()
        for (key in IDENTIFIERS) {
            val itemValues = objects.map { obj -> obj.findProperty(key)?.value?.let(::identifierValue) }
            if (itemValues.any { it == null }) continue
            val distinct = itemValues.requireNoNulls()
            if (distinct.toSet().size == distinct.size) return key to distinct
        }
        return null
    }

    /** The identifying value text: a string as its decoded value, a number as written; null for other values. */
    private fun identifierValue(value: JsonValue): String? = when (value) {
        is JsonStringLiteral -> value.value
        is JsonNumberLiteral -> value.text
        else -> null
    }

    /** The name of an identified item, `[key=value]`, or null when the key is missing. */
    private fun itemName(item: JsonObject, key: String): String? {
        val value = item.findProperty(key)?.value ?: return null
        val text = when (value) {
            is JsonStringLiteral -> value.value
            is JsonNumberLiteral -> value.text
            else -> return null
        }
        return "[$key=$text]"
    }

    /** The key name without quotes and with escapes decoded. */
    private fun keyName(property: JsonProperty): String? {
        val nameElement = property.nameElement
        return when (nameElement) {
            is JsonStringLiteral -> nameElement.value
            else -> {
                val text = nameElement.text ?: return null
                if (text.length >= 2 && ((text.first() == '"' && text.last() == '"') || (text.first() == '\'' && text.last() == '\''))) {
                    text.substring(1, text.length - 1)
                } else {
                    text
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_NAME = "SmartDiff.json"
        val IDENTIFIERS = listOf("name", "id", "key")
    }
}
