package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree

/** Builds declaration trees with a registered provider, the way the declaration engine does. */
abstract class ProviderTestCase : BasePlatformTestCase() {

    /** The provider registered for [languageId]; finding it checks that its descriptor registers it. */
    protected fun provider(languageId: String): DeclarationProvider =
        DeclarationProvider.EP_NAME.extensionList.singleOrNull { it.supports(languageId) }
            ?: error("expected exactly one provider for $languageId, found ${DeclarationProvider.EP_NAME.extensionList}")

    protected fun build(languageId: String, text: String, fileName: String? = null): DeclarationTree {
        val provider = provider(languageId)
        val tree = ReadAction.compute(ThrowableComputable<DeclarationTree, RuntimeException> {
            provider.build(provider.parse(ProjectManager.getInstance().defaultProject, text, fileName), Cancellation.NONE)
        })
        assertSlicedExactly(tree, text)
        return tree
    }

    /**
     * The tokens tile the text: each declaration's own tokens are its source minus its children's, in order, and the
     * file region's tokens are the source outside all top-level declarations. Children are sorted, disjoint and inside
     * their container.
     */
    private fun assertSlicedExactly(tree: DeclarationTree, text: String) {
        fun outside(range: io.github.agadek.smartdiff.engine.api.TextRange, children: List<io.github.agadek.smartdiff.engine.api.TextRange>): String {
            var start = range.start
            val parts = StringBuilder()
            for (child in children) {
                assertTrue("children of $range are sorted, disjoint and inside it: $children", child.start >= start && child.end <= range.end)
                parts.append(text, start, child.start)
                start = child.end
            }
            return parts.append(text, start, range.end).toString()
        }
        fun extent(declaration: Declaration) =
            io.github.agadek.smartdiff.engine.api.TextRange(declaration.leadingWhitespace?.start ?: declaration.range.start, declaration.range.end)
        fun check(declaration: Declaration) {
            declaration.leadingWhitespace?.let { leading ->
                assertEquals("leading whitespace of $declaration ends at its start", declaration.range.start, leading.end)
                assertTrue("leading whitespace of $declaration is blank", text.substring(leading.start, leading.end).isBlank())
            }
            val leading = declaration.leadingWhitespace?.let { text.substring(it.start, it.end) }.orEmpty()
            assertEquals("own tokens of $declaration", leading + outside(declaration.range, declaration.children.map(::extent)), declaration.ownTokens.joinToString("") { it.text })
            declaration.children.forEach(::check)
        }
        tree.topLevel.forEach(::check)
        val whole = io.github.agadek.smartdiff.engine.api.TextRange(0, text.length)
        assertEquals("file region tokens", outside(whole, tree.topLevel.map(::extent)), tree.fileRegion.tokens.joinToString("") { it.text })
        assertEquals("file region ranges", outside(whole, tree.topLevel.map(::extent)), tree.fileRegion.ranges.joinToString("") { text.substring(it.start, it.end) })
    }

    protected fun assertTree(expected: String, tree: DeclarationTree, text: String) {
        assertEquals(expected.trimIndent() + "\n", Rendering.tree(tree, text))
    }
}
