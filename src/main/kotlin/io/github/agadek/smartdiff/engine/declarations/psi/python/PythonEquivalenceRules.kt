package io.github.agadek.smartdiff.engine.declarations.psi.python

import io.github.agadek.smartdiff.engine.api.EquivalenceRule
import io.github.agadek.smartdiff.engine.declarations.model.EquivalenceRuleImpl
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.psi.ParenthesesScan
import io.github.agadek.smartdiff.engine.declarations.psi.ParenthesesScan.Companion.STATEMENT_END
import io.github.agadek.smartdiff.engine.declarations.psi.canonicalDecimal
import io.github.agadek.smartdiff.engine.declarations.psi.isHexDigit
import io.github.agadek.smartdiff.engine.declarations.psi.printableInLiteral
import io.github.agadek.smartdiff.engine.declarations.psi.removeTrailingCommas
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Python's equivalence rules (declarations/11 R9 to R13), on the Python lexer's token types as [Token.type] records
 * them (`Py:INTEGER_LITERAL` and the like). They are pure token functions and match token types by name only. [ALL] is
 * in the fixed order the classifier applies them: parentheses, trailing commas, numeric literals, strings.
 */
object PythonEquivalenceRules {

    val ALL: List<EquivalenceRuleImpl> = listOf(RedundantParentheses, TrailingCommas, NumericLiterals, StringQuotes)

    /** R9: every integer written in decimal, every float and imaginary literal as its exact decimal value. */
    object NumericLiterals : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.PYTHON_NUMERIC_LITERALS

        override fun rewrite(tokens: List<Token>): List<Token> = tokens.map { token ->
            val canonical = when (token.type) {
                "Py:INTEGER_LITERAL" -> integer(token.text)
                "Py:FLOAT_LITERAL" -> floating(token.text)
                "Py:IMAGINARY_LITERAL" -> imaginary(token.text)
                else -> null
            }
            if (canonical == null) token else token.copy(text = canonical)
        }

        /** The value of an integer literal in decimal, or null when it keeps its text. */
        internal fun integer(text: String): String? = runCatching {
            val digits = text.replace("_", "")
            val radix = when (digits.take(2).lowercase()) {
                "0x" -> 16
                "0o" -> 8
                "0b" -> 2
                else -> 10
            }
            val body = if (radix == 10) digits else digits.substring(2)
            if (body.isEmpty()) return@runCatching null
            // A Python 2 octal such as 017 is an error in Python 3; 000 is zero.
            if (radix == 10 && body.length > 1 && body[0] == '0' && body.any { it != '0' }) return@runCatching null
            if (!body.all { if (radix == 16) isHexDigit(it) else it.digitToIntOrNull(radix) != null }) return@runCatching null
            BigInteger(body, radix).toString()
        }.getOrNull()

        /** The exact value of a float literal, or null when it does not parse. */
        internal fun floating(text: String): String? = runCatching {
            val digits = text.replace("_", "")
            if (digits.isEmpty() || !digits.all { it.isDigit() || it in ".eE+-" }) return@runCatching null
            canonicalDecimal(BigDecimal(digits))
        }.getOrNull()

