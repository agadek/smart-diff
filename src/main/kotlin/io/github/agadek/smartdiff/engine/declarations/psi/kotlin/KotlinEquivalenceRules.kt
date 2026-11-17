package io.github.agadek.smartdiff.engine.declarations.psi.kotlin

import io.github.agadek.smartdiff.engine.api.EquivalenceRule
import io.github.agadek.smartdiff.engine.declarations.model.EquivalenceRuleImpl
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.ParenthesesScan
import io.github.agadek.smartdiff.engine.declarations.psi.ParenthesesScan.Companion.STATEMENT_END
import io.github.agadek.smartdiff.engine.declarations.psi.isHexDigit
import io.github.agadek.smartdiff.engine.declarations.psi.printableInLiteral
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Kotlin's equivalence rules (declarations/09 R4 to R8), on the Kotlin lexer's token types as [Token.type] records them
 * (each `KtTokens` constant's `toString()`, which is a keyword's own text). They are pure token functions and need no
 * PSI, types or name resolution. [ALL] is in the fixed order the classifier applies them: parentheses, template braces,
 * numeric literals, string escapes.
 */
object KotlinEquivalenceRules {

    val ALL: List<EquivalenceRuleImpl> = listOf(RedundantParentheses, TemplateBraces, NumericLiterals, StringEscapes)

    private const val OPEN_QUOTE = "OPEN_QUOTE"
    private const val CLOSING_QUOTE = "CLOSING_QUOTE"
    private const val REGULAR_STRING_PART = "REGULAR_STRING_PART"
    private const val ESCAPE_SEQUENCE = "ESCAPE_SEQUENCE"
    private const val SHORT_TEMPLATE_ENTRY_START = "SHORT_TEMPLATE_ENTRY_START"
    private const val LONG_TEMPLATE_ENTRY_START = "LONG_TEMPLATE_ENTRY_START"
    private const val LONG_TEMPLATE_ENTRY_END = "LONG_TEMPLATE_ENTRY_END"
    private const val IDENTIFIER = "IDENTIFIER"
    private const val THIS = "this"

    /** R4: every numeric literal written as its exact value in decimal, with its suffix in upper case. */
    object NumericLiterals : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.KOTLIN_NUMERIC_LITERALS

        override fun rewrite(tokens: List<Token>): List<Token> = tokens.map { token ->
            val canonical = when (token.type) {
                "INTEGER_LITERAL" -> integer(token.text)
                "FLOAT_CONSTANT" -> floating(token.text)
                else -> null
            }
            if (canonical == null) token else token.copy(text = canonical)
        }

        /** The value of an integer literal in decimal, with its suffix; null when it does not parse. */
        internal fun integer(text: String): String? = runCatching {
            var digits = text.replace("_", "")
            val suffix = when {
                digits.endsWith("uL") || digits.endsWith("UL") -> "UL"
                digits.endsWith("L") -> "L"
                digits.endsWith("u") || digits.endsWith("U") -> "U"
                else -> ""
            }
            digits = digits.dropLast(suffix.length)
            val (radix, body) = when {
                digits.startsWith("0x") || digits.startsWith("0X") -> 16 to digits.substring(2)
                digits.startsWith("0b") || digits.startsWith("0B") -> 2 to digits.substring(2)
                digits.length > 1 && digits.startsWith("0") -> return@runCatching null // not a Kotlin literal
                else -> 10 to digits
            }
            if (body.isEmpty() || body.startsWith("+") || body.startsWith("-")) return@runCatching null
            // Kotlin has no two's-complement literals: 0xFFFF_FFFF is the Long 4294967295.
            BigInteger(body, radix).toString() + suffix
        }.getOrNull()

