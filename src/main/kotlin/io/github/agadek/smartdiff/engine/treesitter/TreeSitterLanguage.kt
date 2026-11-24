package io.github.agadek.smartdiff.engine.treesitter

import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.LanguageIds
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy

/** One declaration a language table found at a node (engines/05-tree-sitter-details §5). */
internal class Found(
    val kind: DeclarationKind,
    val name: String,
    /** Normalized parameter list; null for a declaration that takes no parameters. */
    val signature: String?,
    /** The declaration's own range; null for the node's whole range. */
    val range: TextRange? = null,
    /** The node whose descendants hold the declaration's members, such as a class body; null without members. */
    val members: Int? = null,
    /** The code of a function's body; null without one. */
    val body: TextRange? = null,
    /** Whether [members] are a type's members, such as methods, or a namespace's, such as functions. */
    val membersInType: Boolean = true,
    /**
     * Whether the declarations after the node, up to its container's end, are its members, as in a C# file-scoped
     * namespace; the declaration's range then runs to the last of them.
     */
    val ownsFollowingSiblings: Boolean = false,
)

/** What one Tree-sitter language adds to the tree builder: its grammar, whitespace rule and declaration table. */
internal interface TreeSitterLanguage {
    val id: String
    val grammar: TreeSitterGrammar
    val whitespacePolicy: WhitespacePolicy

    /** Symbol names of comment leaves. */
    val comments: Set<String>

    /** Symbol names of string literal nodes, each one token. */
    val strings: Set<String>

    /**
     * The declarations at [node], or null when it is not a declaration, so the builder looks for declarations among
     * its children. [inType] is true inside a type's members.
     */
    fun describe(node: Int, syntax: TreeSitterSyntax, inType: Boolean): List<Found>?

    /** Whether nothing under a node of [type] is a declaration, such as a function body (R13). */
    fun isOpaque(type: String): Boolean

    /** Whether the comment [text] is a doc comment that belongs to the declaration below it (R15). */
    fun isDocComment(text: String): Boolean

    /** The end of the declaration at [node], such as after the `;` that follows a C struct; the node's end by default. */
    fun declarationEnd(node: Int, syntax: TreeSitterSyntax): Int = syntax.end(node)

    /** Offsets of the line breaks that are content, not whitespace, such as those ending a C directive (R18). */
    fun significantLineBreaks(syntax: TreeSitterSyntax): Set<Int> = emptySet()

    /** Symbol names of leaves whose text is split into words and whitespace, such as a C macro's body (§7). */
    val splitLeaves: Set<String> get() = emptySet()
}

/** Swift, with `tree-sitter-swift` (engines/05 R8, R16, R19). */
internal object SwiftLanguage : TreeSitterLanguage {
    override val id = LanguageIds.TREE_SITTER_SWIFT
    override val grammar = TreeSitterGrammar.SWIFT
    override val whitespacePolicy = WhitespacePolicy.KOTLIN_LINE_BREAKS
    override val comments = setOf("comment", "multiline_comment")
    override val strings = setOf("line_string_literal", "multi_line_string_literal", "raw_string_literal", "regex_literal")

    private val OPAQUE = setOf(
        "function_body", "computed_property", "lambda_literal", "statements", "protocol_property_requirements",
        "willset_didset_block", "computed_getter", "computed_setter", "computed_modify",
    )

    override fun isOpaque(type: String) = type in OPAQUE

    override fun isDocComment(text: String) = text.startsWith("///") || (text.startsWith("/**") && !text.startsWith("/**/"))

