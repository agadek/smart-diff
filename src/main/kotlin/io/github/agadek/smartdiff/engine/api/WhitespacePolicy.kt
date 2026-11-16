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

    /** Kotlin, with the ignorable line breaks of declarations/07 R1–R5. */
    KOTLIN_LINE_BREAKS,

    /** Scala 2, with the ignorable line breaks of declarations/07 R1–R5. */
    SCALA2_LINE_BREAKS,

    /** Scala 3, with the bracket line breaks of R6 and the whole-declaration shift of R8. */
    SCALA3_INDENTATION;

    /** The policies that extend [LINE_BREAKS_SIGNIFICANT] and count a line break but not its indentation. */
    val countsLineBreaks: Boolean
        get() = this == LINE_BREAKS_SIGNIFICANT || this == KOTLIN_LINE_BREAKS || this == SCALA2_LINE_BREAKS

    /** The policies that extend [INDENTATION_SIGNIFICANT] and count the indentation after a line break. */
    val countsIndentation: Boolean
        get() = this == INDENTATION_SIGNIFICANT || this == SCALA3_INDENTATION
}
