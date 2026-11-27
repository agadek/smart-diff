package io.github.agadek.smartdiff.engine.declarations.psi.javascript

import io.github.agadek.smartdiff.engine.api.EquivalenceRule
import io.github.agadek.smartdiff.engine.declarations.model.EquivalenceRuleImpl
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.psi.ParenthesesScan
import io.github.agadek.smartdiff.engine.declarations.psi.ParenthesesScan.Companion.STATEMENT_END
import io.github.agadek.smartdiff.engine.declarations.psi.canonicalDecimal
import io.github.agadek.smartdiff.engine.declarations.psi.isHexDigit
import io.github.agadek.smartdiff.engine.declarations.psi.meaningfulIndices
import io.github.agadek.smartdiff.engine.declarations.psi.printableInLiteral
import io.github.agadek.smartdiff.engine.declarations.psi.removeTrailingCommas
import java.math.BigDecimal
import java.math.BigInteger

/**
 * JavaScript and TypeScript equivalence rules (declarations/11 R4 to R8, R20), on the JavaScript lexer's token types as
 * [Token.type] records them (`JS:NUMERIC_LITERAL` and the like). They match token types by name only and use no
 * JavaScript plugin class. JavaScript, TypeScript, JSX and TSX share them. [ALL] is in the fixed order the classifier
 * applies them: parentheses, trailing commas, numeric literals, strings.
 */
object JavaScriptEquivalenceRules {

    val ALL: List<EquivalenceRuleImpl> = listOf(RedundantParentheses, TrailingCommas, NumericLiterals, StringQuotes)

    /**
     * Whitespace and comments, including whitespace the provider marked significant (languages/16 R14, R15), which
     * keeps its text but becomes [TokenKind.OTHER]: the rules skip it as layout and never remove it.
     */
    internal fun isLayout(token: Token): Boolean =
        token.kind == TokenKind.WHITESPACE || token.kind == TokenKind.COMMENT || (token.text.isNotEmpty() && token.text.isBlank())

    /** R4: every Number literal written as its exact decimal value, every BigInt as its decimal value with `n`. */
    object NumericLiterals : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.JS_NUMERIC_LITERALS

        override fun rewrite(tokens: List<Token>): List<Token> = tokens.map { token ->
            val canonical = if (token.type == "JS:NUMERIC_LITERAL") canonical(token.text) else null
            if (canonical == null) token else token.copy(text = canonical)
        }

