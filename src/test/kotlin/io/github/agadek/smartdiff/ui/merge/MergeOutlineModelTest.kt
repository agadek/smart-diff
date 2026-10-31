package io.github.agadek.smartdiff.ui.merge

import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.merge.JoinedResult
import io.github.agadek.smartdiff.merge.MergeGroup
import io.github.agadek.smartdiff.merge.MergeNode
import io.github.agadek.smartdiff.ui.ChangeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The merge outline model (merge outline spec 02), without an IDE. */
class MergeOutlineModelTest {

    @Test
    fun `side names use the branch or revision and cut unknown titles`() {
        assertEquals("feature/rates", MergeSideNames.fromTitle("<html>Your version, branch <b>feature/rates</b></html>", "Left").display)
        assertEquals("main", MergeSideNames.fromTitle("Changes from branch main, revision 1a2b3c4d", "Left").display)
        assertEquals("feature/rates", MergeSideNames.fromTitle("Changes from origin/feature/rates", "Left").display)
        assertEquals("1a2b3c4", MergeSideNames.fromTitle("Changes from server (revision 1a2b3c4d)", "Left").display)
        assertEquals("a-very-long-branch-name", MergeSideNames.fromTitle("Changes from branch a-very-long-branch-name", "Left").display)
        assertEquals("a very long side tit", MergeSideNames.fromTitle("a very long side title that is over twenty", "Left").display)
        assertEquals("a very long side title that is over twenty", MergeSideNames.fromTitle("a very long side title that is over twenty", "Left").fullTitle)
        assertFalse(MergeSideNames.fromTitle("Your version, branch feature/rates", "Left").display.contains("ours", ignoreCase = true))
        assertFalse(MergeSideNames.fromTitle("Changes from branch main, revision 1a2b3c4d", "Left").display.contains("theirs", ignoreCase = true))
    }

    @Test
    fun `grouped rows show each side's labels and the group summary`() {
        // merge/02-merge-outline R1, R4, R5, R12
        val f = decl("f")
        val node = row(f, MergeGroup.CHANGED_ON_BOTH_SIDES, left = changed(f), right = changed(f))
        val joined = JoinedResult(result(changed(f)), result(changed(f)), nodes = listOf(node))

        val model = build(joined, MergeOutlineLayout.GROUPED_BY_SIDE)

        assertEquals("1 changed on both sides", model.summary)
        val group = model.items.single()
        assertEquals("Changed on both sides", group.title)
        val row = group.children.single()
        assertEquals("method f()  main: changed  feature: changed", row.label)
        assertEquals("main | feature", row.tooltip)
        assertTrue(row.accessibleName, row.accessibleName.contains("main"))
    }

    @Test
    fun `a container row has no labels and keeps its changed child`() {
        // merge/02-merge-outline R2
        val f = decl("f")
        val container = decl("A", DeclarationKind.CLASS)
        val child = row(f, MergeGroup.CHANGED_ON_BOTH_SIDES, left = changed(f), right = changed(f))
        val node = MergeNode(container, range(0, 10), matched(container), matched(container), MergeGroup.UNCHANGED, children = listOf(child))
        val joined = JoinedResult(result(changed(f)), result(changed(f)), nodes = listOf(node))

        val model = build(joined, MergeOutlineLayout.CONTAINER_TREE)

        val parent = model.items.single()
        assertEquals("class A", parent.title)
        assertEquals(null, parent.left)
        assertEquals("method f()  main: changed  feature: changed", parent.children.single().label)
    }

    @Test
    fun `a hidden change disappears and the summary counts it`() {
        // merge/02-merge-outline R13, R14
        val f = decl("f")
        val node = row(f, MergeGroup.CHANGED_ON_BOTH_SIDES, left = changed(f), right = changed(f))
        val joined = JoinedResult(result(changed(f)), result(changed(f)), nodes = listOf(node))

        val model = MergeOutlineModel.build(
            joined, "base", "left", "right", MergeSideName("main", "main"), MergeSideName("feature", "feature"),
            MergeOutlineLayout.GROUPED_BY_SIDE, setOf(ChangeType.CHANGED),
        )

        assertEquals(emptyList<MergeOutlineItem>(), model.items)
        assertEquals(1, model.hiddenCount)
        assertEquals("1 changed on both sides · 1 hidden", model.summary)
    }

