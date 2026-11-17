package io.github.agadek.smartdiff.engine.api

/**
 * An opt-in, language-specific rule that names one kind of rewrite that keeps a value (declarations/08, ADR 0012). It
 * claims only what it names (R19) and never changes classification: a rewritten declaration stays [Content.CHANGED],
 * and the outline alone can show it as `rewrite-only`.
 */
enum class EquivalenceRule(
    /** The language the rule belongs to, as the settings page groups it. */
    val language: String,
    /** The rule's checkbox text on the settings page. */
    val displayName: String,
) {
    /** R10: an integer or floating-point literal in another base or format, with the same type and exact value. */
    JAVA_NUMERIC_LITERALS("Java", "Numeric literals in another base or format"),

    /** R11: a string or character literal with other escapes that stands for the same characters. */
    JAVA_STRING_ESCAPES("Java", "Strings and characters with other escapes"),

    /** R12: parentheses around a whole expression where they cannot change how it is grouped. */
    JAVA_REDUNDANT_PARENTHESES("Java", "Redundant parentheses"),
}
