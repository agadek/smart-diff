package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.FileRegion
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy

/**
 * Builds declaration trees by hand for the analysis tests, without a parser.
 *
 * Each declaration's own source is a short text split into tokens by [tokens]. Ranges are assigned in source order, so
 * they nest and never overlap, but they are not offsets into any real text.
 */
fun tree(
    policy: WhitespacePolicy = WhitespacePolicy.INSIGNIFICANT,
    fileRegion: String = "",
    fileRegionParseError: Boolean = false,
    block: Scope.() -> Unit = {},
): DeclarationTree {
    val offsets = Offsets()
    val regionStart = offsets.take(fileRegion.length)
    val scope = Scope(offsets).apply(block)
    val ranges = if (fileRegion.isEmpty()) emptyList() else listOf(TextRange(regionStart, regionStart + fileRegion.length))
    return DeclarationTree(scope.declarations, FileRegion(ranges, tokens(fileRegion), fileRegionParseError), policy)
}

class Offsets {
    private var next = 0

    fun take(length: Int): Int = next.also { next += length }
}

class Scope(private val offsets: Offsets) {
    val declarations = mutableListOf<Declaration>()

    fun declaration(
        kind: DeclarationKind,
        name: String,
        signature: String? = null,
        body: String = "",
        parseError: Boolean = false,
        block: Scope.() -> Unit = {},
    ): Declaration {
        val start = offsets.take(body.length + 1)
        val children = Scope(offsets).apply(block).declarations
        val declaration = Declaration(kind, name, signature, TextRange(start, offsets.take(0)), children, tokens(body), parseError)
        declarations += declaration
        return declaration
    }

    fun cls(name: String, body: String = "{}", parseError: Boolean = false, block: Scope.() -> Unit = {}) =
        declaration(DeclarationKind.CLASS, name, body = body, parseError = parseError, block = block)

    fun method(name: String, signature: String = "", body: String = "{}", parseError: Boolean = false) =
        declaration(DeclarationKind.METHOD, name, signature, body, parseError)

    fun field(name: String, body: String = "int;") = declaration(DeclarationKind.FIELD, name, body = body)

    fun initializer(body: String = "{}") = declaration(DeclarationKind.INITIALIZER, "static", body = body)
}

private val TOKEN = Regex("""\s+|//[^\n]*|/\*.*?\*/|"[^"]*"|\w+|.""", RegexOption.DOT_MATCHES_ALL)

/** Splits [text] into whitespace, comment, string, word and single-character tokens. */
fun tokens(text: String): List<Token> = TOKEN.findAll(text).map { match ->
    val value = match.value
    when {
        value.isBlank() -> Token("WHITE_SPACE", value, TokenKind.WHITESPACE)
        value.startsWith("//") || value.startsWith("/*") -> Token("COMMENT", value, TokenKind.COMMENT)
        value.startsWith("\"") -> Token("STRING", value, TokenKind.OTHER)
        value[0].isLetterOrDigit() || value[0] == '_' -> Token("WORD", value, TokenKind.OTHER)
        else -> Token("SYMBOL", value, TokenKind.OTHER)
    }
}.toList()
