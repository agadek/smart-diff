package io.github.agadek.smartdiff.ui

import com.intellij.diff.fragments.DiffFragment
import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.fragments.LineFragmentImpl
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The word highlights the SMART Diff viewer takes from changed runs (declarations/10 R11 to R13), without the IDE. */
class ChangedRunsTest {

    // Ten lines of ten characters each: line n spans offsets [10n, 10n + 10).
    private val text = (0 until 10).joinToString("") { "line $it   \n".take(9) + "\n" }

    // declarations/10-fine-grained-differencing R11
    @Test
    fun `a native change inside a declaration with runs shows exactly the runs`() {
        val runs = ChangedRuns.of(
            result(node("a", lines(1, 3), lines(1, 3), runs = listOf(RangePair(TextRange(22, 24), TextRange(22, 25))))),
            text, text,
        )

        val fragment = runs.fragment(change(2, 3, 2, 3))!!

        assertEquals(listOf(listOf(2, 4, 2, 5)), fragment.innerFragments!!.map(::offsets))
        assertEquals(2, fragment.startLine1)
        assertEquals(3, fragment.endLine2)
    }

    // declarations/10-fine-grained-differencing R12
    @Test
    fun `a change with no run inside, or inside a formatting-only declaration, shows no word highlights`() {
        val runs = ChangedRuns.of(
            result(
                node("a", lines(1, 3), lines(1, 3), runs = listOf(RangePair(TextRange(12, 14), TextRange(12, 14)))),
                node("b", lines(5, 6), lines(5, 6), content = Content.FORMATTING_ONLY),
            ),
            text, text,
        )

        assertEquals(emptyList<DiffFragment>(), runs.fragment(change(3, 4, 3, 4))!!.innerFragments)
        assertEquals(emptyList<DiffFragment>(), runs.fragment(change(5, 7, 5, 7))!!.innerFragments)
        assertNull("a pure insertion keeps the native fragment and its colour", runs.fragment(change(6, 6, 5, 6)))
    }

    // declarations/10-fine-grained-differencing R13
    @Test
    fun `a change reaching a line outside the compared declarations keeps the native highlights`() {
        val runs = ChangedRuns.of(
            result(
                node("a", lines(1, 3), lines(1, 3), runs = listOf(RangePair(TextRange(12, 14), TextRange(12, 14)))),
                node("c", null, lines(4, 5), presence = Presence.ADDED),
                node("d", lines(7, 8), lines(7, 8), compared = false),
            ),
            text, text,
        )

        assertNull("into an added method", runs.fragment(change(3, 4, 3, 5)))
        assertNull("into a declaration over the limits", runs.fragment(change(7, 8, 7, 8)))
        assertNull("lines no declaration owns", runs.fragment(change(9, 10, 9, 10)))
    }

    // declarations/10-fine-grained-differencing R13: an unanalyzed child keeps the native highlights.
    @Test
    fun `a change across a changed child that was not compared keeps the native highlights`() {
        val child = node("b", lines(2, 2), lines(2, 2), content = Content.UNANALYZED, compared = false)
        val runs = ChangedRuns.of(result(node("A", lines(0, 4), lines(0, 4), children = listOf(child))), text, text)

        assertTrue(runs.fragment(change(1, 2, 1, 2)) != null)
        assertNull(runs.fragment(change(1, 3, 1, 3)))
    }

    // declarations/10-fine-grained-differencing R11
    @Test
    fun `a run only on one side gets an empty range on the other side next to it`() {
        val runs = ChangedRuns.of(
            result(node("a", lines(1, 3), lines(1, 3), runs = listOf(RangePair(null, TextRange(23, 25))))),
            text, text,
        )

        val inner = runs.fragment(change(2, 3, 2, 3))!!.innerFragments!!.single()

        assertEquals(listOf(3, 3, 3, 5), offsets(inner))
    }

    @Test
    fun `a fragment past the compared text keeps the native one, and no runs change nothing`() {
        val runs = ChangedRuns.of(result(node("a", lines(1, 3), lines(1, 3), runs = emptyList())), text, text)

        assertNull(runs.fragment(LineFragmentImpl(1, 2, 1, 2, 10, 500, 10, 20)))
        assertNull(ChangedRuns.NONE.fragment(change(1, 2, 1, 2)))
        assertSame(ChangedRuns.NONE, ChangedRuns.of(result(node("x", null, lines(1, 2), presence = Presence.ADDED)), text, text))
    }

    private fun offsets(fragment: DiffFragment) =
        listOf(fragment.startOffset1, fragment.endOffset1, fragment.startOffset2, fragment.endOffset2)

    private fun lines(first: Int, last: Int) = TextRange(first * 10, last * 10 + 9)

    /** A native change over lines `[s1, e1)` and `[s2, e2)`, with offsets from the start of the first line to the end of the last. */
    private fun change(s1: Int, e1: Int, s2: Int, e2: Int): LineFragment =
        LineFragmentImpl(s1, e1, s2, e2, s1 * 10, e1 * 10, s2 * 10, e2 * 10, listOf(com.intellij.diff.fragments.DiffFragmentImpl(0, 1, 0, 1)))

    private fun result(vararg changes: ChangeNode) = ComparisonResult(EngineId("e"), "1", Status.ANALYZED, changes = changes.toList())

    private fun node(
        name: String,
        old: TextRange?,
        new: TextRange?,
        presence: Presence = Presence.MATCHED,
        content: Content = Content.CHANGED,
        children: List<ChangeNode> = emptyList(),
        runs: List<RangePair> = emptyList(),
        compared: Boolean = presence == Presence.MATCHED,
    ): ChangeNode {
        val kind = if (name.first().isUpperCase()) DeclarationKind.CLASS else DeclarationKind.METHOD
        return ChangeNode(
            Subject.Declaration(kind, name, null), old, new, presence, content = content, children = children,
            changedRuns = runs, comparedByTokens = compared,
        )
    }
}
