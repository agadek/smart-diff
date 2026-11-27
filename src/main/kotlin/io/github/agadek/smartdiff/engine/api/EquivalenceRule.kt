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

    /** declarations/11 R4: a JavaScript Number or BigInt literal in another base or format, same type and exact value. */
    JS_NUMERIC_LITERALS("JavaScript and TypeScript", "Numeric literals in another base or format"),

    /** declarations/11 R5: a JavaScript string literal with other quotes or escapes for the same characters. */
    JS_STRING_QUOTES("JavaScript and TypeScript", "Strings with other quotes or escapes"),

    /** declarations/11 R6: a JavaScript trailing comma before `)`, `]` or `}`. */
    JS_TRAILING_COMMAS("JavaScript and TypeScript", "Trailing commas"),

    /** declarations/11 R7, R8: JavaScript parentheses around a whole expression where they cannot change its grouping. */
    JS_REDUNDANT_PARENTHESES("JavaScript and TypeScript", "Redundant parentheses"),

    /** declarations/11 R9: a Python integer, float or imaginary literal in another base or format, same type and value. */
    PYTHON_NUMERIC_LITERALS("Python", "Numeric literals in another base or format"),

    /** declarations/11 R10: a Python string or bytes literal with other quotes or escapes for the same characters. */
    PYTHON_STRING_QUOTES("Python", "Strings with other quotes or escapes"),

    /** declarations/11 R11: a Python trailing comma that does not make a tuple. */
    PYTHON_TRAILING_COMMAS("Python", "Trailing commas"),

    /** declarations/11 R12, R13: Python parentheses around a whole expression where they cannot change its grouping. */
    PYTHON_REDUNDANT_PARENTHESES("Python", "Redundant parentheses"),

    /** declarations/11 R14: a Go integer, float or imaginary literal in another base or format, same kind and value. */
    GO_NUMERIC_LITERALS("Go", "Numeric literals in another base or format"),

    /** declarations/11 R15: a Go string literal for the same bytes, or a rune literal for the same rune. */
    GO_STRING_LITERALS("Go", "Strings and runes with other escapes or quotes"),

    /** declarations/11 R16: a Go trailing comma before `)` or `}`. */
    GO_TRAILING_COMMAS("Go", "Trailing commas"),

    /** declarations/11 R17, R18: Go parentheses around a whole expression where they cannot change its grouping. */
    GO_REDUNDANT_PARENTHESES("Go", "Redundant parentheses"),

    /** declarations/11 R19: the specs of one parenthesized import block in another order. */
    GO_IMPORT_ORDER("Go", "Import order in an import block"),
}
