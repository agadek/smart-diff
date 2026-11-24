package io.github.agadek.smartdiff.engine.treesitter

import io.github.agadek.smartdiff.engine.api.TextRange

/**
 * Helpers the language tables share (engines/05-tree-sitter-details §5): splitting a multi-name declaration, the
 * inside of a body's braces and the normalized text of a type.
 */
internal object TreeSitterTables {
    private val SPACE_BEFORE = Regex("\\s+([*&<>()\\[\\],:.?])")
    private val SPACE_AFTER = Regex("([<(\\[:.,])\\s+")

    /**
     * One range per entry of [names], as in `int a = 1, b;`: the first runs from [node]'s start to the end of its part
     * before the comma, each next from its name to its comma or [node]'s end. The commas are children of [commaParent]
     * and belong to the container. A single name gives `null`, the node's whole range.
     */
    fun split(syntax: TreeSitterSyntax, node: Int, names: List<Int>, commaParent: Int = node): List<TextRange?> = with(syntax) {
        if (names.size <= 1) return names.map { null }
        val children = children(commaParent)
        names.mapIndexed { index, name ->
            val from = if (index == 0) start(node) else start(name)
            val next = names.getOrNull(index + 1)
            val to = if (next == null) {
                end(node)
            } else {
                val comma = children.lastOrNull { start(it) < start(next) && !isNamed(it) && text(it) == "," }
                if (comma == null) end(name) else children.filter { end(it) <= start(comma) }.maxOf { end(it) }
            }
            TextRange(from, to)
        }
    }

    /** The inside of [body]'s braces, or its whole range when it has none, such as an expression body. */
    fun braced(syntax: TreeSitterSyntax, body: Int): TextRange = with(syntax) {
        val parts = children(body)
        val open = parts.firstOrNull()?.takeIf { text(it) == "{" }
        val close = parts.lastOrNull()?.takeIf { text(it) == "}" }
        if (open != null && close != null && open != close) TextRange(end(open), start(close)) else TextRange(start(body), end(body))
    }

    /**
     * The leaves of [node] other than those under [excluded], joined so that the same type written with other spacing
     * gives the same text: `const std::vector<int> &` and `const std :: vector<int>&` are both `const std::vector<int>&`.
     */
    fun typeText(syntax: TreeSitterSyntax, node: Int, excluded: Set<Int> = emptySet()): String {
        val words = mutableListOf<String>()
        fun visit(at: Int) {
            if (at in excluded || syntax.type(at) == "comment") return
            val children = syntax.children(at)
            if (children.isEmpty()) words += syntax.text(at).trim() else children.forEach(::visit)
        }
        visit(node)
        return compact(words.filter { it.isNotEmpty() }.joinToString(" "))
    }

    /** [text] with whitespace runs as one space and none around punctuation (see [typeText]). */
    fun compact(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").replace(SPACE_BEFORE, "$1").replace(SPACE_AFTER, "$1").replace(",", ", ")
}