    @Test
    fun `the container tree can hide rows changed on both sides`() {
        // merge/02-merge-outline R15
        val f = decl("f")
        val node = row(f, MergeGroup.CHANGED_ON_BOTH_SIDES, left = changed(f), right = changed(f))
        val joined = JoinedResult(result(changed(f)), result(changed(f)), nodes = listOf(node))

        val model = MergeOutlineModel.build(
            joined, "base", "left", "right", MergeSideName("main", "main"), MergeSideName("feature", "feature"),
            MergeOutlineLayout.CONTAINER_TREE, hideBothSides = true,
        )

        assertEquals(emptyList<MergeOutlineItem>(), model.items)
        assertEquals(1, model.hiddenCount)
    }

    @Test
    fun `same change rows come last in their group`() {
        // merge/02-merge-outline R6
        val a = decl("a")
        val b = decl("b")
        val nodeA = row(a, MergeGroup.CHANGED_ON_BOTH_SIDES, sameChange = false, left = changed(a), right = changed(a))
        val nodeB = row(b, MergeGroup.CHANGED_ON_BOTH_SIDES, sameChange = true, left = changed(b), right = changed(b))
        val joined = JoinedResult(result(changed(a), changed(b)), result(changed(a), changed(b)), nodes = listOf(nodeA, nodeB))

        val rows = build(joined, MergeOutlineLayout.GROUPED_BY_SIDE).items.single().children

        assertEquals(listOf("method a()", "method b()"), rows.map { it.title })
        assertTrue(rows.last().note == "same change on both sides")
    }

    @Test
    fun `a base mismatch gives each side its own group`() {
        // merge/02-merge-outline R21, R31
        val joined = JoinedResult(result(changed(decl("f"))), result(changed(decl("f"))), baseMismatch = true)

        val model = build(joined, MergeOutlineLayout.GROUPED_BY_SIDE)

        assertTrue(model.baseMismatch)
        assertEquals("1 in main · 1 in feature", model.summary)
        assertEquals(listOf("Left: main", "Right: feature"), model.items.map { it.title })
    }

    @Test
    fun `a one-side row uses that side's name in the summary`() {
        // merge/02-merge-outline R5, R12
        val f = decl("f")
        val node = row(f, MergeGroup.LEFT_ONLY, left = changed(f), right = matched(f))
        val joined = JoinedResult(result(changed(f)), result(matched(f)), nodes = listOf(node))

        val model = build(joined, MergeOutlineLayout.GROUPED_BY_SIDE)

        assertEquals("1 in main only", model.summary)
        assertEquals("method f()  main: changed", model.items.single().children.single().label)
    }

    @Test
    fun `an uncertain row lists the candidates from its side`() {
        // merge/02-merge-outline R7
        val f = decl("f")
        val uncertain = ChangeNode(
            f, range(0, 5), null, Presence.UNCERTAIN,
            candidates = listOf(io.github.agadek.smartdiff.engine.api.Candidate(f, range(0, 5))),
        )
        val node = row(f, MergeGroup.UNCERTAIN, left = uncertain, right = changed(f))
        val joined = JoinedResult(result(uncertain), result(changed(f)), nodes = listOf(node))

        val model = build(joined, MergeOutlineLayout.GROUPED_BY_SIDE)

        val row = model.items.single().children.single()
        assertEquals("candidate: method f()", row.children.single().title)
    }

    @Test
    fun `a hidden change type removes only that side's label`() {
        // merge/02-merge-outline R13
        val f = decl("f")
        val left = ChangeNode(f, range(0, 5), range(0, 5), Presence.MATCHED, moved = true, content = Content.CHANGED)
        val right = changed(f)
        val node = row(f, MergeGroup.CHANGED_ON_BOTH_SIDES, left = left, right = right)
        val joined = JoinedResult(result(left), result(right), nodes = listOf(node))

        val model = MergeOutlineModel.build(
            joined, "base", "left", "right", MergeSideName("main", "main"), MergeSideName("feature", "feature"),
            MergeOutlineLayout.GROUPED_BY_SIDE, setOf(ChangeType.CHANGED),
        )

        assertEquals("method f()  main: moved", model.items.single().children.single().label)
    }

    @Test
    fun `an unanalyzed side does not claim the other side changed alone`() {
        // merge/02-merge-outline R22
        val f = decl("f")
        val node = row(f, MergeGroup.RIGHT_ONLY, left = null, right = changed(f))
        val left = ComparisonResult(EngineId("test"), "1", Status.FAILED, fallbackReason = "parse failed")
        val joined = JoinedResult(left, result(changed(f)), nodes = listOf(node))

        val model = build(joined, MergeOutlineLayout.GROUPED_BY_SIDE)

        assertEquals("1 in feature", model.summary)
        assertEquals("Changed in feature", model.items.single().title)
    }

