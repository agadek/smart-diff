package io.github.agadek.smartdiff.merge

import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Origin
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The join of a merge's two results (three-way merge plan §5, §7 join unit tests), with results built by hand. Every
 * case also checks that each node either result reports as changed itself is in exactly one row.
 */
class MergeJoinTest {

    private val base = "class A {\n  void f() { 1 }\n  void g() { 2 }\n  void h() { 3 }\n}\n"
    private val classA = decl(DeclarationKind.CLASS, "A", null)
    private val f = decl(DeclarationKind.METHOD, "f", "()")
    private val g = decl(DeclarationKind.METHOD, "g", "()")
    private val h = decl(DeclarationKind.METHOD, "h", "()")

    @Test
    fun `a declaration changed on one side is in that side's group`() {
        val left = base.replace("{ 1 }", "{ 10 }")
        val joined = join(
            result(inA(base, left, changed(f, base, left, "void f() { 10 }"), same(g, base, left), same(h, base, left))),
            result(unchanged(base)),
            left,
            base,
        )

        val row = joined.nodes.single()
        assertEquals(classA, row.subject)
        assertEquals(MergeGroup.UNCHANGED, row.group)
        assertEquals(listOf("f LEFT_ONLY"), rows(row.children))
    }

    @Test
    fun `a declaration changed on both sides is a likely conflict, the others keep their side`() {
        val left = base.replace("{ 1 }", "{ 10 }").replace("{ 2 }", "{ 20 }")
        val right = base.replace("{ 1 }", "{ 11 }").replace("{ 3 }", "{ 33 }")
        val joined = join(
            result(inA(base, left, changed(f, base, left, "void f() { 10 }"), changed(g, base, left, "void g() { 20 }"), same(h, base, left))),
            result(inA(base, right, changed(f, base, right, "void f() { 11 }"), same(g, base, right), changed(h, base, right, "void h() { 33 }"))),
            left,
            right,
        )

        assertEquals(listOf("f CHANGED_ON_BOTH_SIDES", "g LEFT_ONLY", "h RIGHT_ONLY"), rows(joined.nodes.single().children))
        assertFalse(joined.nodes.single().children.first().sameChange)
    }

    @Test
    fun `the same change on both sides is marked`() {
        val side = base.replace("{ 1 }", "{ 10 }")
        val joined = join(
            result(inA(base, side, changed(f, base, side, "void f() { 10 }"), same(g, base, side), same(h, base, side))),
            result(inA(base, side, changed(f, base, side, "void f() { 10 }"), same(g, base, side), same(h, base, side))),
            side,
            side,
        )

        val row = joined.nodes.single().children.single()
        assertEquals(MergeGroup.CHANGED_ON_BOTH_SIDES, row.group)
        assertTrue(row.sameChange)
    }

    @Test
    fun `a rename on one side and a change on the other are both changes of the base declaration`() {
        val left = base.replace("void f()", "void sum()")
        val right = base.replace("{ 1 }", "{ 11 }")
        val sum = decl(DeclarationKind.METHOD, "sum", "()")
        val renamed = ChangeNode(sum, range(base, "void f() { 1 }"), range(left, "void sum() { 1 }"), Presence.MATCHED, renamedFrom = f)
        val joined = join(
            result(inA(base, left, renamed, same(g, base, left), same(h, base, left))),
            result(inA(base, right, changed(f, base, right, "void f() { 11 }"), same(g, base, right), same(h, base, right))),
            left,
            right,
        )

        val row = joined.nodes.single().children.single()
        assertEquals(f, row.subject)
        assertEquals(MergeGroup.CHANGED_ON_BOTH_SIDES, row.group)
        assertSame(renamed, row.left)
    }

    @Test
    fun `removed on one side and changed on the other is a change on both sides`() {
        val left = base.replace("  void f() { 1 }\n", "")
        val right = base.replace("{ 1 }", "{ 11 }")
        val joined = join(
            result(inA(base, left, removed(f, base), same(g, base, left), same(h, base, left))),
            result(inA(base, right, changed(f, base, right, "void f() { 11 }"), same(g, base, right), same(h, base, right))),
            left,
            right,
        )

        val row = joined.nodes.single().children.single()
        assertEquals(MergeGroup.CHANGED_ON_BOTH_SIDES, row.group)
        assertEquals(Presence.REMOVED, row.left!!.presence)
        assertFalse(row.sameChange)
    }

