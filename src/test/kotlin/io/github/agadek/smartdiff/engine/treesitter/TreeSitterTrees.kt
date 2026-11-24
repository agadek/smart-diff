package io.github.agadek.smartdiff.engine.treesitter

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Builds declaration trees with the helper the build made, for the per-language tree tests (engines/05). */
internal class TreeSitterTrees(private val language: TreeSitterLanguage) : AutoCloseable {
    private val helper = TreeSitterHelper(Path.of(System.getProperty(TreeSitterHelpers.HELPER_PROPERTY)))

    fun tree(text: String): DeclarationTree {
        val syntax = TreeSitterSyntax(helper.parse(language.grammar, text, Cancellation.NONE), helper.names(language.grammar), text)
        return TreeSitterTreeBuilder.build(syntax, language, Cancellation.NONE)
    }

    override fun close() = helper.close()

    companion object {
        /** Each declaration as `KIND name(signature)`, indented by depth. */
        fun DeclarationTree.outline(): List<String> = buildList {
            fun visit(declaration: Declaration, depth: Int) {
                add("  ".repeat(depth) + "${declaration.kind} ${declaration.name}" + (declaration.signature?.let { "($it)" } ?: ""))
                declaration.children.forEach { visit(it, depth + 1) }
            }
            topLevel.forEach { visit(it, 0) }
        }

        fun DeclarationTree.all(): List<Declaration> = buildList {
            fun visit(declaration: Declaration) {
                add(declaration)
                declaration.children.forEach(::visit)
            }
            topLevel.forEach(::visit)
        }

        /** engines/02 R1: every character is in exactly one declaration's own source or the file region. */
        fun assertCoveredOnce(tree: DeclarationTree, text: String) {
            val covered = BooleanArray(text.length)
            fun mark(range: TextRange) {
                for (i in range.start until range.end) {
                    assertFalse("offset $i is covered twice", covered[i])
                    covered[i] = true
                }
            }
            fun visit(declaration: Declaration) {
                val start = declaration.leadingWhitespace?.start ?: declaration.range.start
                var at = start
                for (child in declaration.children) {
                    mark(TextRange(at, child.leadingWhitespace?.start ?: child.range.start))
                    at = child.range.end
                }
                mark(TextRange(at, declaration.range.end))
                declaration.children.forEach(::visit)
            }
            tree.topLevel.forEach(::visit)
            tree.fileRegion.ranges.forEach(::mark)
            assertTrue("every offset is covered: ${covered.indices.filterNot { covered[it] }}", covered.all { it })
        }

        /** The content tokens of [declaration]'s own source, which a formatting-only change keeps. */
        fun contentTokens(declaration: Declaration): List<String> =
            declaration.ownTokens.filter { it.kind != TokenKind.WHITESPACE }.map { it.text }
    }
}
