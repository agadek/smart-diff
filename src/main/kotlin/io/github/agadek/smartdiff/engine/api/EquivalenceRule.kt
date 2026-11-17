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

    /** declarations/09 R4: a Kotlin integer or floating-point literal in another base or format, same suffix and value. */
    KOTLIN_NUMERIC_LITERALS("Kotlin", "Numeric literals in another base or format"),

    /** declarations/09 R5: a Kotlin string or character literal with other escapes for the same characters. */
    KOTLIN_STRING_ESCAPES("Kotlin", "Strings and characters with other escapes"),

    /** declarations/09 R6: `${name}` written as `$name` in a string template where the braces are not needed. */
    KOTLIN_TEMPLATE_BRACES("Kotlin", "Redundant braces in string templates"),

    /** declarations/09 R7, R8: Kotlin parentheses around a whole expression where they cannot change its grouping. */
    KOTLIN_REDUNDANT_PARENTHESES("Kotlin", "Redundant parentheses"),

    /** declarations/09 R9: a Scala integer or floating-point literal in another base or format, same type and value. */
    SCALA_NUMERIC_LITERALS("Scala", "Numeric literals in another base or format"),

    /** declarations/09 R10: a plain Scala string or character literal with other escapes for the same characters. */
    SCALA_STRING_ESCAPES("Scala", "Strings and characters with other escapes"),

    /** declarations/09 R11, R12: Scala parentheses around a whole expression where they cannot change its grouping. */
    SCALA_REDUNDANT_PARENTHESES("Scala", "Redundant parentheses"),
}
