package io.github.agadek.smartdiff.engine.treesitter

/**
 * Navigation over one side's [TreeSitterTree]: children, fields and node text by name, for the language tables
 * (engines/05-tree-sitter-details §5). Node indices are those of the tree. [text] is always the side's own text, also
 * when the tree was parsed from a [MacroRecovery]'s text, so tokens and names read the source as written (engines/06 R5).
 */
internal class TreeSitterSyntax(
    val tree: TreeSitterTree,
    val names: TreeSitterNames,
    val text: CharSequence,
    /** The macro recovery the tree was parsed with (engines/06); the tree's offsets are [text]'s either way. */
    val recovery: MacroRecovery? = null,
) {
    private val firstChild = IntArray(tree.size) { -1 }
    private val nextSibling = IntArray(tree.size) { -1 }

    init {
        val last = IntArray(tree.size) { -1 }
        for (node in 1 until tree.size) {
            val parent = tree.parent(node)
            if (last[parent] == -1) firstChild[parent] = node else nextSibling[last[parent]] = node
            last[parent] = node
        }
    }

    val root: Int get() = 0

    /** The grammar's name of [node]'s symbol: its type for a named node, its text for an anonymous one. */
    fun type(node: Int): String = names.symbols.getOrElse(tree.symbol(node)) { "" }

    fun isNamed(node: Int): Boolean = tree.isNamed(node)

    /** The field the parent gives [node], or `""`. */
    fun field(node: Int): String = names.fields.getOrElse(tree.field(node)) { "" }

    fun start(node: Int): Int = tree.start(node)
    fun end(node: Int): Int = tree.end(node)
    fun text(node: Int): String = text.substring(tree.start(node), tree.end(node))

    fun children(node: Int): List<Int> = buildList {
        var child = firstChild[node]
        while (child != -1) {
            add(child)
            child = nextSibling[child]
        }
    }

    fun namedChildren(node: Int): List<Int> = children(node).filter(::isNamed)

    /** The children the parent gives the field [name], in order. */
    fun fields(node: Int, name: String): List<Int> = children(node).filter { field(it) == name }

    fun field(node: Int, name: String): Int? = fields(node, name).firstOrNull()

    /** The first child of [type], named or anonymous. */
    fun child(node: Int, type: String): Int? = children(node).firstOrNull { type(it) == type }

    /** The first descendant of [type], in pre-order, or null. */
    fun descendant(node: Int, type: String): Int? {
        for (child in children(node)) {
            if (type(child) == type) return child
            descendant(child, type)?.let { return it }
        }
        return null
    }

    /** [text] with every run of whitespace written as one space. */
    fun normalized(start: Int, end: Int): String = text.substring(start, end).trim().replace(WHITESPACE, " ")

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
