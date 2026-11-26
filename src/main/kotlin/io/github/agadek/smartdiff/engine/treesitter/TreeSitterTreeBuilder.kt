package io.github.agadek.smartdiff.engine.treesitter

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.FileRegion

/**
 * Turns one side's syntax tree into the declaration engine's [DeclarationTree] (engines/05-tree-sitter-details §6), the
 * way the PSI providers do: declaration ranges from the language table, own tokens sliced from the side's tokens, the
 * whitespace before each declaration as its own, and parse errors given to the innermost declaration that holds them.
 */
internal object TreeSitterTreeBuilder {
    private const val CHECK_EVERY = 1000

    fun build(syntax: TreeSitterSyntax, language: TreeSitterLanguage, cancellation: Cancellation): DeclarationTree {
        val tokens = TreeSitterTokens.of(
            syntax.tree, syntax.names, syntax.text, language.comments, language.strings,
            language.significantLineBreaks(syntax), language.splitLeaves, syntax.recovery?.literals.orEmpty(),
        )
        var visited = 0

        fun find(nodes: List<Int>, inType: Boolean): List<Node> = buildList {
            for ((index, child) in nodes.withIndex()) {
                if (++visited % CHECK_EVERY == 0) cancellation.checkCanceled()
                if (language.isOpaque(syntax.type(child))) continue
                val found = language.describe(child, syntax, inType)
                if (found == null) {
                    addAll(find(syntax.children(child), inType))
                    continue
                }
                for (declaration in found) {
                    if (declaration.ownsFollowingSiblings) {
                        // A C# file-scoped namespace holds every declaration after it.
                        val rest = nodes.drop(index + 1)
                        val members = find(rest, declaration.membersInType)
                        val end = maxOf(syntax.end(child), rest.maxOfOrNull { syntax.end(it) } ?: 0)
                        add(Node(declaration, TextRange(withDocComment(child, syntax, language), end), members))
                        return@buildList
                    }
                    val range = declaration.range?.let { own ->
                        if (own.start == syntax.start(child)) TextRange(withDocComment(child, syntax, language), own.end) else own
                    } ?: TextRange(withDocComment(child, syntax, language), language.declarationEnd(child, syntax))
                    val members = declaration.members?.let { find(syntax.children(it), declaration.membersInType) }.orEmpty()
                    add(Node(declaration, range, members))
                }
            }
        }
        val topLevel = find(syntax.children(syntax.root), inType = false)
        val fileRegionError = markParseErrors(syntax, topLevel)
        assignLeading(topLevel, tokens, containerStart = 0)

        fun declaration(node: Node): Declaration {
            val excluded = node.children.map { it.extent }
            val leading = node.leading?.let { tokens.own(it, emptyList()) }.orEmpty()
            val leadingStarts = node.leading?.let { tokens.ownStarts(it, emptyList()) }.orEmpty()
            return Declaration(
                kind = node.found.kind,
                name = node.found.name,
                signature = node.found.signature,
                range = node.range,
                children = node.children.map(::declaration),
                ownTokens = leading + tokens.own(node.range, excluded),
                hasParseError = node.parseError,
                leadingWhitespace = node.leading,
                ownTokenStarts = leadingStarts + tokens.ownStarts(node.range, excluded),
                body = node.found.body,
            )
        }
        val whole = TextRange(0, syntax.text.length)
        val covered = topLevel.map { it.extent }
        val fileRegion = FileRegion(gaps(whole, covered), tokens.own(whole, covered), fileRegionError)
        return DeclarationTree(topLevel.map(::declaration), fileRegion, language.whitespacePolicy)
    }

    /**
     * The start of [node] widened over the doc comments directly above it, with no blank line between (R15), and over the
     * attribute macros that macro recovery read as blank space directly before it (engines/06 R6). Its attributes are
     * already inside the node.
     */
    private fun withDocComment(node: Int, syntax: TreeSitterSyntax, language: TreeSitterLanguage): Int {
        val parent = syntax.tree.parent(node).takeIf { it >= 0 } ?: return syntax.start(node)
        val siblings = syntax.children(parent)
        fun adjacent(from: Int, to: Int): Boolean {
            val gap = syntax.text.substring(from, to)
            return gap.isBlank() && gap.count { it == '\n' } <= 1
        }
        var start = syntax.start(node)
        var index = siblings.indexOf(node) - 1
        while (true) {
            // The root starts at its first leaf, after any masked text, so a top-level declaration looks back to 0.
            val previousEnd = when {
                index >= 0 -> syntax.end(siblings[index])
                parent == syntax.root -> 0
                else -> syntax.start(parent)
            }
            val prefix = syntax.recovery?.prefixBefore(start)?.takeIf { it.start >= previousEnd }
            if (prefix != null && adjacent(prefix.end, start)) {
                start = prefix.start
                continue
            }
            if (index < 0) break
            val previous = siblings[index]
            val isDoc = syntax.type(previous) in language.comments && language.isDocComment(syntax.text(previous))
            if (!isDoc || !adjacent(syntax.end(previous), start)) break
            start = syntax.start(previous)
            index--
        }
        return start
    }

    /** Each declaration takes the whitespace between its previous sibling, or its container's start, and itself. */
    private fun assignLeading(nodes: List<Node>, tokens: TreeSitterTokens, containerStart: Int) {
        var previousEnd = containerStart
        for (node in nodes) {
            val start = tokens.whitespaceStart(node.range.start, notBefore = previousEnd)
            if (start < node.range.start) node.leading = TextRange(start, node.range.start)
            assignLeading(node.children, tokens, node.range.start)
            previousEnd = node.range.end
        }
    }

    /** Marks the innermost declaration holding each `ERROR` or `MISSING` node; true when one lies outside all (R20). */
    private fun markParseErrors(syntax: TreeSitterSyntax, roots: List<Node>): Boolean {
        var fileRegionError = false
        for (node in 0 until syntax.tree.size) {
            if (!syntax.tree.isError(node) && !syntax.tree.isMissing(node)) continue
            val owner = innermost(roots, syntax.start(node))
            if (owner == null) fileRegionError = true else owner.parseError = true
        }
        return fileRegionError
    }

    private fun innermost(nodes: List<Node>, offset: Int): Node? {
        for (node in nodes) {
            if (offset < node.range.start || offset > node.range.end) continue
            return innermost(node.children, offset) ?: node
        }
        return null
    }

    private fun gaps(range: TextRange, excluded: List<TextRange>): List<TextRange> {
        val gaps = mutableListOf<TextRange>()
        var start = range.start
        for (part in excluded) {
            if (part.start > start) gaps += TextRange(start, part.start)
            start = maxOf(start, part.end)
        }
        if (range.end > start) gaps += TextRange(start, range.end)
        return gaps
    }

    private class Node(val found: Found, val range: TextRange, val children: List<Node>) {
        var leading: TextRange? = null
        var parseError = false
        val extent: TextRange get() = leading?.let { TextRange(it.start, range.end) } ?: range
    }
}
