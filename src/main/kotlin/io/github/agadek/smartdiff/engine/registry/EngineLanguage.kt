package io.github.agadek.smartdiff.engine.registry

import io.github.agadek.smartdiff.engine.api.LanguageIds

/**
 * The language id an engine is selected by (ADR 0015): the IntelliJ language id, or, for a file the IDE has no language
 * support for, the Tree-sitter language of its file name's extension (engines/05 R2, R3, R4).
 */
object EngineLanguage {
    /** The IntelliJ ids that mean "no language support": plain text and the TextMate language. */
    private val NO_SUPPORT = setOf("TEXT", "textmate")

    // engines/05 R3
    private val EXTENSIONS = mapOf(
        "swift" to LanguageIds.TREE_SITTER_SWIFT,
        "cs" to LanguageIds.TREE_SITTER_C_SHARP,
        "c" to LanguageIds.TREE_SITTER_C,
        "cpp" to LanguageIds.TREE_SITTER_CPP, "cc" to LanguageIds.TREE_SITTER_CPP, "cxx" to LanguageIds.TREE_SITTER_CPP,
        "c++" to LanguageIds.TREE_SITTER_CPP, "h" to LanguageIds.TREE_SITTER_CPP, "hpp" to LanguageIds.TREE_SITTER_CPP,
        "hh" to LanguageIds.TREE_SITTER_CPP, "hxx" to LanguageIds.TREE_SITTER_CPP,
    )

    private val DISPLAY_NAMES = mapOf(
        LanguageIds.TREE_SITTER_SWIFT to "Swift",
        LanguageIds.TREE_SITTER_C_SHARP to "C#",
        LanguageIds.TREE_SITTER_C to "C",
        LanguageIds.TREE_SITTER_CPP to "C++",
    )

    /**
     * [languageId] unchanged when it names a language the IDE supports; otherwise the Tree-sitter language of
     * [fileName]'s extension, compared ignoring case, or [languageId] itself (null when there is none).
     */
    fun of(languageId: String?, fileName: String?): String? {
        if (languageId != null && languageId !in NO_SUPPORT) return languageId
        val extension = fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }
        return EXTENSIONS[extension] ?: languageId
    }

    /** Whether [languageId] is a Tree-sitter language. */
    fun isTreeSitter(languageId: String?): Boolean = languageId?.startsWith(LanguageIds.TREE_SITTER_PREFIX) == true

    /** The name to show for a Tree-sitter language, such as `Swift`; null for any other id. */
    fun displayName(languageId: String): String? = DISPLAY_NAMES[languageId]
}
