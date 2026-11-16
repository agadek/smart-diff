package io.github.agadek.smartdiff.ui

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import io.github.agadek.smartdiff.engine.api.Candidate
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.RelationshipEndpoint
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumSet

/** The reviewed labels, rows, summary and filter, without the IDE (outline/12). */
class ReviewedOutlineModelTest {

    private val request = ComparisonRequest("old text here\n", "new text here\n", "JAVA")

    private fun node(
        name: String,
        old: TextRange? = TextRange(0, 3),
        new: TextRange? = TextRange(0, 3),
        presence: Presence = Presence.MATCHED,
        content: Content = Content.UNCHANGED,
        moved: Boolean = false,
        candidates: List<Candidate> = emptyList(),
        children: List<ChangeNode> = emptyList(),
        renamed: Subject? = null,
    ): ChangeNode {
        val kind = if (name.first().isUpperCase()) DeclarationKind.CLASS else DeclarationKind.METHOD
        return ChangeNode(
            Subject.Declaration(kind, name, if (kind == DeclarationKind.METHOD) "" else null),
            old, new, presence, moved = moved, content = content, candidates = candidates, children = children,
            renamedFrom = renamed,
        )
    }

    private fun result(vararg changes: ChangeNode, fileRegion: ChangeNode? = null) =
        ComparisonResult(EngineId("e"), "1", Status.ANALYZED, fileRegion = fileRegion, changes = changes.toList())

    private fun build(
        result: ComparisonResult,
        reviewed: Set<ChangeNode> = emptySet(),
        hidden: Set<ChangeType> = emptySet(),
        hideReviewed: Boolean = false,
    ) = OutlineModel.build(result, request.oldText, request.newText, false, hidden, WhitespacePolicy.INSIGNIFICANT, false, reviewed, hideReviewed)

    private fun flatten(items: List<OutlineItem>): List<OutlineItem> = items.flatMap { listOf(it) + flatten(it.children) }

    private fun fragments(item: OutlineItem): List<Pair<String, SimpleTextAttributes>> {
        val component = SimpleColoredComponent()
        appendItem(component, item)
        val out = mutableListOf<Pair<String, SimpleTextAttributes>>()
        val iterator = component.iterator()
        while (iterator.hasNext()) {
            iterator.next()
            out += iterator.fragment to iterator.textAttributes
        }
        return out
    }

    private fun summary(result: ComparisonResult, reviewed: Set<ChangeNode> = emptySet(), hideReviewed: Boolean = false) =
        OutlineModel.summary(result, emptySet(), request.oldText, request.newText, WhitespacePolicy.INSIGNIFICANT, false, reviewed, hideReviewed)

    @Test
    // outline/12-reviewed-marks R4, R8
    fun `outline 12 R4 R8 a reviewed row gains the label in its text and accessible name`() {
        val changed = node("change", content = Content.CHANGED)
        val result = result(changed)

        val plain = build(result).single()
        val reviewed = build(result, identitySetOf(changed)).single()

        assertEquals("method change()  changed", plain.label)
        assertEquals("method change()  changed, reviewed", reviewed.label)
        assertEquals(reviewed.label, reviewed.toString())
    }

    @Test
    // outline/12-reviewed-marks R4
    fun `outline 12 R4 the title and the reviewed label are greyed`() {
        val changed = node("change", content = Content.CHANGED)
        val item = build(result(changed), identitySetOf(changed)).single()
        val parts = fragments(item)

        assertEquals("method change()", parts.first().first)
        assertEquals(SimpleTextAttributes.GRAYED_ATTRIBUTES, parts.first().second)
        assertEquals(", reviewed" to SimpleTextAttributes.GRAYED_ATTRIBUTES, parts.last())
    }

    @Test
    // outline/12-reviewed-marks R5
    fun `outline 12 R5 a container shown only for its children is reviewed when all children are`() {
        val a = node("a", content = Content.CHANGED)
        val b = node("b", content = Content.CHANGED)
        val result = result(node("Container", children = listOf(a, b)))

        val all = build(result, identitySetOf(a, b))
        assertEquals("class Container  reviewed", all.single().label)
        assertTrue(all.single().reviewed)

        val partial = build(result, identitySetOf(a))
        assertFalse(partial.single().reviewed)
        assertEquals("class Container", partial.single().label)
    }

    @Test
    // outline/12-reviewed-marks R3
    fun `outline 12 R3 marking a parent or a group marks every change under it`() {
        val a = node("a", content = Content.FORMATTING_ONLY)
        val b = node("b", content = Content.FORMATTING_ONLY)
        val parent = node("Parent", children = listOf(a, b))
        val result = result(parent)
        val state = Reviewed(emptySet(), ReviewedMarks.markable(result, request.oldText, request.newText, WhitespacePolicy.INSIGNIFICANT), emptyMap(), emptyMap())
        val plain = build(result)
        val group = flatten(plain).single { it.kind == ItemKind.FORMATTING_GROUP }
        val parentNodes = state.nodesOf(plain.single())
        val groupNodes = state.nodesOf(group)
        assertEquals(2, parentNodes.size)
        assertTrue("marking the parent marks its children", parentNodes.containsAll(listOf(a, b)))
        assertEquals("the group stands for the same changes", parentNodes, groupNodes)

        val rows = build(result, parentNodes)
        assertTrue("the parent row is reviewed", rows.single().reviewed)
        val markedGroup = flatten(rows).single { it.kind == ItemKind.FORMATTING_GROUP }
        assertTrue("the formatting group is reviewed", markedGroup.reviewed)
        assertEquals("Formatting-only (2)  reviewed", markedGroup.label)
        assertTrue("marking the group alone reviews it", flatten(build(result, groupNodes)).single { it.kind == ItemKind.FORMATTING_GROUP }.reviewed)
    }

