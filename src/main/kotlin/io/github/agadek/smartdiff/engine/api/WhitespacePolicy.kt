package io.github.agadek.smartdiff.engine.api

/**
 * Which whitespace a language treats as insignificant (technical specification §8 step 4, relationships/04).
 *
 * The declaration engine classifies content with it, and the reference pass and the outline compare a renamed
 * declaration's text with it, so both agree on what is only a rename.
 */
enum class WhitespacePolicy {
    /** Java: whitespace never matters. */
    INSIGNIFICANT,

    /** Kotlin: only whether there is a line break between two tokens matters. */
    LINE_BREAKS_SIGNIFICANT,

    /**
     * Scala 3: whether there is a line break matters, and so does the indentation after the last one, compared as
     * written; spaces within a line do not.
     */
    INDENTATION_SIGNIFICANT,

    /**
     * Shell: whether there is a line break between two tokens matters, and so does whether there is any whitespace
     * between them at all; the amount of space and the indentation do not.
     */
    SEPARATION_SIGNIFICANT,

    /** Python, YAML: whitespace always matters, so nothing is formatting-only. */
    SIGNIFICANT,
}