    @Test
    fun `removed on both sides is the same change`() {
        val side = base.replace("  void f() { 1 }\n", "")
        val joined = join(
            result(inA(base, side, removed(f, base), same(g, base, side), same(h, base, side))),
            result(inA(base, side, removed(f, base), same(g, base, side), same(h, base, side))),
            side,
            side,
        )

        assertTrue(joined.nodes.single().children.single().sameChange)
    }

    @Test
    fun `a declaration added on both sides with the same key pairs, identical or not`() {
        val left = base.dropLast(2) + "  void k() { 4 }\n}\n"
        val right = base.dropLast(2) + "  void k() { 5 }\n}\n"
        val k = decl(DeclarationKind.METHOD, "k", "()")
        fun joinWith(rightText: String) = join(
            result(inA(base, left, same(f, base, left), same(g, base, left), same(h, base, left), added(k, left, "void k() { 4 }"))),
            result(inA(base, rightText, same(f, base, rightText), same(g, base, rightText), same(h, base, rightText), added(k, rightText, "void k() {"))),
            left,
            rightText,
        )

        val different = joinWith(right).nodes.single().children.single()
        assertEquals(k, different.subject)
        assertNull(different.baseRange)
        assertEquals(MergeGroup.CHANGED_ON_BOTH_SIDES, different.group)
        assertFalse(different.sameChange)

        val identical = join(
            result(inA(base, left, same(f, base, left), same(g, base, left), same(h, base, left), added(k, left, "void k() { 4 }"))),
            result(inA(base, left, same(f, base, left), same(g, base, left), same(h, base, left), added(k, left, "void k() { 4 }"))),
            left,
            left,
        ).nodes.single().children.single()
        assertTrue(identical.sameChange)
    }

    @Test
    fun `an added key repeated on a side stays unpaired`() {
        val k = decl(DeclarationKind.METHOD, "k", "()")
        val left = base.dropLast(2) + "  void k() { 4 }\n  void k() { 5 }\n}\n"
        val right = base.dropLast(2) + "  void k() { 6 }\n}\n"
        val joined = join(
            result(inA(base, left, same(f, base, left), same(g, base, left), same(h, base, left), added(k, left, "void k() { 4 }"), added(k, left, "void k() { 5 }"))),
            result(inA(base, right, same(f, base, right), same(g, base, right), same(h, base, right), added(k, right, "void k() { 6 }"))),
            left,
            right,
        )

        assertEquals(listOf("k LEFT_ONLY", "k LEFT_ONLY", "k RIGHT_ONLY"), rows(joined.nodes.single().children))
    }

    @Test
    fun `members added in a class added on both sides pair under it`() {
        val b = decl(DeclarationKind.CLASS, "B", null)
        val m = decl(DeclarationKind.METHOD, "m", "()")
        val side = base + "class B {\n  void m() {}\n}\n"
        fun classB() = ChangeNode(
            b, null, range(side, "class B {\n  void m() {}\n}\n"), Presence.ADDED,
            children = listOf(added(m, side, "void m() {}")),
        )
        val joined = join(result(unchanged(base, side), classB()), result(unchanged(base, side), classB()), side, side)

        val row = joined.nodes.single()
        assertEquals("B CHANGED_ON_BOTH_SIDES", rows(listOf(row)).single())
        assertEquals(listOf("m CHANGED_ON_BOTH_SIDES"), rows(row.children))
        assertTrue(row.children.single().sameChange)
    }

    @Test
    fun `moved on one side is a change of that side`() {
        val left = base.replace("  void f() { 1 }\n  void g() { 2 }\n", "  void g() { 2 }\n  void f() { 1 }\n")
        val moved = ChangeNode(f, range(base, "void f() { 1 }"), range(left, "void f() { 1 }"), Presence.MATCHED, moved = true)
        val joined = join(result(inA(base, left, moved, same(g, base, left), same(h, base, left))), result(unchanged(base)), left, base)

        assertEquals(listOf("f LEFT_ONLY"), rows(joined.nodes.single().children))
    }

    @Test
    fun `uncertain on one side goes to the uncertain group with that side's node`() {
        val right = base.replace("{ 2 }", "{ 22 }")
        val uncertain = ChangeNode(f, range(base, "void f() { 1 }"), null, Presence.UNCERTAIN)
        val joined = join(
            result(inA(base, base, uncertain, same(g, base, base), same(h, base, base))),
            result(inA(base, right, same(f, base, right), changed(g, base, right, "void g() { 22 }"), same(h, base, right))),
            base,
            right,
        )

        assertEquals(listOf("f UNCERTAIN", "g RIGHT_ONLY"), rows(joined.nodes.single().children))
        assertSame(uncertain, joined.nodes.single().children.first().left)
    }