    override fun describe(node: Int, syntax: TreeSitterSyntax, inType: Boolean): List<Found>? = with(syntax) {
        when (type(node)) {
            "class_declaration" -> {
                val keyword = field(node, "declaration_kind")?.let(::text) ?: return null
                val kind = when (keyword) {
                    "class" -> DeclarationKind.CLASS
                    "struct" -> DeclarationKind.STRUCT
                    "enum" -> DeclarationKind.ENUM
                    "actor" -> DeclarationKind.ACTOR
                    "extension" -> DeclarationKind.EXTENSION
                    else -> return null
                }
                val name = field(node, "name")?.let { normalized(start(it), end(it)) } ?: return null
                listOf(Found(kind, name, null, members = field(node, "body")))
            }
            "protocol_declaration" -> {
                val name = field(node, "name")?.let(::text) ?: return null
                listOf(Found(DeclarationKind.PROTOCOL, name, null, members = field(node, "body")))
            }
            "function_declaration", "protocol_function_declaration" -> {
                // The grammar also gives the return type the `name` field; the function's own name comes first.
                val name = field(node, "name")?.let(::text) ?: return null
                val kind = if (inType) DeclarationKind.METHOD else DeclarationKind.FUNCTION
                listOf(Found(kind, name, parameters(node), body = body(node)))
            }
            "init_declaration" -> {
                val mark = children(node).firstOrNull { !isNamed(it) && text(it) in setOf("?", "!") }?.let(::text).orEmpty()
                listOf(Found(DeclarationKind.CONSTRUCTOR, "init$mark", parameters(node), body = body(node)))
            }
            "deinit_declaration" -> listOf(Found(DeclarationKind.DESTRUCTOR, "deinit", null, body = body(node)))
            "subscript_declaration" -> listOf(Found(DeclarationKind.SUBSCRIPT, "subscript", parameters(node)))
            "property_declaration", "protocol_property_declaration" ->
                split(node, syntax) { pattern -> bound(pattern, syntax) }.map { (name, range) ->
                    Found(DeclarationKind.PROPERTY, name, null, range = range)
                }.ifEmpty { null }
            "enum_entry" ->
                split(node, syntax) { text(it) }.map { (name, range) -> Found(DeclarationKind.ENUM_CONSTANT, name, null, range = range) }
                    .ifEmpty { null }
            "typealias_declaration" -> {
                val name = field(node, "name")?.let(::text) ?: return null
                listOf(Found(DeclarationKind.TYPE_ALIAS, name, null))
            }
            else -> null
        }
    }

    /** The name a property pattern binds: its bound identifier, or the last identifier in it. */
    private fun bound(pattern: Int, syntax: TreeSitterSyntax): String = with(syntax) {
        val identifier = fields(pattern, "bound_identifier").firstOrNull() ?: descendant(pattern, "simple_identifier")
        identifier?.let(::text) ?: text(pattern)
    }

    /**
     * One entry per `name` field of [node], as in `let a = 1, b = 2` or `case small, large`: the first runs from the
     * node's start to its comma, each next from its name to its comma or the node's end (R8). The commas belong to the
     * container.
     */
    private fun split(node: Int, syntax: TreeSitterSyntax, name: (Int) -> String): List<Pair<String, TextRange?>> = with(syntax) {
        val names = fields(node, "name")
        if (names.size <= 1) return names.map { name(it) to null }
        val children = children(node)
        names.mapIndexed { index, nameNode ->
            val from = if (index == 0) start(node) else start(nameNode)
            val next = names.getOrNull(index + 1)
            val to = if (next == null) {
                end(node)
            } else {
                val comma = children.last { start(it) < start(next) && !isNamed(it) && text(it) == "," }
                children.filter { end(it) <= start(comma) }.maxOf { end(it) }
            }
            name(nameNode) to TextRange(from, to)
        }
    }

    /** The parameter list as `label: Type, …`, with `_` for no label (R16); the outline adds the parentheses. */
    private fun TreeSitterSyntax.parameters(node: Int): String {
        val parameters = children(node).filter { type(it) == "parameter" }.map { parameter ->
            val label = field(parameter, "external_name")?.let(::text) ?: field(parameter, "name")?.let(::text) ?: "_"
            val parts = children(parameter)
            val colon = parts.indexOfFirst { !isNamed(it) && text(it) == ":" }
            val type = if (colon < 0) "" else parts.drop(colon + 1).filter { type(it) != "parameter_modifiers" }
                .joinToString(" ") { normalized(start(it), end(it)) }
            "$label: $type"
        }
        return parameters.joinToString(", ")
    }

    /** The inside of a function body's braces. */
    private fun TreeSitterSyntax.body(node: Int): TextRange? {
        val body = field(node, "body") ?: return null
        val parts = children(body)
        val open = parts.firstOrNull()?.takeIf { text(it) == "{" }
        val close = parts.lastOrNull()?.takeIf { text(it) == "}" }
        return if (open != null && close != null && open != close) TextRange(end(open), start(close)) else TextRange(start(body), end(body))
    }
}
