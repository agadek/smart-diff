package io.github.agadek.smartdiff.engine.declarations.model

import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange

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

enum class TokenKind { WHITESPACE, COMMENT, OTHER }

/** Which whitespace a language treats as insignificant (§8 step 4). */
enum class WhitespacePolicy {
    /** Java: whitespace never matters. */
    INSIGNIFICANT,

    /** Kotlin: only whether there is a line break between two tokens matters. */
    LINE_BREAKS_SIGNIFICANT,

    /**
     * Scala 3: whether there is a line break matters, and so does the indentation after the last one, compared as
     * written; spaces within a line do not.
     */
    INDENTATION_SIGNIFICANT,

    /** Python, YAML: whitespace always matters, so nothing is formatting-only. */
    SIGNIFICANT,
}
