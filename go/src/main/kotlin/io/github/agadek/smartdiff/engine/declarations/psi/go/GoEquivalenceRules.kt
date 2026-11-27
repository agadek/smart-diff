package io.github.agadek.smartdiff.engine.declarations.psi.go

import io.github.agadek.smartdiff.engine.api.EquivalenceRule
import io.github.agadek.smartdiff.engine.declarations.model.EquivalenceRuleImpl
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.ParenthesesScan
import io.github.agadek.smartdiff.engine.declarations.psi.ParenthesesScan.Companion.STATEMENT_END
import io.github.agadek.smartdiff.engine.declarations.psi.canonicalDecimal
import io.github.agadek.smartdiff.engine.declarations.psi.hexFloatValue
import io.github.agadek.smartdiff.engine.declarations.psi.isHexDigit
import io.github.agadek.smartdiff.engine.declarations.psi.printableInLiteral
import io.github.agadek.smartdiff.engine.declarations.psi.removeTrailingCommas
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Go's equivalence rules (declarations/11 R14 to R19), on the Go lexer's token types as [Token.type] records them
 * (`int`, `string`, `(` and the like). They match token types by name only and use no Go plugin class. [ALL] is in the
 * fixed order the classifier applies them: parentheses, trailing commas, numeric literals, strings, import order.
 */
object GoEquivalenceRules {

    val ALL: List<EquivalenceRuleImpl> = listOf(RedundantParentheses, TrailingCommas, NumericLiterals, StringLiterals, ImportOrder)

    /** The lexer's zero-length token for the semicolon Go inserts at a line break. */
    internal const val NEW_LINE = "new line"

    /** R14: every integer, float and imaginary literal written as its exact decimal value, per kind. */
    object NumericLiterals : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.GO_NUMERIC_LITERALS

        private val INTEGERS = setOf("int", "hex", "oct", "bin")
        /** The lexer reads every imaginary literal as one of these, a prefixed one included. */
        private val IMAGINARY = setOf("decimali", "floati")

        override fun rewrite(tokens: List<Token>): List<Token> = tokens.map { token ->
            when (token.type) {
                in INTEGERS -> integer(token.text)?.let { token.copy(type = "int", text = it) }
                "float" -> floating(token.text)?.let { token.copy(text = it) }
                in IMAGINARY -> imaginary(token.text)?.let { token.copy(type = "decimali", text = it) }
                else -> null
            } ?: token
        }

        /** The value of an integer literal in decimal, a leading `0` read as octal; null when it does not parse. */
        internal fun integer(text: String, legacyOctal: Boolean = true): String? = runCatching {
            val digits = text.replace("_", "")
            val (radix, body) = when {
                digits.take(2).lowercase() == "0x" -> 16 to digits.substring(2)
                digits.take(2).lowercase() == "0o" -> 8 to digits.substring(2)
                digits.take(2).lowercase() == "0b" -> 2 to digits.substring(2)
                legacyOctal && digits.length > 1 && digits[0] == '0' -> 8 to digits.substring(1)
                else -> 10 to digits
            }
            if (body.isEmpty() || !body.all { if (radix == 16) isHexDigit(it) else it.digitToIntOrNull(radix) != null }) {
                return@runCatching null
            }
            BigInteger(body, radix).toString()
        }.getOrNull()

        /** The exact value of a decimal or hexadecimal float literal; null when it does not parse. */
        internal fun floating(text: String): String? = runCatching {
            val digits = text.replace("_", "")
            val value = if (digits.take(2).lowercase() == "0x") {
                hexFloatValue(digits.substring(2))
            } else {
                if (digits.isEmpty() || !digits.all { it.isDigit() || it in ".eE+-" }) null else BigDecimal(digits)
            }
            value?.let(::canonicalDecimal)
        }.getOrNull()

