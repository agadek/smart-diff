package io.github.agadek.smartdiff.engine.declarations.psi.scala

import io.github.agadek.smartdiff.engine.api.EquivalenceRule
import io.github.agadek.smartdiff.engine.declarations.model.EquivalenceRuleImpl
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.psi.ParenthesesScan
import io.github.agadek.smartdiff.engine.declarations.psi.ParenthesesScan.Companion.STATEMENT_END
import io.github.agadek.smartdiff.engine.declarations.psi.isHexDigit
import io.github.agadek.smartdiff.engine.declarations.psi.printableInLiteral
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Scala's equivalence rules (declarations/09 R9 to R13), on the Scala lexer's token types as [Token.type] records them
 * (each `ScalaTokenTypes` constant's `toString()`). They match token types by name only and use no Scala plugin class,
 * so they load without the plugin. The same rules serve Scala 2 and Scala 3; the classifier compares line breaks under
 * each dialect's policy (R13). [ALL] is in the fixed order the classifier applies them: parentheses, numeric literals,
 * string escapes.
 */
object ScalaEquivalenceRules {

    val ALL: List<EquivalenceRuleImpl> = listOf(RedundantParentheses, NumericLiterals, StringEscapes)

    /** R9: every decimal or hexadecimal literal written as its value in decimal, with its type's suffix in upper case. */
    object NumericLiterals : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.SCALA_NUMERIC_LITERALS

        private val INTEGER_TYPES = setOf("integer", "long")
        private val FLOATING_TYPES = setOf("float", "double")

        override fun rewrite(tokens: List<Token>): List<Token> = tokens.map { token ->
            val canonical = when (token.type) {
                in INTEGER_TYPES -> integer(token.text)
                in FLOATING_TYPES -> floating(token.text)
                else -> null
            }
            if (canonical == null) token else token.copy(text = canonical)
        }

        /** The value of an `Int` or `Long` literal in decimal, as the compiler reads it; null when it does not parse. */
        internal fun integer(text: String): String? = runCatching {
            var digits = text.replace("_", "")
            val long = digits.endsWith("L") || digits.endsWith("l")
            if (long) digits = digits.dropLast(1)
            val hex = digits.startsWith("0x") || digits.startsWith("0X")
            val body = if (hex) digits.substring(2) else digits
            // Binary literals and literals with a leading zero are out of scope and keep their text.
            if (body.isEmpty() || (!hex && body.length > 1 && body.startsWith("0"))) return@runCatching null
            if (!body.all { if (hex) isHexDigit(it) else it.isDigit() }) return@runCatching null
            var value = BigInteger(body, if (hex) 16 else 10)
            val bits = if (long) 64 else 32
            if (value.bitLength() > bits) return@runCatching null
            // A hexadecimal literal is the two's-complement bit pattern: 0xFFFFFFFF is -1.
            if (hex && value.testBit(bits - 1)) value = value.subtract(BigInteger.ONE.shiftLeft(bits))
            value.toString() + if (long) "L" else ""
        }.getOrNull()

        /** The exact value of a `Float` or `Double` literal, plain, with `F` or `D`; null when it does not parse. */
        internal fun floating(text: String): String? = runCatching {
            var digits = text.replace("_", "")
            val suffix = when (digits.lastOrNull()) {
                'f', 'F' -> "F"
                'd', 'D' -> "D"
                else -> null
            }
            if (suffix != null) digits = digits.dropLast(1)
            if (digits.isEmpty() || !digits.all { it.isDigit() || it in ".eE+-" }) return@runCatching null
            BigDecimal(digits).stripTrailingZeros().toPlainString() + (suffix ?: "D")
        }.getOrNull()
    }

    /** R10: every plain string and character literal written with one canonical escape for each character. */
    object StringEscapes : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.SCALA_STRING_ESCAPES

        /** The characters whose unicode escape means something else before Scala 2.13, so such a literal keeps its text. */
        private val UNSAFE_UNICODE = setOf('"', '\'', '\\', '\n', '\r')

        override fun rewrite(tokens: List<Token>): List<Token> = tokens.map { token ->
            val quote = when (token.type) {
                "string content" -> '"'
                "Character" -> '\''
                else -> null
            }
            val canonical = quote?.let { canonical(token.text, it) }
            if (canonical == null) token else token.copy(text = canonical)
        }

        /** The literal [text] in canonical form, or null when it is not a plain literal or must keep its text. */
        internal fun canonical(text: String, quote: Char): String? {
            if (text.length < 2 || text.first() != quote || text.last() != quote) return null
            if (quote == '"' && text.startsWith("\"\"\"")) return null // a triple-quoted string
            val decoded = decode(text.substring(1, text.length - 1)) ?: return null
            return buildString {
                append(quote)
                decoded.forEach { append(encode(it)) }
                append(quote)
            }
        }

        /** The characters [body] stands for, or null for an escape this rule does not know or must not decode. */
        private fun decode(body: String): String? {
            val out = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c != '\\') { out.append(c); i++; continue }
                if (i + 1 >= body.length) return null
                val next = body[i + 1]
                if (next == 'u') {
                    var j = i + 1
                    while (j < body.length && body[j] == 'u') j++
                    if (j + 4 > body.length) return null
                    val hex = body.substring(j, j + 4)
                    if (!hex.all(::isHexDigit)) return null
                    val ch = hex.toInt(16).toChar()
                    if (ch in UNSAFE_UNICODE) return null
                    out.append(ch)
                    i = j + 4
                    continue
                }
                out.append(
                    when (next) {
                        'b' -> '\b'
                        't' -> '\t'
                        'n' -> '\n'
                        'f' -> '\u000C'
                        'r' -> '\r'
                        '"' -> '"'
                        '\'' -> '\''
                        '\\' -> '\\'
                        else -> return null // octal escapes are an error since Scala 2.13
                    },
                )
                i += 2
            }
            return out.toString()
        }

        /**
         * One character as the canonical text writes it; only compared, never compiled. A line break is written as its
         * simple escape, so it never matches a literal kept as written with a unicode escape of it.
         */
        private fun encode(c: Char): String = when {
            c == '\'' -> "\\'"
            c == '"' -> "\\\""
            c == '\\' -> "\\\\"
            c == '\n' -> "\\n"
            c == '\r' -> "\\r"
            !printableInLiteral(c) -> "\\u%04X".format(c.code)
            else -> c.toString()
        }
    }

    /** R11, R12: parentheses around a whole expression removed where they cannot change how it is grouped. */
    object RedundantParentheses : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.SCALA_REDUNDANT_PARENTHESES

        private val STARTS_STATEMENT = setOf(
            "val", "var", "def", "type", "class", "object", "trait", "case", "import", "return", "throw", "if", "while",
            "for", "try", "else",
        )

        private val scan = ParenthesesScan(
            lpar = "(",
            rpar = ")",
            lbracket = "[",
            opens = setOf("(", "[", "{"),
            closes = setOf(")", "]", "}"),
            excludedTopLevel = setOf(",", ":", "=", "=>"),
            excludedAnywhere = setOf("_"),
            statementEnds = setOf(";", "}", "else"),
            startsStatement = { it.type in STARTS_STATEMENT },
            allowedAfter = { before ->
                when (before) {
                    "return", "throw" -> setOf(STATEMENT_END)
                    "=" -> setOf(STATEMENT_END, ",", ")")
                    "(", "," -> setOf(",", ")")
                    else -> null
                }
            },
            needsArgumentList = { before -> before == "," },
        )

        override fun rewrite(tokens: List<Token>): List<Token> = scan.rewrite(tokens)
    }
}
