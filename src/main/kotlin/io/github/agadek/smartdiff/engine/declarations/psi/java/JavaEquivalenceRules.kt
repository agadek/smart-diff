package io.github.agadek.smartdiff.engine.declarations.psi.java

import io.github.agadek.smartdiff.engine.api.EquivalenceRule
import io.github.agadek.smartdiff.engine.declarations.model.EquivalenceRuleImpl
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Java's equivalence rules (declarations/08 R10 to R12), on the Java lexer's token types as [Token.type] records them.
 * They are pure token functions and need no PSI, types or name resolution. [ALL] is in the fixed order the classifier
 * applies them: parentheses, then numeric literals, then string escapes.
 */
object JavaEquivalenceRules {

    val ALL: List<EquivalenceRuleImpl> = listOf(RedundantParentheses, NumericLiterals, StringEscapes)

    /** R10: every numeric literal written as its exact value in decimal, with its type's suffix in upper case. */
    object NumericLiterals : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.JAVA_NUMERIC_LITERALS

        private val INTEGER_TYPES = setOf("INTEGER_LITERAL", "LONG_LITERAL")
        private val FLOATING_TYPES = setOf("FLOAT_LITERAL", "DOUBLE_LITERAL")

        override fun rewrite(tokens: List<Token>): List<Token> = tokens.map { token ->
            val canonical = when (token.type) {
                in INTEGER_TYPES -> integer(token.text)
                in FLOATING_TYPES -> floating(token.text)
                else -> null
            }
            if (canonical == null) token else token.copy(text = canonical)
        }

        /** The value of an `int` or `long` literal in decimal, as the compiler reads it; null when it does not parse. */
        internal fun integer(text: String): String? = runCatching {
            var digits = text.replace("_", "")
            val long = digits.endsWith("L") || digits.endsWith("l")
            if (long) digits = digits.dropLast(1)
            val (radix, body) = when {
                digits.startsWith("0x") || digits.startsWith("0X") -> 16 to digits.substring(2)
                digits.startsWith("0b") || digits.startsWith("0B") -> 2 to digits.substring(2)
                digits.length > 1 && digits.startsWith("0") -> 8 to digits.substring(1)
                else -> 10 to digits
            }
            if (body.isEmpty()) return@runCatching null
            var value = BigInteger(body, radix)
            val bits = if (long) 64 else 32
            if (value.bitLength() > bits) return@runCatching null
            // A hex, octal or binary literal is the two's-complement bit pattern: 0xFFFFFFFF is -1. A decimal literal
            // is never negative; 2147483648 is legal only after a unary minus and keeps its value.
            if (radix != 10 && value.testBit(bits - 1)) value = value.subtract(BigInteger.ONE.shiftLeft(bits))
            value.toString() + if (long) "L" else ""
        }.getOrNull()

        /** The exact value of a `float` or `double` literal, plain, with `F` or `D`; null when it does not parse. */
        internal fun floating(text: String): String? = runCatching {
            var digits = text.replace("_", "")
            val suffix = when (digits.lastOrNull()) {
                'f', 'F' -> "F"
                'd', 'D' -> "D"
                else -> null
            }
            if (suffix != null) digits = digits.dropLast(1)
            val value = if (digits.startsWith("0x") || digits.startsWith("0X")) hexFloat(digits.substring(2)) else BigDecimal(digits)
            value?.let { it.stripTrailingZeros().toPlainString() + (suffix ?: "D") }
        }.getOrNull()