        /** The exact value of an imaginary literal followed by `j`, or null when it does not parse. */
        internal fun imaginary(text: String): String? {
            if (text.isEmpty() || text.last() !in "jJ") return null
            return floating(text.dropLast(1))?.let { "${it}j" }
        }
    }

    /** R10: every plain, `u` or `b` single-line literal written in double quotes with one canonical escape each. */
    object StringQuotes : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.PYTHON_STRING_QUOTES

        override fun rewrite(tokens: List<Token>): List<Token> = tokens.map { token ->
            val canonical = if (token.type == "Py:SINGLE_QUOTED_STRING") canonical(token.text) else null
            if (canonical == null) token else token.copy(text = canonical)
        }

        /** The literal [text], prefix included, in canonical form, or null when it keeps its text. */
        internal fun canonical(text: String): String? {
            val quoteAt = text.indexOfFirst { it == '\'' || it == '"' }
            if (quoteAt < 0) return null
            val bytes = when (text.substring(0, quoteAt).lowercase()) {
                "", "u" -> false
                "b" -> true
                else -> return null // raw strings and other prefixes keep their text
            }
            val literal = text.substring(quoteAt)
            val quote = literal.first()
            if (literal.length < 2 || literal.last() != quote) return null
            if (literal.startsWith("$quote$quote$quote") && literal.length >= 6) return null // triple-quoted
            val decoded = decode(literal.substring(1, literal.length - 1), bytes) ?: return null
            return buildString {
                if (bytes) append('b')
                append('"')
                decoded.forEach { append(if (bytes) encodeByte(it) else encode(it)) }
                append('"')
            }
        }

        /** The characters (or, for [bytes], byte values as chars) [body] stands for, or null to keep the text. */
        private fun decode(body: String, bytes: Boolean): String? {
            val out = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c != '\\') {
                    if (bytes && c.code > 0x7F) return null
                    out.append(c)
                    i++
                    continue
                }
                if (i + 1 >= body.length) return null
                val next = body[i + 1]
                when (next) {
                    '\\', '\'', '"' -> out.append(next)
                    'a' -> out.append('\u0007')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'v' -> out.append('\u000B')
                    in '0'..'7' -> {
                        var j = i + 1
                        while (j < body.length && j - (i + 1) < 3 && body[j] in '0'..'7') j++
                        val value = body.substring(i + 1, j).toInt(8)
                        if (value > 0xFF) return null
                        out.append(value.toChar())
                        i = j
                        continue
                    }
                    'x' -> {
                        if (i + 4 > body.length) return null
                        val hex = body.substring(i + 2, i + 4)
                        if (!hex.all(::isHexDigit)) return null
                        out.append(hex.toInt(16).toChar())
                        i += 4
                        continue
                    }
                    'u', 'U' -> {
                        if (bytes) return null // an unknown escape in bytes
                        val length = if (next == 'u') 4 else 8
                        if (i + 2 + length > body.length) return null
                        val hex = body.substring(i + 2, i + 2 + length)
                        if (!hex.all(::isHexDigit)) return null
                        val code = hex.toLong(16)
                        if (code > 0x10FFFF) return null
                        out.appendCodePoint(code.toInt())
                        i += 2 + length
                        continue
                    }
                    else -> return null // \N{…}, a line continuation or an unknown escape keeps the text
                }
                i += 2
            }
            return out.toString()
        }

        /** One character of a string as the canonical text writes it; only compared, never compiled. */
        private fun encode(c: Char): String = when {
            c == '"' -> "\\\""
            c == '\\' -> "\\\\"
            c == '\n' -> "\\n"
            c == '\r' -> "\\r"
            !printableInLiteral(c) -> "\\u%04X".format(c.code)
            else -> c.toString()
        }

        /** One byte of a bytes literal as the canonical text writes it. */
        private fun encodeByte(c: Char): String = when {
            c == '"' -> "\\\""
            c == '\\' -> "\\\\"
            c.code in 0x20..0x7E -> c.toString()
            else -> "\\x%02X".format(c.code)
        }
    }

    /** R11: a trailing comma removed where it cannot make a tuple. */
    object TrailingCommas : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.PYTHON_TRAILING_COMMAS

        private val OPENS = setOf("Py:LPAR", "Py:LBRACKET", "Py:LBRACE")
        private val CLOSES = setOf("Py:RPAR", "Py:RBRACKET", "Py:RBRACE")
        private val SOFT_KEYWORDS = setOf("match", "case")
        private val SUBSCRIPTED = setOf("Py:RPAR", "Py:RBRACKET", "Py:RBRACE", "Py:SINGLE_QUOTED_STRING", "Py:TRIPLE_QUOTED_STRING", "Py:FSTRING_END")

        override fun rewrite(tokens: List<Token>): List<Token> = removeTrailingCommas(
            tokens,
            comma = "Py:COMMA",
            closes = CLOSES,
            notAfter = OPENS + "Py:COMMA",
        ) { indices, _, close -> removable(tokens, indices, close) }

        /** Whether the comma before the bracket at [close] among [indices] cannot make a tuple. */
        private fun removable(tokens: List<Token>, indices: List<Int>, close: Int): Boolean {
            val closeType = tokens[indices[close]].type
            if (closeType == "Py:RBRACE") return true
            val open = matchingOpen(tokens, indices, close) ?: return false
            val before = indices.getOrNull(open - 1)?.let { tokens[it] }
            val name = before != null && before.type == "Py:IDENTIFIER" && before.text !in SOFT_KEYWORDS
            return when (closeType) {
                "Py:RPAR" -> {
                    val call = name || (before != null && (before.type in setOf("Py:RPAR", "Py:RBRACKET") || before.type == "Py:IMPORT_KEYWORD"))
                    call || topLevelCommas(tokens, indices, open, close) >= 2
                }
                else -> {
                    val subscript = name || (before != null && before.type in SUBSCRIPTED)
                    !subscript || topLevelCommas(tokens, indices, open, close) >= 2
                }
            }
        }

        /** The position among [indices] of the bracket that [close] closes, or null when the tokens are unbalanced. */
        private fun matchingOpen(tokens: List<Token>, indices: List<Int>, close: Int): Int? {
            var depth = 0
            for (p in close - 1 downTo 0) {
                when (tokens[indices[p]].type) {
                    in CLOSES -> depth++
                    in OPENS -> if (depth == 0) return p else depth--
                }
            }
            return null
        }

        private fun topLevelCommas(tokens: List<Token>, indices: List<Int>, open: Int, close: Int): Int {
            var depth = 0
            var commas = 0
            for (p in open + 1 until close) {
                when (tokens[indices[p]].type) {
                    in OPENS -> depth++
                    in CLOSES -> depth--
                    "Py:COMMA" -> if (depth == 0) commas++
                }
            }
            return commas
        }
    }

    /** R12, R13: parentheses around a whole expression removed where they cannot change how it is grouped. */
    object RedundantParentheses : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.PYTHON_REDUNDANT_PARENTHESES

        internal val ASSIGNMENTS = setOf(
            "Py:EQ", "Py:PLUSEQ", "Py:MINUSEQ", "Py:MULTEQ", "Py:DIVEQ", "Py:FLOORDIVEQ", "Py:PERCEQ", "Py:EXPEQ",
            "Py:ATEQ", "Py:ANDEQ", "Py:OREQ", "Py:XOREQ", "Py:LTLTEQ", "Py:GTGTEQ",
        )

        private val scan = ParenthesesScan(
            lpar = "Py:LPAR",
            rpar = "Py:RPAR",
            lbracket = "Py:LBRACKET",
            opens = setOf("Py:LPAR", "Py:LBRACKET", "Py:LBRACE", "Py:FSTRING_FRAGMENT_START"),
            closes = setOf("Py:RPAR", "Py:RBRACKET", "Py:RBRACE", "Py:FSTRING_FRAGMENT_END"),
            excludedTopLevel = setOf("Py:COMMA", "Py:FOR_KEYWORD", "Py:YIELD_KEYWORD", "Py:COLONEQ"),
            excludedAnywhere = emptySet(),
            statementEnds = setOf("Py:STATEMENT_BREAK", "Py:SEMICOLON"),
            startsStatement = { false },
            allowedAfter = { before ->
                when (before) {
                    "Py:RETURN_KEYWORD" -> setOf(STATEMENT_END)
                    in ASSIGNMENTS -> setOf(STATEMENT_END, "Py:COMMA", "Py:RPAR")
                    "Py:LPAR", "Py:COMMA" -> setOf("Py:COMMA", "Py:RPAR")
                    else -> null
                }
            },
            needsArgumentList = { before -> before == "Py:COMMA" },
            excludedFirst = setOf("Py:MULT", "Py:EXP"),
            contentOnOneLine = true,
        )

        override fun rewrite(tokens: List<Token>): List<Token> = scan.rewrite(tokens)
    }
}
