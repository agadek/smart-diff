package io.github.agadek.smartdiff.ui

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lines whose native diff marks the SMART Diff viewer fades (technical specification §10), without the IDE. */
class HiddenLinesTest {

    // Ten lines of ten characters each: line n spans offsets [10n, 10n + 10).
    private val text = (0 until 10).joinToString("") { "line $it   \n".take(9) + "\n" }

    @Test
    fun `a native change is hidden only when every line on both sides belongs to hidden changes`() {
        val result = result(
            node("a", lines(1, 2), lines(1, 2), content = Content.FORMATTING_ONLY),
            node("b", lines(4, 4), lines(4, 4), content = Content.CHANGED),
            node("c", null, lines(6, 7), presence = Presence.ADDED),
        )

        val hidden = HiddenLines.of(result, setOf(ChangeType.FORMATTING_ONLY, ChangeType.ADDED), text, text)

        assertTrue(hidden.covers(1, 3, 1, 3))
        assertFalse("a shown change", hidden.covers(4, 5, 4, 5))
        assertFalse("a change that also spans a shown line", hidden.covers(1, 5, 1, 5))
        assertTrue("the empty old side of an insertion does not count", hidden.covers(6, 6, 6, 8))
        assertFalse("lines that belong to no change", hidden.covers(9, 10, 9, 10))
    }

    @Test
    fun `a hidden container keeps the lines of its shown children`() {
        val result = result(
            node(
                "A", lines(0, 8), lines(0, 8), content = Content.FORMATTING_ONLY,
                children = listOf(node("b", lines(3, 4), lines(3, 4), content = Content.CHANGED)),
            ),
        )

        val hidden = HiddenLines.of(result, setOf(ChangeType.FORMATTING_ONLY), text, text)

        assertTrue(hidden.covers(0, 3, 0, 3))
        assertFalse(hidden.covers(3, 5, 3, 5))
        assertTrue(hidden.covers(5, 9, 5, 9))
    }

    @Test
    fun `unanalyzed and uncertain lines are never hidden, and an empty filter hides nothing`() {
        val result = ComparisonResult(
            EngineId("e"), "1", Status.PARTIAL,
            changes = listOf(
                node("a", lines(0, 3), lines(0, 3), content = Content.FORMATTING_ONLY),
                node("u", null, lines(2, 2), presence = Presence.UNCERTAIN),
            ),
            unanalyzed = listOf(RangePair(lines(1, 1), null)),
        )

        val hidden = HiddenLines.of(result, ChangeType.entries.toSet(), text, text)

        assertTrue(hidden.covers(0, 1, 0, 1))
        assertFalse(hidden.covers(1, 2, 0, 0))
        assertFalse(hidden.covers(0, 0, 2, 3))
        assertTrue(hidden.covers(3, 4, 3, 4))
        assertEquals(HiddenLines.NONE, HiddenLines.of(result, emptySet(), text, text))
    }

    @Test
    fun `leading whitespace does not take the line before it`() {
        // Method b on line 4, with the blank line 3 and the line break that ends line 2 as its leading whitespace.
        val b = node("b", lines(4, 4), lines(4, 4), content = Content.FORMATTING_ONLY)
            .copy(details = listOf(RangePair(TextRange(29, 40), TextRange(29, 40))))

        val hidden = HiddenLines.of(result(b), setOf(ChangeType.FORMATTING_ONLY), text, text)

        assertTrue(hidden.covers(3, 5, 3, 5))
        assertFalse(hidden.covers(2, 3, 2, 3))
    }

    /** Lines [first, last] as an offset range ending before the last line's line break. */
    private fun lines(first: Int, last: Int) = TextRange(first * 10, last * 10 + 9)

    private fun result(vararg changes: ChangeNode) = ComparisonResult(EngineId("e"), "1", Status.ANALYZED, changes = changes.toList())

    private fun node(
        name: String,
        old: TextRange?,
        new: TextRange?,
        presence: Presence = Presence.MATCHED,
        content: Content = Content.UNCHANGED,
        children: List<ChangeNode> = emptyList(),
    ): ChangeNode {
        val kind = if (name.first().isUpperCase()) DeclarationKind.CLASS else DeclarationKind.METHOD
        return ChangeNode(Subject.Declaration(kind, name, null), old, new, presence, content = content, children = children)
    }
}