        /** The exact value of a hexadecimal floating-point literal's digits after `0x`, such as `1.8p1`. */
        private fun hexFloat(text: String): BigDecimal? {
            val p = text.indexOfFirst { it == 'p' || it == 'P' }
            if (p < 0) return null
            val mantissa = text.substring(0, p)
            val exponent = text.substring(p + 1).toInt()
            val dot = mantissa.indexOf('.')
            val whole = mantissa.replace(".", "")
            if (whole.isEmpty()) return null
            val fractionDigits = if (dot < 0) 0 else mantissa.length - dot - 1
            val binaryExponent = exponent - 4 * fractionDigits
            val m = BigDecimal(BigInteger(whole, 16))
            return if (binaryExponent >= 0) m.multiply(BigDecimal(BigInteger.TWO.pow(binaryExponent)))
            else m.divide(BigDecimal(BigInteger.TWO.pow(-binaryExponent))) // a power of two always divides exactly
        }
    }

    /** R11: every string and character literal written with one canonical escape for each character. */
    object StringEscapes : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.JAVA_STRING_ESCAPES

        override fun rewrite(tokens: List<Token>): List<Token> = tokens.map { token ->
            val quote = when (token.type) {
                "STRING_LITERAL" -> '"'
                "CHARACTER_LITERAL" -> '\''
                else -> null
            }
            val canonical = quote?.let { canonical(token.text, it) }
            if (canonical == null) token else token.copy(text = canonical)
        }

        /** The literal [text] in canonical form, or null when it is not a plain literal or has an unknown escape. */
        internal fun canonical(text: String, quote: Char): String? {
            if (text.length < 2 || text.first() != quote || text.last() != quote) return null
            val decoded = decode(text.substring(1, text.length - 1)) ?: return null
            return buildString {
                append(quote)
                decoded.forEach { append(encode(it)) }
                append(quote)
            }
        }

        /** The characters [body] stands for, or null for an escape this rule does not know. */
        private fun decode(body: String): String? {
            val out = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c != '\\') { out.append(c); i++; continue }
                if (i + 1 >= body.length) return null
                val next = body[i + 1]
                when {
                    next == 'u' -> {
                        var j = i + 1
                        while (j < body.length && body[j] == 'u') j++
                        if (j + 4 > body.length) return null
                        val hex = body.substring(j, j + 4)
                        if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
                        val ch = hex.toInt(16).toChar()
                        // A unicode escape of a backslash starts another escape before lexing; leave such a literal as
                        // written rather than decode it in the wrong order.
                        if (ch == '\\') return null
                        out.append(ch)
                        i = j + 4
                    }
                    next in '0'..'7' -> {
                        val max = if (next <= '3') 3 else 2
                        var j = i + 1
                        while (j < body.length && j - (i + 1) < max && body[j] in '0'..'7') j++
                        out.append(body.substring(i + 1, j).toInt(8).toChar())
                        i = j
                    }
                    else -> {
                        out.append(
                            when (next) {
                                'b' -> '\b'
                                't' -> '\t'
                                'n' -> '\n'
                                'f' -> '\u000C'
                                'r' -> '\r'
                                's' -> ' '
                                '"' -> '"'
                                '\'' -> '\''
                                '\\' -> '\\'
                                else -> return null
                            },
                        )
                        i += 2
                    }
                }
            }
            return out.toString()
        }

        /** One character as the canonical text writes it; only compared, never compiled. */
        private fun encode(c: Char): String = when {
            c == '\'' -> "\\'"
            c == '"' -> "\\\""
            c == '\\' -> "\\\\"
            !printable(c) -> "\\u%04X".format(c.code)
            else -> c.toString()
        }

        private fun printable(c: Char): Boolean {
            if (Character.isISOControl(c) || !Character.isDefined(c)) return false
            return when (Character.getType(c).toByte()) {
                Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR ->
                    c == ' '
                else -> true
            }
        }
    }

    /** R12: parentheses around a whole expression removed where they cannot change how it is grouped. */
    object RedundantParentheses : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.JAVA_REDUNDANT_PARENTHESES

        /**
         * The assignment operators. The Java lexer writes `>=`, `>>=` and `>>>=` as `GT` tokens followed directly by `EQ`,
         * so [operator] names those by the whole operator first; `GTGTEQ` and `GTGTGTEQ` are those names.
         */
        private val ASSIGNMENTS = setOf(
            "EQ", "PLUSEQ", "MINUSEQ", "ASTERISKEQ", "DIVEQ", "PERCEQ", "ANDEQ", "OREQ", "XOREQ", "LTLTEQ", "GTGTEQ", "GTGTGTEQ",
        )

        /**
         * The operator the meaningful token at [index] belongs to. An `EQ` right after one `GT` is the comparison `>=`
         * (`GE`), after two or three it is `>>=` or `>>>=`; any other token is its own type.
         */
        private fun operator(tokens: List<Token>, index: Int): String {
            val type = tokens[index].type
            if (type != "EQ") return type
            var gts = 0
            while (index - gts - 1 >= 0 && tokens[index - gts - 1].type == "GT") gts++
            return when (gts) {
                0 -> "EQ"
                1 -> "GE"
                2 -> "GTGTEQ"
                3 -> "GTGTGTEQ"
                else -> "GT"
            }
        }
        private val OPEN = setOf("LPARENTH", "LBRACKET", "LBRACE")
        private val CLOSE = setOf("RPARENTH", "RBRACKET", "RBRACE")

        /** The tokens allowed after the `)` for each token before the `(` (the details file's table). */
        private fun allowedAfter(before: String): Set<String>? = when (before) {
            "RETURN_KEYWORD", "THROW_KEYWORD" -> setOf("SEMICOLON")
            in ASSIGNMENTS -> setOf("SEMICOLON", "COMMA", "RPARENTH")
            "LPARENTH", "COMMA" -> setOf("COMMA", "RPARENTH")
            else -> null
        }

        override fun rewrite(tokens: List<Token>): List<Token> {
            var current = tokens
            while (true) {
                val pair = removable(current) ?: return current
                current = current.filterIndexed { index, _ -> index != pair.first && index != pair.second }
            }
        }

        private fun meaningful(token: Token) = token.kind != TokenKind.WHITESPACE && token.kind != TokenKind.COMMENT

        /** The innermost removable pair, as the indices of its `(` and `)`, or null when none is. */
        private fun removable(tokens: List<Token>): Pair<Int, Int>? {
            val indices = tokens.indices.filter { meaningful(tokens[it]) }
            val stack = ArrayDeque<Int>() // positions in [indices] of open brackets of any kind
            for ((position, index) in indices.withIndex()) {
                val type = tokens[index].type
                when (type) {
                    in OPEN -> stack.addLast(position)
                    in CLOSE -> {
                        val open = stack.removeLastOrNull() ?: return null // unbalanced: leave the tokens as they are
                        if (type == "RPARENTH" && tokens[indices[open]].type == "LPARENTH" &&
                            isRedundant(tokens, indices, open, position)
                        ) {
                            return indices[open] to index
                        }
                    }
                }
            }
            return null
        }

        private fun isRedundant(tokens: List<Token>, indices: List<Int>, open: Int, close: Int): Boolean {
            if (close == open + 1) return false // empty content
            val before = indices.getOrNull(open - 1)?.let { operator(tokens, it) } ?: return false
            val after = indices.getOrNull(close + 1)?.let { tokens[it].type } ?: return false
            if (after !in (allowedAfter(before) ?: return false)) return false
            var depth = 0
            for (position in open + 1 until close) {
                val type = tokens[indices[position]].type
                when {
                    type in OPEN -> depth++
                    type in CLOSE -> depth--
                    depth == 0 && (type == "COMMA" || type == "ARROW") -> return false
                }
            }
            return true
        }
    }
}