        /** The exact value of a floating-point literal, plain, with `F` or `D`; null when it does not parse. */
        internal fun floating(text: String): String? = runCatching {
            var digits = text.replace("_", "")
            val suffix = if (digits.endsWith("f") || digits.endsWith("F")) "F" else "D"
            if (suffix == "F") digits = digits.dropLast(1)
            if (digits.isEmpty() || !digits.all { it.isDigit() || it in ".eE+-" }) return@runCatching null
            BigDecimal(digits).stripTrailingZeros().toPlainString() + suffix
        }.getOrNull()
    }

    /** R5: every regular string and character literal written with one canonical escape for each character. */
    object StringEscapes : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.KOTLIN_STRING_ESCAPES

        override fun rewrite(tokens: List<Token>): List<Token> {
            val out = mutableListOf<Token>()
            val quotes = ArrayDeque<String>() // the open quotes of the strings the walk is in, innermost last
            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                when (token.type) {
                    OPEN_QUOTE -> quotes.addLast(token.text)
                    CLOSING_QUOTE -> quotes.removeLastOrNull()
                }
                if ((token.type == REGULAR_STRING_PART || token.type == ESCAPE_SEQUENCE) && quotes.lastOrNull() == "\"") {
                    var end = i
                    while (end < tokens.size && (tokens[end].type == REGULAR_STRING_PART || tokens[end].type == ESCAPE_SEQUENCE)) end++
                    val run = tokens.subList(i, end)
                    val canonical = canonicalRun(run)
                    if (canonical == null) out += run else out += Token(REGULAR_STRING_PART, canonical, token.kind)
                    i = end
                    continue
                }
                out += if (token.type == "CHARACTER_LITERAL") character(token.text)?.let { token.copy(text = it) } ?: token else token
                i++
            }
            return out
        }

        /** The canonical text of one run of string parts and escapes, or null when an escape is unknown. */
        private fun canonicalRun(run: List<Token>): String? {
            val decoded = StringBuilder()
            for (token in run) {
                if (token.type == ESCAPE_SEQUENCE) decoded.append(decode(token.text) ?: return null) else decoded.append(token.text)
            }
            return buildString {
                decoded.forEach { c ->
                    append(
                        when {
                            c == '"' -> "\\\""
                            c == '\\' -> "\\\\"
                            c == '$' -> "\\$"
                            !printableInLiteral(c) -> "\\u%04X".format(c.code)
                            else -> c.toString()
                        },
                    )
                }
            }
        }

        /** The canonical text of a character literal with its quotes, or null when it is not one this rule reads. */
        internal fun character(text: String): String? {
            if (text.length < 3 || text.first() != '\'' || text.last() != '\'') return null
            val decoded = decodeAll(text.substring(1, text.length - 1)) ?: return null
            return buildString {
                append('\'')
                decoded.forEach { c ->
                    append(
                        when {
                            c == '\'' -> "\\'"
                            c == '\\' -> "\\\\"
                            !printableInLiteral(c) -> "\\u%04X".format(c.code)
                            else -> c.toString()
                        },
                    )
                }
                append('\'')
            }
        }

        /** The characters [body] stands for, escapes decoded, or null for an escape this rule does not know. */
        private fun decodeAll(body: String): String? {
            val out = StringBuilder()
            var i = 0
            while (i < body.length) {
                if (body[i] != '\\') { out.append(body[i]); i++; continue }
                val length = if (body.startsWith("\\u", i)) 6 else 2
                if (i + length > body.length) return null
                out.append(decode(body.substring(i, i + length)) ?: return null)
                i += length
            }
            return out.toString()
        }

        /** The character one escape sequence stands for, or null when this rule does not know it. */
        private fun decode(escape: String): Char? = when {
            escape.length == 6 && escape.startsWith("\\u") && escape.substring(2).all(::isHexDigit) -> escape.substring(2).toInt(16).toChar()
            escape.length != 2 || escape[0] != '\\' -> null
            else -> when (escape[1]) {
                't' -> '\t'
                'b' -> '\b'
                'n' -> '\n'
                'r' -> '\r'
                '\'' -> '\''
                '"' -> '"'
                '\\' -> '\\'
                '$' -> '$'
                else -> null
            }
        }
    }

    /** R6: `${name}` and `${this}` written as `$name` and `$this` where the next character cannot continue a name. */
    object TemplateBraces : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.KOTLIN_TEMPLATE_BRACES

        override fun rewrite(tokens: List<Token>): List<Token> {
            val out = mutableListOf<Token>()
            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                if (token.type == LONG_TEMPLATE_ENTRY_START) {
                    val replaced = shortForm(tokens, i)
                    if (replaced != null) {
                        out += Token(SHORT_TEMPLATE_ENTRY_START, "$", token.kind)
                        out += tokens[replaced.first]
                        i = replaced.second + 1
                        continue
                    }
                }
                out += token
                i++
            }
            return out
        }

        /** The index of the single name inside the braces at [start] and of the closing `}`, or null to keep them. */
        private fun shortForm(tokens: List<Token>, start: Int): Pair<Int, Int>? {
            var name: Int? = null
            var i = start + 1
            while (i < tokens.size && tokens[i].type != LONG_TEMPLATE_ENTRY_END) {
                val token = tokens[i]
                if (token.kind != TokenKind.WHITESPACE) {
                    if (name != null) return null
                    name = i
                }
                i++
            }
            if (i >= tokens.size || name == null) return null
            val content = tokens[name]
            val simple = (content.type == IDENTIFIER && !content.text.startsWith("`")) || content.type == THIS
            if (!simple) return null
            val after = tokens.getOrNull(i + 1)
            if (after != null && after.type == REGULAR_STRING_PART) {
                val c = after.text.firstOrNull()
                if (c != null && Character.isJavaIdentifierPart(c) && c != '$') return null
            }
            return name to i
        }
    }

    /** R7, R8: parentheses around a whole expression removed where they cannot change how it is grouped. */
    object RedundantParentheses : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.KOTLIN_REDUNDANT_PARENTHESES

        private val ASSIGNMENTS = setOf("EQ", "PLUSEQ", "MINUSEQ", "MULTEQ", "DIVEQ", "PERCEQ")

        private val STARTS_STATEMENT = setOf(
            "val", "var", "fun", "class", "object", "interface", "return", "throw", "if", "when", "for", "while", "do",
            "try", "this", "super",
        )

        private val scan = ParenthesesScan(
            lpar = "LPAR",
            rpar = "RPAR",
            lbracket = "LBRACKET",
            opens = setOf("LPAR", "LBRACKET", "LBRACE", LONG_TEMPLATE_ENTRY_START),
            closes = setOf("RPAR", "RBRACKET", "RBRACE", LONG_TEMPLATE_ENTRY_END),
            excludedTopLevel = setOf("COMMA", "ARROW"),
            excludedAnywhere = emptySet(),
            statementEnds = setOf("SEMICOLON", "RBRACE", "else"),
            startsStatement = { (it.type == IDENTIFIER) || it.type in STARTS_STATEMENT },
            allowedAfter = { before ->
                when (before) {
                    "return", "throw" -> setOf(STATEMENT_END)
                    in ASSIGNMENTS -> setOf(STATEMENT_END, "COMMA", "RPAR")
                    "LPAR", "COMMA" -> setOf("COMMA", "RPAR")
                    else -> null
                }
            },
            // A comma directly inside braces separates lambda parameters or `when` conditions, never arguments.
            needsArgumentList = { before -> before == "COMMA" },
        )

        override fun rewrite(tokens: List<Token>): List<Token> = scan.rewrite(tokens)
    }
}