    @Test
    fun `a mismatch counts hidden rows`() {
        // merge/02-merge-outline R21, R32
        val left = changed(decl("f"))
        val right = changed(decl("g"))
        val joined = JoinedResult(result(left), result(right), baseMismatch = true)

        val model = MergeOutlineModel.build(
            joined, "base", "left", "right", MergeSideName("main", "main"), MergeSideName("feature", "feature"),
            MergeOutlineLayout.GROUPED_BY_SIDE, setOf(ChangeType.CHANGED),
        )

        assertEquals(2, model.hiddenCount)
        assertEquals("1 in main · 1 in feature · 2 hidden", model.summary)
    }

    @Test
    fun `a changed row without a conflict is merged without conflict`() {
        // merge/04-merge-window R12
        val clean = decl("clean")
        val conflicted = decl("conflicted")
        val cleanRow = MergeNode(clean, range(0, 5), changed(clean), changed(clean), MergeGroup.CHANGED_ON_BOTH_SIDES)
        val conflictRow = MergeNode(conflicted, range(10, 15), changed(conflicted), changed(conflicted), MergeGroup.CHANGED_ON_BOTH_SIDES)
        val joined = JoinedResult(result(changed(clean)), result(changed(clean)), nodes = listOf(cleanRow, conflictRow))

        val rows = build(joined, MergeOutlineLayout.GROUPED_BY_SIDE, listOf(range(10, 15))).items.single().children

        assertEquals(null, rows.first { it.title == "method conflicted()" }.note)
        assertEquals("merged without conflict", rows.first { it.title == "method clean()" }.note)
    }

    @Test
    fun `merged rows come after conflicting rows in their group`() {
        // merge/04-merge-window R13; merge/02-merge-outline R6
        val clean = decl("clean")
        val conflicted = decl("conflicted")
        val same = decl("same")
        val cleanRow = MergeNode(clean, range(0, 5), changed(clean), changed(clean), MergeGroup.CHANGED_ON_BOTH_SIDES)
        val conflictRow = MergeNode(conflicted, range(10, 15), changed(conflicted), changed(conflicted), MergeGroup.CHANGED_ON_BOTH_SIDES)
        val sameRow = MergeNode(same, range(20, 25), changed(same), changed(same), MergeGroup.CHANGED_ON_BOTH_SIDES, sameChange = true)
        val joined = JoinedResult(result(changed(clean)), result(changed(clean)), nodes = listOf(cleanRow, conflictRow, sameRow))

        val rows = build(joined, MergeOutlineLayout.GROUPED_BY_SIDE, listOf(range(10, 15))).items.single().children

        assertEquals(listOf("method conflicted()", "method clean()", "method same()"), rows.map { it.title })
        assertEquals("same change on both sides · merged without conflict", rows.last().note)
    }

    @Test
    fun `unreadable conflicts leave the note out`() {
        // merge/04-merge-window R14
        val f = decl("f")
        val node = MergeNode(f, range(0, 5), changed(f), changed(f), MergeGroup.CHANGED_ON_BOTH_SIDES)
        val joined = JoinedResult(result(changed(f)), result(changed(f)), nodes = listOf(node))

        val rows = build(joined, MergeOutlineLayout.GROUPED_BY_SIDE, conflictRanges = null).items.single().children

        assertEquals(null, rows.single().note)
    }

    private fun build(
        joined: JoinedResult,
        layout: MergeOutlineLayout,
        conflictRanges: List<TextRange>? = null,
    ) = MergeOutlineModel.build(
        joined, "base", "left", "right",
        MergeSideName("main", "main"), MergeSideName("feature", "feature"), layout,
        conflictRanges = conflictRanges,
    )

    private fun row(
        subject: Subject,
        group: MergeGroup,
        sameChange: Boolean = false,
        left: ChangeNode? = null,
        right: ChangeNode? = null,
    ) = MergeNode(subject, range(0, 5), left, right, group, sameChange)

    private fun changed(subject: Subject) = ChangeNode(subject, range(0, 5), range(0, 5), Presence.MATCHED, content = Content.CHANGED)

    private fun matched(subject: Subject) = ChangeNode(subject, range(0, 5), range(0, 5), Presence.MATCHED)

    private fun result(vararg nodes: ChangeNode) = ComparisonResult(EngineId("test"), "1", Status.ANALYZED, changes = nodes.toList())

    private fun decl(name: String, kind: DeclarationKind = DeclarationKind.METHOD) =
        Subject.Declaration(kind, name, if (kind == DeclarationKind.CLASS) null else "")

    private fun range(start: Int, end: Int) = TextRange(start, end)
}