    @Test
    // outline/12-reviewed-marks R6
    fun `outline 12 R6 the summary counts hidden nodes and ends with N of M reviewed`() {
        val changed = node("changed", content = Content.CHANGED)
        val added = node("added", presence = Presence.ADDED, old = null, new = TextRange(4, 8))
        val result = result(changed, added)
        val hidden = setOf(ChangeType.ADDED)

        val withMark = OutlineModel.summary(result, hidden, request.oldText, request.newText, WhitespacePolicy.INSIGNIFICANT, false, identitySetOf(changed))
        assertEquals("1 changed · 1 added · 1 hidden · 1 of 2 reviewed", withMark)
        assertEquals("1 changed · 1 added · 1 hidden", OutlineModel.summary(result, hidden))
    }

    @Test
    // outline/12-reviewed-marks R7
    fun `outline 12 R7 with no marks the rows and summary are unchanged`() {
        val result = result(node("Parent", children = listOf(node("a", content = Content.CHANGED))))
        val before = build(result)
        val beforeSummary = summary(result)

        val after = build(result, emptySet())
        assertEquals(flatten(before).map { it.label }, flatten(after).map { it.label })
        assertEquals(beforeSummary, summary(result, emptySet()))
        assertEquals("1 changed", beforeSummary)
        assertFalse(flatten(before).any { it.label.contains("reviewed") })
    }

    @Test
    // outline/12-reviewed-marks R12
    fun `outline 12 R12 Hide Reviewed hides reviewed rows, uses container rules and counts them as hidden`() {
        val reviewedChild = node("read", content = Content.CHANGED)
        val unseen = node("unread", content = Content.CHANGED)
        val parent = node("Parent", children = listOf(reviewedChild, unseen))
        val result = result(parent)
        val reviewedSet = identitySetOf(reviewedChild)

        val rows = build(result, reviewedSet, hideReviewed = true)
        assertEquals("class Parent", rows.single().label)
        assertEquals(listOf("method unread()  changed"), rows.single().children.map { it.label })
        assertEquals(1, OutlineModel.hiddenCount(result, emptySet(), false, reviewedSet, true))
        assertEquals("2 changed · 1 hidden · 1 of 2 reviewed", summary(result, reviewedSet, hideReviewed = true))
        assertEquals("2 changed", summary(result))
    }

    @Test
    // outline/12-reviewed-marks R1
    fun `outline 12 R1 an uncertain node is markable but candidates and unanalyzed ranges are not`() {
        val uncertain = node("maybe", presence = Presence.UNCERTAIN, old = null, new = TextRange(0, 3), candidates = listOf(Candidate(Subject.Declaration(DeclarationKind.METHOD, "other", ""), TextRange(4, 7))))
        val result = ComparisonResult(
            EngineId("e"), "1", Status.PARTIAL,
            changes = listOf(uncertain), unanalyzed = listOf(RangePair(TextRange(0, 2), null)),
        )
        val all = ReviewedMarks.markable(result, request.oldText, request.newText, WhitespacePolicy.INSIGNIFICANT)
        assertTrue("an uncertain node is markable", all.contains(uncertain))

        val rows = build(result, identitySetOf(uncertain))
        val labels = flatten(rows).map { it.label }
        assertTrue(labels.any { it.startsWith("Uncertain (1)") })
        assertTrue("the uncertain group shows the mark", labels.any { it.startsWith("Uncertain (1)  reviewed") })
        assertTrue(labels.any { it.startsWith("Unanalyzed (1)") })
        assertFalse("an unanalyzed range has no node", labels.any { it.startsWith("Unanalyzed (1), reviewed") })
        assertFalse("a candidate row has no node", labels.any { it.startsWith("candidate:") && it.contains("reviewed") })
    }

    @Test
    // outline/12-reviewed-marks R13
    fun `outline 12 R13 the refactorings view marks relationship and other-changes rows`() {
        val old = Subject.Declaration(DeclarationKind.METHOD, "total", "")
        val new = Subject.Declaration(DeclarationKind.METHOD, "sum", "")
        val renamed = node("sum", renamed = old, content = Content.CHANGED)
        val other = node("other", old = TextRange(4, 8), new = TextRange(4, 8), content = Content.CHANGED)
        val relationship = Relationship(
            EnumSet.of(RelationshipKind.RENAME),
            listOf(RelationshipEndpoint(old, TextRange(0, 3))),
            listOf(RelationshipEndpoint(new, TextRange(0, 3))),
            Confidence.HIGH,
            listOf(Evidence.Name("total", "sum")),
        )
        val result = ComparisonResult(EngineId("e"), "1", Status.ANALYZED, changes = listOf(renamed, other), relationships = listOf(relationship))
        val reviewed = identitySetOf(renamed)

        val rows = RefactoringsModel.build(result, request.oldText, request.newText, false, markCommentOnly = false, reviewed = reviewed)
        val relationshipRow = flatten(rows).single { it.kind == ItemKind.RELATIONSHIP }
        val otherSection = rows.single { it.title == RefactoringsModel.OTHER_CHANGES }

        assertEquals(listOf(renamed), relationshipRow.endpoints)
        assertEquals("rename total → sum  high, reviewed", relationshipRow.label)
        assertTrue(relationshipRow.reviewed)
        assertFalse("an unreviewed change keeps Other changes unmarked", otherSection.reviewed)
        assertTrue(flatten(rows).any { it.label == "method other()  changed" })
    }
}