        /**
         * The exact value of an imaginary literal followed by `i`. A body of decimal digits is decimal even with a
         * leading `0` (`0123i` is `123i`); null when it does not parse.
         */
        internal fun imaginary(text: String): String? {
            if (!text.endsWith("i")) return null
            val body = text.dropLast(1)
            val plain = body.replace("_", "")
            val value = if (plain.isNotEmpty() && plain.all { it.isDigit() }) {
                integer(body, legacyOctal = false)
            } else if (plain.take(2).lowercase() in setOf("0o", "0b") || (plain.take(2).lowercase() == "0x" && plain.none { it == 'p' || it == 'P' || it == '.' })) {
                integer(body)
            } else {
                floating(body)
            }
            return value?.let { "${it}i" }
        }
    }

    /** R15: every string literal written as the bytes it stands for, every rune literal as its rune. */
    object StringLiterals : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.GO_STRING_LITERALS

        override fun rewrite(tokens: List<Token>): List<Token> = tokens.map { token ->
            when (token.type) {
                "string" -> interpreted(token.text)?.let { token.copy(text = writeBytes(it)) }
                "raw_string" -> raw(token.text)?.let { token.copy(type = "string", text = writeBytes(it)) }
                "char" -> rune(token.text)?.let { token.copy(text = writeRune(it)) }
                else -> null
            } ?: token
        }

        /** The bytes of an interpreted string literal, or null when it keeps its text. */
        internal fun interpreted(text: String): ByteArray? {
            if (text.length < 2 || text.first() != '"' || text.last() != '"') return null
            val out = ByteArrayOutputStream()
            val body = text.substring(1, text.length - 1)
            var i = 0
            while (i < body.length) {
                if (body[i] != '\\') {
                    val cp = body.codePointAt(i)
                    out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
                    i += Character.charCount(cp)
                    continue
                }
                val (value, length, isByte) = escape(body, i, '"') ?: return null
                if (isByte) out.write(value) else out.write(String(Character.toChars(value)).toByteArray(Charsets.UTF_8))
                i += length
            }
            return out.toByteArray()
        }

        /** The bytes of a raw string literal, carriage returns dropped. */
        internal fun raw(text: String): ByteArray? {
            if (text.length < 2 || text.first() != '`' || text.last() != '`') return null
            return text.substring(1, text.length - 1).replace("\r", "").toByteArray(Charsets.UTF_8)
        }

        /** The code point of a rune literal, or null when it keeps its text. */
        internal fun rune(text: String): Int? {
            if (text.length < 3 || text.first() != '\'' || text.last() != '\'') return null
            val body = text.substring(1, text.length - 1)
            if (body[0] != '\\') {
                val cp = body.codePointAt(0)
                return if (Character.charCount(cp) == body.length) cp else null
            }
            val (value, length, _) = escape(body, 0, '\'') ?: return null
            return if (length == body.length) value else null
        }

        /**
         * The escape at [i] in [body]: its value, its length, and whether the value is one byte (`\x`, octal) rather
         * than a code point. [quote] is the one quote that may be escaped. Null for an escape that keeps the text.
         */
        private fun escape(body: String, i: Int, quote: Char): Triple<Int, Int, Boolean>? {
            if (i + 1 >= body.length) return null
            val next = body[i + 1]
            val simple = when (next) {
                'a' -> 7
                'b' -> 8
                'f' -> 12
                'n' -> 10
                'r' -> 13
                't' -> 9
                'v' -> 11
                '\\' -> '\\'.code
                quote -> quote.code
                else -> null
            }
            if (simple != null) return Triple(simple, 2, true)
            return when (next) {
                'x' -> {
                    if (i + 4 > body.length) return null
                    val hex = body.substring(i + 2, i + 4)
                    if (!hex.all(::isHexDigit)) null else Triple(hex.toInt(16), 4, true)
                }
                in '0'..'7' -> {
                    if (i + 4 > body.length) return null
                    val oct = body.substring(i + 1, i + 4)
                    if (!oct.all { it in '0'..'7' }) return null
                    val value = oct.toInt(8)
                    if (value > 0xFF) null else Triple(value, 4, true)
                }
                'u', 'U' -> {
                    val length = if (next == 'u') 4 else 8
                    if (i + 2 + length > body.length) return null
                    val hex = body.substring(i + 2, i + 2 + length)
                    if (!hex.all(::isHexDigit)) return null
                    val code = hex.toLong(16)
                    if (code > 0x10FFFF || code in 0xD800..0xDFFF) null else Triple(code.toInt(), 2 + length, false)
                }
                else -> null
            }
        }

        /** [bytes] as the canonical string text; only compared, never compiled. */
        private fun writeBytes(bytes: ByteArray): String = buildString {
            append('"')
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                when {
                    v == '"'.code -> append("\\\"")
                    v == '\\'.code -> append("\\\\")
                    v in 0x20..0x7E -> append(v.toChar())
                    else -> append("\\x%02X".format(v))
                }
            }
            append('"')
        }

        /** A rune as the canonical rune text. In a rune, `\x` and octal escapes are code points below 256. */
        private fun writeRune(cp: Int): String {
            val printable = Character.charCount(cp) == 1 && printableInLiteral(cp.toChar())
            val body = when {
                cp == '\''.code -> "\\'"
                cp == '\\'.code -> "\\\\"
                printable -> cp.toChar().toString()
                else -> "\\U%08X".format(cp)
            }
            return "'$body'"
        }
    }

    /** R16: a trailing comma before `)` or `}` removed; never one before `]`. */
    object TrailingCommas : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.GO_TRAILING_COMMAS

        override fun rewrite(tokens: List<Token>): List<Token> = removeTrailingCommas(
            tokens,
            comma = ",",
            closes = setOf(")", "}"),
            notAfter = setOf(",", "(", "{"),
        )
    }

    /** R17, R18: parentheses around a whole expression removed where they cannot change how it is grouped. */
    object RedundantParentheses : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.GO_REDUNDANT_PARENTHESES

        internal val ASSIGNMENTS = setOf("=", ":=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", "&^=")

        private val scan = ParenthesesScan(
            lpar = "(",
            rpar = ")",
            lbracket = "[",
            opens = setOf("(", "[", "{"),
            closes = setOf(")", "]", "}"),
            excludedTopLevel = setOf(","),
            excludedAnywhere = setOf("{"),
            statementEnds = setOf(";", "}", NEW_LINE),
            startsStatement = { false },
            allowedAfter = { before ->
                when (before) {
                    "return" -> setOf(STATEMENT_END, ",")
                    in ASSIGNMENTS -> setOf(STATEMENT_END, ",", ")")
                    "(", "," -> setOf(",", ")")
                    else -> null
                }
            },
            needsArgumentList = { before -> before == "," },
        )

        override fun rewrite(tokens: List<Token>): List<Token> = scan.rewrite(tokens)
    }

    /** R19: the specs of each parenthesized import block sorted and written in one layout. */
    object ImportOrder : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.GO_IMPORT_ORDER

        private val PATHS = setOf("string", "raw_string")

        override fun rewrite(tokens: List<Token>): List<Token> {
            var current = tokens
            var from = 0
            while (true) {
                val block = nextBlock(current, from) ?: return current
                val (start, end, specs) = block
                val sorted = specs.sortedWith(compareBy({ it.second.text }, { it.first?.text ?: "" }))
                val rebuilt = buildList {
                    add(current[start]) // import
                    add(Token("GO_WHITESPACE", " ", TokenKind.WHITESPACE))
                    add(current[openIndex(current, start)]) // (
                    for ((alias, path) in sorted) {
                        add(Token("GO_WS_NEW_LINES", "\n", TokenKind.WHITESPACE))
                        add(Token("GO_WHITESPACE", "\t", TokenKind.WHITESPACE))
                        if (alias != null) {
                            add(alias)
                            add(Token("GO_WHITESPACE", " ", TokenKind.WHITESPACE))
                        }
                        add(path)
                        add(Token(NEW_LINE, "", TokenKind.OTHER))
                    }
                    add(Token("GO_WS_NEW_LINES", "\n", TokenKind.WHITESPACE))
                    add(current[end])
                }
                current = current.subList(0, start) + rebuilt + current.subList(end + 1, current.size)
                from = start + rebuilt.size
            }
        }

        private fun openIndex(tokens: List<Token>, importIndex: Int): Int =
            (importIndex + 1 until tokens.size).first { tokens[it].kind != TokenKind.WHITESPACE }

        /**
         * The next import block at or after [from]: the index of `import`, of its `)`, and its specs as alias and path,
         * or null when there is none. A block with a comment or another token inside is skipped.
         */
        private fun nextBlock(tokens: List<Token>, from: Int): Triple<Int, Int, List<Pair<Token?, Token>>>? {
            var i = from
            while (i < tokens.size) {
                if (tokens[i].type == "import") {
                    val parsed = parse(tokens, i)
                    if (parsed != null) return parsed
                }
                i++
            }
            return null
        }

        private fun parse(tokens: List<Token>, importIndex: Int): Triple<Int, Int, List<Pair<Token?, Token>>>? {
            val open = (importIndex + 1 until tokens.size).firstOrNull { tokens[it].kind != TokenKind.WHITESPACE } ?: return null
            if (tokens[open].type != "(") return null
            val specs = mutableListOf<Pair<Token?, Token>>()
            var alias: Token? = null
            var i = open + 1
            while (i < tokens.size) {
                val token = tokens[i]
                when {
                    token.kind == TokenKind.WHITESPACE -> Unit
                    token.kind == TokenKind.COMMENT -> return null
                    token.type == ")" -> return if (alias == null && specs.isNotEmpty()) Triple(importIndex, i, specs) else null
                    token.type == ";" || token.type == NEW_LINE -> if (alias != null) return null
                    token.type in PATHS -> { specs += alias to token; alias = null }
                    (token.type == "identifier" || token.type == ".") && alias == null -> alias = token
                    else -> return null
                }
                i++
            }
            return null
        }
    }
}
