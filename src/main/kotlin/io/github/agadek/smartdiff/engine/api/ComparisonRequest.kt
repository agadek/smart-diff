package io.github.agadek.smartdiff.engine.api

/** The two sides of one file to compare. The texts are immutable snapshots, so an engine may read them on any thread. */
data class ComparisonRequest(
    val oldText: CharSequence,
    val newText: CharSequence,
    /** IntelliJ `Language.getID()`, such as `JAVA` or `kotlin`. */
    val languageId: String,
    /** A hint only; engines must not depend on it. */
    val fileName: String? = null,
    val limits: Limits = Limits.DEFAULT,
    /**
     * Other versions compared with the same old side, such as the other side of a merge (three-way merge plan §5.3). A
     * hint only: an engine with several parsers for the language may choose one from all the texts, so every comparison
     * reads the old side the same way.
     */
    val relatedTexts: List<CharSequence> = emptyList(),
)

/** Analysis limits (technical specification §9.3). */
data class Limits(
    /** Size of either side, checked by the host before an engine is called. */
    val maxChars: Int,
    /** Declarations per side; an engine with more reports [Status.TOO_LARGE]. */
    val maxDeclarations: Int,
    /** Analysis time; the host cancels a comparison that takes longer. */
    val timeoutMillis: Long,
) {
    init {
        require(maxChars >= 0 && maxDeclarations >= 0 && timeoutMillis > 0) { "invalid limits: $this" }
    }

    companion object {
        val DEFAULT = Limits(maxChars = 1024 * 1024, maxDeclarations = 5_000, timeoutMillis = 5_000)
    }
}
