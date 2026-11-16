package io.github.agadek.smartdiff.engine.api

/**
 * The open brackets seen while walking a declaration's own tokens (declarations/07 R1, R4, R6). A line break is
 * "inside brackets" only when the innermost open bracket is a parenthesis or a square bracket: an opening brace
 * pushes a block, so a break in a lambda within parentheses is compared as in a block, not as in the parentheses.
 */
class BracketDepth {
    private val open = ArrayDeque<String>()

    /** The text of the innermost open bracket, or null when none is open. */
    val top: String? get() = open.lastOrNull()

    /** Whether the innermost open bracket is a parenthesis or a square bracket. */
    val insideParensOrBrackets: Boolean get() = top == "(" || top == "["

    /** Records an opening bracket token; `${` of a Kotlin string template counts as `{`. */
    fun open(token: String) {
        open.addLast(if (token == "\${") "{" else token)
    }

    /**
     * Records a closing bracket token. An unmatched close empties the stack instead of failing: own tokens skip their
     * children's brackets, so the conservative direction is to stop treating later runs as inside brackets.
     */
    fun close(token: String) {
        val expected = when (token) {
            ")" -> "("
            "]" -> "["
            "}" -> "{"
            else -> null
        }
        if (expected != null && top == expected) open.removeLast() else open.clear()
    }
}

/**
 * The pure token comparisons that decide whether a line-break run counts, shared by the PSI path
 * (`ContentClassifier`) and the text path (`RenameNameMapping`) so they cannot disagree (declarations/07 R10).
 */
object LineBreakRules {

    // Kotlin: tokens after which a line break cannot end a statement (R2), and tokens before which it cannot start one (R3).
    private val KOTLIN_CONTINUES = setOf(
        ",", "(", "[", "{", "=", "->", ".", "?.", "+", "-", "/", "%", "&&", "||", "?:",
        "==", "!=", "===", "!==", "<=", ">=", "..", "..<", "+=", "-=", "*=", "/=", "%=",
    )
    private val KOTLIN_CLOSES = setOf(")", "]", "}", ",", ".", "?.", "?:", "&&", "||")

    // Scala 2 infix operators are identifiers and get no entry; a break next to one stays changed, conservatively.
    private val SCALA2_CONTINUES = setOf(",", "(", "[", "{", "=", "=>", "<-", ".")
    private val SCALA2_CLOSES = setOf(")", "]", "}", ",", ".", "=>")

    // Scala 3: only the bracket positions of R6; after `=>` or `:` inside brackets can open an indented region (R7).
    private val SCALA3_AFTER = setOf(",", "(", "[")
    private val SCALA3_BEFORE = setOf(")", "]")

    /** Whether a line-break run between [previous] and [next] at [depth] is ignored under [policy]. */
    fun ignorable(policy: WhitespacePolicy, previous: String?, next: String?, depth: BracketDepth): Boolean =
        when (policy) {
            WhitespacePolicy.KOTLIN_LINE_BREAKS ->
                depth.insideParensOrBrackets || previous in KOTLIN_CONTINUES || next in KOTLIN_CLOSES
            WhitespacePolicy.SCALA2_LINE_BREAKS ->
                depth.insideParensOrBrackets || previous in SCALA2_CONTINUES || next in SCALA2_CLOSES
            WhitespacePolicy.SCALA3_INDENTATION ->
                depth.insideParensOrBrackets && (previous in SCALA3_AFTER || next in SCALA3_BEFORE)
            else -> false
        }

    /** Updates [depth] for one non-whitespace [token]. */
    fun track(depth: BracketDepth, token: String) {
        when (token) {
            "(", "[", "{", "\${" -> depth.open(token)
            ")", "]", "}" -> depth.close(token)
        }
    }

    /**
     * The common leading whitespace of [indents], or null when the prefixes are not uniform (R8). An empty list has
     * an empty shift. Tabs and spaces compared together are not uniform, so the declaration is compared as written.
     */
    fun commonIndent(indents: List<String>): String? {
        if (indents.isEmpty()) return ""
        val shortest = indents.minByOrNull { it.length } ?: return ""
        return shortest.takeIf { prefix -> indents.all { it.startsWith(prefix) } }
    }
}