    @Test
    fun `an uncertain added declaration never pairs`() {
        val k = decl(DeclarationKind.METHOD, "k", "()")
        val side = base.dropLast(2) + "  void k() { 4 }\n}\n"
        val uncertain = ChangeNode(k, null, range(side, "void k() { 4 }"), Presence.UNCERTAIN)
        val joined = join(
            result(inA(base, side, same(f, base, side), same(g, base, side), same(h, base, side), uncertain)),
            result(inA(base, side, same(f, base, side), same(g, base, side), same(h, base, side), added(k, side, "void k() { 4 }"))),
            side,
            side,
        )

        assertEquals(listOf("k UNCERTAIN", "k RIGHT_ONLY"), rows(joined.nodes.single().children))
    }

    @Test
    fun `a side that was not analyzed leaves the other side's changes in its group`() {
        val right = base.replace("{ 1 }", "{ 11 }")
        val failed = ComparisonResult(EngineId("test"), "1", Status.FAILED, fallbackReason = "parse failed")
        val joined = join(failed, result(inA(base, right, changed(f, base, right, "void f() { 11 }"), same(g, base, right), same(h, base, right))), base, right)

        assertFalse(joined.isAnalyzed(MergeSide.LEFT))
        assertTrue(joined.isAnalyzed(MergeSide.RIGHT))
        assertFalse(joined.baseMismatch)
        assertEquals(listOf("f RIGHT_ONLY"), rows(joined.nodes.single().children))
    }

    @Test
    fun `results that read the base differently are not joined`() {
        val right = base.replace("{ 1 }", "{ 11 }")
        // The right side's tree lacks h: its base was parsed differently.
        val joined = join(
            result(unchanged(base)),
            result(inA(base, right, changed(f, base, right, "void f() { 11 }"), same(g, base, right))),
            base,
            right,
        )

        assertTrue(joined.baseMismatch)
        assertTrue(joined.nodes.isEmpty())
    }

    @Test
    fun `a node renamed in the base by one side is the same base declaration`() {
        // The consistency check compares base names, so a rename on one side is not a mismatch.
        val left = base.replace("void g()", "void two()")
        val two = decl(DeclarationKind.METHOD, "two", "()")
        val renamed = ChangeNode(two, range(base, "void g() { 2 }"), range(left, "void two() { 2 }"), Presence.MATCHED, renamedFrom = g)
        val joined = join(result(inA(base, left, same(f, base, left), renamed, same(h, base, left))), result(unchanged(base)), left, base)

        assertFalse(joined.baseMismatch)
        assertEquals(listOf("g LEFT_ONLY"), rows(joined.nodes.single().children))
    }

    @Test
    fun `extraction links stay with their side`() {
        val shipping = decl(DeclarationKind.METHOD, "shipping", "()")
        val left = base.replace("{ 1 }", "{ shipping() }").dropLast(2) + "  void shipping() { 1 }\n}\n"
        val right = base.replace("{ 1 }", "{ 11 }")
        val source = ChangeNode(
            f, range(base, "void f() { 1 }"), range(left, "void f() { shipping() }"), Presence.MATCHED,
            content = Content.CHANGED, extractedTo = listOf(shipping),
        )
        val extracted = ChangeNode(
            shipping, null, range(left, "void shipping() { 1 }"), Presence.ADDED,
            extractedFrom = listOf(Origin(f, range(base, "void f() { 1 }"), range(base, "1"))),
        )
        val joined = join(
            result(inA(base, left, source, same(g, base, left), same(h, base, left), extracted)),
            result(inA(base, right, changed(f, base, right, "void f() { 11 }"), same(g, base, right), same(h, base, right))),
            left,
            right,
        )

        val rows = joined.nodes.single().children
        assertEquals(listOf("f CHANGED_ON_BOTH_SIDES", "shipping LEFT_ONLY"), rows(rows))
        assertEquals(listOf(shipping), rows.first().left!!.extractedTo)
        assertEquals(f, rows.last().left!!.extractedFrom.single().subject)
    }

