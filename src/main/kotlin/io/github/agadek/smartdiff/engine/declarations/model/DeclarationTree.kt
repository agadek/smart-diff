package io.github.agadek.smartdiff.engine.declarations.model

import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy

/**
 * One side of a file as the declaration engine sees it (technical specification §6.1), built by a language provider.
 *
 * Declarations compare by identity: two equal-looking declarations on one side are still two declarations.
 */
class DeclarationTree(
    val topLevel: List<Declaration>,
    /** Everything not covered by a top-level declaration. */
    val fileRegion: FileRegion,
    val whitespacePolicy: WhitespacePolicy,
) {
    /** Every declaration, containers before their children, in source order. */
    val all: List<Declaration> by lazy {
        buildList {
            fun visit(declaration: Declaration) {
                add(declaration)
                declaration.children.forEach(::visit)
            }
            topLevel.forEach(::visit)
        }
    }
}

class Declaration(
    val kind: DeclarationKind,
    /** As written; `""` for an initializer (`"static"` for a static Java initializer). */
    val name: String,
    /** Normalized parameter types (§6.6); null for declarations that take no parameters. */
    val signature: String?,
    /** The whole declaration, including its doc comment and annotations. */
    val range: TextRange,
    /** Nested declarations, in source order. */
    val children: List<Declaration> = emptyList(),
    /**
     * Tokens in [range] minus the children's, preceded by the tokens of [leadingWhitespace]. The children's leading
     * whitespace is theirs, not this declaration's.
     */
    val ownTokens: List<Token> = emptyList(),
    /** A parse error lies in [range] but outside the children. */
    val hasParseError: Boolean = false,
    /**
     * The whitespace right before [range] that belongs to this declaration, such as the blank line before a method;
     * null when there is none. Adding, removing or moving a declaration takes its leading whitespace with it, so its
     * container does not change.
     */
    val leadingWhitespace: TextRange? = null,
    /** The parameter types for labels, with a space kept where the source separates words (§6.6). */
    val displaySignature: String? = signature,
    /** For labels: the receiver type of a Kotlin extension, which [signature] starts with; null when there is none. */
    val receiver: String? = null,
    /**
     * The start offset of each of [ownTokens], so a run of tokens maps back to the source; empty when the provider has
     * no offsets, and then the declaration takes no part in extraction detection.
     */
    val ownTokenStarts: List<Int> = emptyList(),
    /**
     * For a method, function or constructor, the code of its body: inside the braces of a block body, or the whole
     * expression of an expression body. Null without a body, such as an abstract method.
     */
    val body: TextRange? = null,
    /**
     * For a declaration whose name is written as one or more tokens, such as a quoted JSON key, the range of those
     * tokens, so matching can mask the name even when the token text differs from [name]. Null when the name is not a
     * token of the declaration, or the provider does not say.
     */
    val nameRange: TextRange? = null,
    /**
     * Source after [range] that belongs to the declaration for coverage but is not compared: the separator and line
     * break after a structure-view item (languages/06). Null for a dedicated provider, whose source ends at [range].
     */
    val layout: TextRange? = null,
) {
    override fun toString(): String = "$kind $name" + (signature?.let { "($it)" } ?: "") + " $range"
}

/** Source outside all top-level declarations: package line, imports, top-level statements. */
class FileRegion(
    val ranges: List<TextRange>,
    val tokens: List<Token>,
    val hasParseError: Boolean = false,
)

/** A lexer token; [type] is the lexer's token type name, compared together with [text]. */
data class Token(val type: String, val text: String, val kind: TokenKind)

enum class TokenKind {
    WHITESPACE,
    COMMENT,
    OTHER,

    /**
     * A separator between declarations, such as a comma between JSON keys or array items. It is source, so it covers
     * text for the nothing-hidden check, but it never makes a declaration or its container changed by itself.
     */
    SEPARATOR,
}