        /** The canonical text of a numeric literal, or null when it keeps its text. */
        internal fun canonical(text: String): String? = runCatching {
            var digits = text.replace("_", "")
            if (digits.length > 1 && digits[0] == '0' && digits[1].isDigit()) return@runCatching null // legacy octal, 08
            val bigInt = digits.endsWith("n")
            if (bigInt) digits = digits.dropLast(1)
            val radix = when (digits.take(2).lowercase()) {
                "0x" -> 16
                "0o" -> 8
                "0b" -> 2
                else -> 10
            }
            if (radix != 10) {
                val body = digits.substring(2)
                if (body.isEmpty() || !body.all { if (radix == 16) isHexDigit(it) else it.digitToIntOrNull(radix) != null }) {
                    return@runCatching null
                }
                val value = BigInteger(body, radix)
                return@runCatching if (bigInt) "${value}n" else canonicalDecimal(BigDecimal(value))
            }
            if (digits.isEmpty() || !digits.all { it.isDigit() || it in ".eE+-" }) return@runCatching null
            if (bigInt) {
                if (!digits.all { it.isDigit() }) return@runCatching null
                return@runCatching "${BigInteger(digits)}n"
            }
            canonicalDecimal(BigDecimal(digits))
        }.getOrNull()
    }

    /** R5: every string literal written in double quotes with one canonical escape for each character. */
    object StringQuotes : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.JS_STRING_QUOTES

        private val DIRECTIVE_BEFORE = setOf("JS:LBRACE", "JS:SEMICOLON")
        private val DIRECTIVE_AFTER = setOf("JS:SEMICOLON", "JS:RBRACE")

        override fun rewrite(tokens: List<Token>): List<Token> {
            val indices = meaningfulIndices(tokens, ::isLayout)
            val position = IntArray(tokens.size) { -1 }
            indices.forEachIndexed { p, i -> position[i] = p }
            return tokens.mapIndexed { index, token ->
                if (token.type != "JS:STRING_LITERAL") return@mapIndexed token
                val canonical = if (mayBeDirective(tokens, indices, position[index])) directive(token.text) else canonical(token.text)
                if (canonical == null) token else token.copy(text = canonical)
            }
        }

        /** Whether the string at [p] among [indices] can be a directive: alone at the start of a statement list. */
        private fun mayBeDirective(tokens: List<Token>, indices: List<Int>, p: Int): Boolean {
            val previous = indices.getOrNull(p - 1)?.let { tokens[it] }
            if (previous != null && previous.type !in DIRECTIVE_BEFORE) return false
            val nextIndex = indices.getOrNull(p + 1) ?: return true
            if (tokens[nextIndex].type in DIRECTIVE_AFTER) return true
            return (indices[p] + 1 until nextIndex).any { i ->
                tokens[i].kind != TokenKind.COMMENT && (tokens[i].text.contains('\n') || tokens[i].text.contains('\r'))
            }
        }

        /** A possible directive in double quotes, only when its body has no backslash and no quote. */
        internal fun directive(text: String): String? {
            if (text.length < 2 || text.first() !in "'\"" || text.last() != text.first()) return null
            val body = text.substring(1, text.length - 1)
            if (body.any { it == '\\' || it == '"' || it == '\'' }) return null
            return "\"$body\""
        }

        /** The literal [text] in canonical form, or null when it keeps its text. */
        internal fun canonical(text: String): String? {
            if (text.length < 2 || text.first() !in "'\"" || text.last() != text.first()) return null
            val decoded = decode(text.substring(1, text.length - 1)) ?: return null
            return buildString {
                append('"')
                decoded.forEach { append(encode(it)) }
                append('"')
            }
        }

        /** The characters [body] stands for, or null for an escape this rule does not decode. */
        private fun decode(body: String): String? {
            val out = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c != '\\') { out.append(c); i++; continue }
                if (i + 1 >= body.length) return null
                val next = body[i + 1]
                when (next) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'v' -> out.append('\u000B')
                    '\'', '"', '\\' -> out.append(next)
                    '0' -> {
                        if (i + 2 < body.length && body[i + 2].isDigit()) return null // legacy octal
                        out.append('\u0000')
                    }
                    'x' -> {
                        if (i + 4 > body.length) return null
                        val hex = body.substring(i + 2, i + 4)
                        if (!hex.all(::isHexDigit)) return null
                        out.append(hex.toInt(16).toChar())
                        i += 4
                        continue
                    }
                    'u' -> {
                        if (i + 2 < body.length && body[i + 2] == '{') {
                            val end = body.indexOf('}', i + 3)
                            if (end < 0) return null
                            val hex = body.substring(i + 3, end)
                            if (hex.isEmpty() || hex.length > 8 || !hex.all(::isHexDigit)) return null
                            val code = hex.toLong(16)
                            if (code > 0x10FFFF) return null
                            out.appendCodePoint(code.toInt())
                            i = end + 1
                        } else {
                            if (i + 6 > body.length) return null
                            val hex = body.substring(i + 2, i + 6)
                            if (!hex.all(::isHexDigit)) return null
                            out.append(hex.toInt(16).toChar())
                            i += 6
                        }
                        continue
                    }
                    else -> return null // a line continuation, legacy octal or an identity escape keeps the text
                }
                i += 2
            }
            return out.toString()
        }

        /** One character as the canonical text writes it; only compared, never compiled. */
        private fun encode(c: Char): String = when {
            c == '"' -> "\\\""
            c == '\\' -> "\\\\"
            c == '\n' -> "\\n"
            c == '\r' -> "\\r"
            !printableInLiteral(c) -> "\\u%04X".format(c.code)
            else -> c.toString()
        }
    }

    /** R6: a trailing comma before `)`, `]` or `}` removed, never one after an array hole. */
    object TrailingCommas : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.JS_TRAILING_COMMAS

        override fun rewrite(tokens: List<Token>): List<Token> = removeTrailingCommas(
            tokens,
            comma = "JS:COMMA",
            closes = setOf("JS:RPAR", "JS:RBRACKET", "JS:RBRACE"),
            notAfter = setOf("JS:COMMA", "JS:LBRACKET", "JS:LPAR", "JS:LBRACE"),
            layout = ::isLayout,
        )
    }

    /** R7, R8: parentheses around a whole expression removed where they cannot change how it is grouped. */
    object RedundantParentheses : EquivalenceRuleImpl {
        override val rule = EquivalenceRule.JS_REDUNDANT_PARENTHESES

        internal val ASSIGNMENTS = setOf(
            "JS:EQ", "JS:PLUSEQ", "JS:MINUSEQ", "JS:MULTEQ", "JS:DIVEQ", "JS:PERCEQ", "JS:MULTMULTEQ", "JS:LTLTEQ",
            "JS:GTGTEQ", "JS:GTGTGTEQ", "JS:ANDEQ", "JS:OREQ", "JS:XOREQ", "JS:AND_AND_EQ", "JS:OR_OR_EQ",
            "JS:QUEST_QUEST_EQ",
        )

        internal val STARTS_STATEMENT = setOf(
            "JS:CONST_KEYWORD", "JS:LET_KEYWORD", "JS:VAR_KEYWORD", "JS:FUNCTION_KEYWORD", "JS:CLASS_KEYWORD",
            "JS:RETURN_KEYWORD", "JS:THROW_KEYWORD", "JS:IF_KEYWORD", "JS:FOR_KEYWORD", "JS:WHILE_KEYWORD",
            "JS:DO_KEYWORD", "JS:TRY_KEYWORD", "JS:SWITCH_KEYWORD", "JS:BREAK_KEYWORD", "JS:CONTINUE_KEYWORD",
            "JS:IMPORT_KEYWORD", "JS:EXPORT_KEYWORD",
        )

        private val scan = ParenthesesScan(
            lpar = "JS:LPAR",
            rpar = "JS:RPAR",
            lbracket = "JS:LBRACKET",
            opens = setOf("JS:LPAR", "JS:LBRACKET", "JS:LBRACE", "JS:DOLLAR_LBRACE"),
            closes = setOf("JS:RPAR", "JS:RBRACKET", "JS:RBRACE"),
            excludedTopLevel = setOf("JS:COMMA", "JS:IN_KEYWORD"),
            excludedAnywhere = emptySet(),
            statementEnds = setOf("JS:SEMICOLON", "JS:RBRACE"),
            startsStatement = { it.type in STARTS_STATEMENT },
            allowedAfter = { before ->
                when (before) {
                    "JS:RETURN_KEYWORD", "JS:THROW_KEYWORD" -> setOf(STATEMENT_END)
                    in ASSIGNMENTS -> setOf(STATEMENT_END, "JS:COMMA", "JS:RPAR")
                    "JS:LPAR", "JS:COMMA" -> setOf("JS:COMMA", "JS:RPAR")
                    else -> null
                }
            },
            needsArgumentList = { before -> before == "JS:COMMA" },
            sameLineAfter = setOf("JS:RETURN_KEYWORD", "JS:THROW_KEYWORD"),
            layout = ::isLayout,
        )

        override fun rewrite(tokens: List<Token>): List<Token> = scan.rewrite(tokens)
    }
}