    @Test
    fun `the file region pairs directly`() {
        val left = "import a;\n$base"
        val right = "import b;\n$base"
        // The import line on the new side, nothing on the base side.
        fun region() = ChangeNode(
            Subject.Region("file"), TextRange(0, 0), TextRange(0, 10), Presence.MATCHED, content = Content.CHANGED,
            details = listOf(RangePair(TextRange(0, 0), TextRange(0, 10))),
        )
        val joined = join(result(region = region()), result(region = region()), left, right)

        assertEquals(MergeGroup.CHANGED_ON_BOTH_SIDES, joined.fileRegion!!.group)
        assertFalse(joined.fileRegion!!.sameChange)

        val same = join(result(region = region()), result(region = region()), left, left)
        assertTrue(same.fileRegion!!.sameChange)
        assertEquals(MergeGroup.RIGHT_ONLY, join(result(), result(region = region()), base, right).fileRegion!!.group)
    }

    @Test
    fun `the join is deterministic`() {
        val left = base.replace("{ 1 }", "{ 10 }")
        val right = base.replace("{ 3 }", "{ 33 }")
        fun once() = join(
            result(inA(base, left, changed(f, base, left, "void f() { 10 }"), same(g, base, left), same(h, base, left))),
            result(inA(base, right, same(f, base, right), same(g, base, right), changed(h, base, right, "void h() { 33 }"))),
            left,
            right,
        )
        assertEquals(once(), once())
    }

    /** Joins and checks that every node either result reports as changed itself is in exactly one row. */
    private fun join(left: ComparisonResult, right: ComparisonResult, leftText: String, rightText: String): JoinedResult {
        val joined = MergeJoin.join(left, right, leftText, rightText)
        if (!joined.baseMismatch) {
            for (side in MergeSide.entries) {
                if (!joined.isAnalyzed(side)) continue
                val rowsOfSide = (joined.nodes.flatMap(::flatten) + listOfNotNull(joined.fileRegion)).mapNotNull { it.node(side) }
                val changed = joined.result(side).changes.flatMap(::flattenNodes).filter { it.isChangedItself } +
                    listOfNotNull(joined.result(side).fileRegion?.takeIf { it.isChangedItself })
                for (node in changed) {
                    assertEquals("$side ${node.subject} in exactly one row", 1, rowsOfSide.count { it === node })
                }
            }
        }
        return joined
    }

    private fun flatten(node: MergeNode): List<MergeNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun flattenNodes(node: ChangeNode): List<ChangeNode> = listOf(node) + node.children.flatMap(::flattenNodes)

    private fun rows(nodes: List<MergeNode>): List<String> = nodes.map { "${(it.subject as Subject.Declaration).name} ${it.group}" }

    private fun decl(kind: DeclarationKind, name: String, signature: String?) = Subject.Declaration(kind, name, signature)

    private fun result(vararg changes: ChangeNode, region: ChangeNode? = null) =
        ComparisonResult(EngineId("test"), "1", Status.ANALYZED, fileRegion = region, changes = changes.toList())

    /** Class A matched between [old] and [new], with [members]. */
    private fun inA(old: String, new: String, vararg members: ChangeNode) =
        ChangeNode(classA, TextRange(0, old.length - 1), TextRange(0, new.length - 1), Presence.MATCHED, children = members.toList())

    private fun unchanged(old: String, new: String = old) = inA(old, new, same(f, old, new), same(g, old, new), same(h, old, new))

    private fun same(subject: Subject.Declaration, old: String, new: String): ChangeNode {
        val code = "void ${subject.name}() {"
        return ChangeNode(subject, rangeOfLine(old, code), rangeOfLine(new, code), Presence.MATCHED)
    }

    private fun changed(subject: Subject.Declaration, old: String, new: String, newCode: String) =
        ChangeNode(subject, rangeOfLine(old, "void ${subject.name}() {"), range(new, newCode), Presence.MATCHED, content = Content.CHANGED)

    private fun removed(subject: Subject.Declaration, old: String) =
        ChangeNode(subject, rangeOfLine(old, "void ${subject.name}() {"), null, Presence.REMOVED)

    private fun added(subject: Subject.Declaration, new: String, code: String) =
        ChangeNode(subject, null, rangeOfLine(new, code), Presence.ADDED)

    private fun range(text: String, code: String): TextRange {
        val start = text.indexOf(code)
        require(start >= 0) { "'$code' not in text" }
        return TextRange(start, start + code.length)
    }

    /** The range of the line that starts with [code] after its indentation, without the line break. */
    private fun rangeOfLine(text: String, code: String): TextRange {
        val start = text.indexOf(code)
        require(start >= 0) { "'$code' not in text" }
        return TextRange(start, text.indexOf('\n', start))
    }
}
