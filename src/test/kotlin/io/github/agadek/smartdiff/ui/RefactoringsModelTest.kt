package io.github.agadek.smartdiff.ui

import com.intellij.ui.JBColor
import io.github.agadek.smartdiff.engine.api.Candidate
import io.github.agadek.smartdiff.engine.api.ChangeNode
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumSet

/** The refactorings view's rows, filters and coverage (relationships/06), without the IDE. */
class RefactoringsModelTest {

    @Test
    fun `R3 R4 one row per relationship names its kinds and both names and lists usages`() {
        val result = ComparisonResult(
            EngineId("e"), "1", Status.ANALYZED,
            relationships = listOf(
                rename("total", 0, 5, "sum", 0, 3).copy(
                    evidence = listOf(Evidence.Name("total", "sum"), Evidence.Reference(method("sum"), TextRange(7, 12), TextRange(7, 10)), Evidence.Score(0.9)),
                ),
                relationship(EnumSet.of(RelationshipKind.MOVE), method("parse"), 10, 15, method("parse"), 20, 25),
                relationship(
                    EnumSet.of(RelationshipKind.EXTRACT), method("summarize"), 20, 29, method("describeItems"), 30, 38,
                    evidence = listOf(Evidence.Content(4, 5, TextRange(21, 25), TextRange(31, 35))),
                ),
                relationship(
                    EnumSet.of(RelationshipKind.INLINE), method("heading"), 30, 37, method("render"), 40, 46,
                    evidence = listOf(Evidence.Content(4, 5, TextRange(31, 35), TextRange(41, 45))),
                ),
                relationship(
                    EnumSet.of(RelationshipKind.KIND_CHANGE),
                    Subject.Declaration(DeclarationKind.TRAIT, "Shape", null), 40, 45,
                    Subject.Declaration(DeclarationKind.CLASS, "Shape", null), 50, 55,
                ),
            ),
        )

        val section = refactorings(result)

        assertEquals(
            listOf(
                "rename total → sum, 1 usage  high",
                "moved parse  high",
                "extracted describeItems from summarize  high",
                "inlined heading into render  high",
                "kind change trait → class Shape  high",
            ),
            section.children.map { it.label },
        )
        assertTrue("every relationship is its own row", section.children.all { it.kind == ItemKind.RELATIONSHIP })
        val rename = section.children.first()
        assertEquals("the usage is listed under its rename", ItemKind.USAGE, rename.children.first().kind)
        assertEquals("usage  line 2  resolves to method sum()", rename.children.first().label)
    }

    @Test
    fun `R7 R8 rows show a confidence word, a medium row is a candidate and a low row is possible under both declarations`() {
        val oldLow = node("load", Presence.REMOVED, TextRange(10, 14), null)
        val newLow = node("read", Presence.ADDED, null, TextRange(20, 24))
        val result = ComparisonResult(
            EngineId("e"), "1", Status.ANALYZED,
            changes = listOf(
                node("total", Presence.MATCHED, TextRange(0, 5), TextRange(0, 3)),
                node("first", Presence.MATCHED, TextRange(5, 10), TextRange(10, 15)),
                oldLow,
                newLow,
            ),
            relationships = listOf(
                rename("total", 0, 5, "sum", 0, 3),
                relationship(EnumSet.of(RelationshipKind.RENAME), method("first"), 5, 10, method("second"), 10, 15, Confidence.MEDIUM),
                relationship(EnumSet.of(RelationshipKind.RENAME), method("load"), 10, 14, method("read"), 20, 24, Confidence.LOW),
            ),
        )

        val rows = RefactoringsModel.build(result, "line1\nline2\nline3\nline4\n", "line1\nline2\nline3\nline4\n", formattingInline = false)
        val refactorings = rows.single { it.title == RefactoringsModel.REFACTORINGS }
        val other = rows.single { it.title == RefactoringsModel.OTHER_CHANGES }

        assertEquals(
            listOf(
                "rename total → sum  high",
                "candidate rename first → second  medium",
                "possible rename load → read  low",
            ),
            refactorings.children.map { it.label },
        )
        assertFalse("a confidence is never a percentage", refactorings.children.any { it.label.contains('%') })
        for (row in refactorings.children) {
            assertTrue("a confidence word is shown: ${row.label}", listOf("high", "medium", "low").any { row.label.endsWith("  $it") })
        }

        val removed = other.children.single { it.title == "method load()" }
        val added = other.children.single { it.title == "method read()" }
        assertEquals("the possible link is under the removed declaration", "possible rename load → read  low", removed.children.single().label)
        assertEquals("the possible link is under the added declaration", "possible rename load → read  low", added.children.single().label)
    }

    @Test
    fun `R8 low-confidence links stay on their side when old and new offsets collide`() {
        val oldLow = node("load", Presence.REMOVED, TextRange(10, 14), null)
        val newLow = node("read", Presence.ADDED, null, TextRange(20, 24))
        val unrelated = node("other", Presence.MATCHED, TextRange(20, 24), TextRange(30, 34), content = Content.CHANGED)
        val result = ComparisonResult(
            EngineId("e"), "1", Status.ANALYZED,
            changes = listOf(unrelated, oldLow, newLow),
            relationships = listOf(
                relationship(EnumSet.of(RelationshipKind.RENAME), method("load"), 10, 14, method("read"), 20, 24, Confidence.LOW),
            ),
        )

        val other = otherChanges(result)
        val removed = other.single { it.title == "method load()" }
        val added = other.single { it.title == "method read()" }
        val colliding = other.single { it.title == "method other()" }

        assertEquals("the possible link is under the removed declaration", "possible rename load → read  low", removed.children.single().label)
        assertEquals("the possible link is under the added declaration", "possible rename load → read  low", added.children.single().label)
        assertTrue(
            "a row whose old range has the same offsets as the new endpoint must not get the link",
            colliding.children.isEmpty(),
        )
    }

    @Test
    fun `R9 expanding a row shows its evidence and never the numeric score`() {
        val result = ComparisonResult(
            EngineId("e"), "1", Status.ANALYZED,
            relationships = listOf(
                rename("total", 0, 5, "sum", 0, 3).copy(
                    evidence = listOf(
                        Evidence.Name("total", "sum"),
                        Evidence.Content(4, 5, TextRange(1, 5), TextRange(1, 3)),
                        Evidence.Structure("Order", "Service"),
                        Evidence.Position(2, 3),
                        Evidence.Reference(method("sum"), TextRange(7, 12), TextRange(7, 10)),
                        Evidence.Score(0.91),
                    ),
                ),
            ),
        )

        val labels = refactorings(result).children.single().children.map { it.label }

        assertTrue(labels.toString(), labels.contains("name  total → sum"))
        assertTrue(labels.toString(), labels.contains("content  4 of 5 tokens"))
        assertTrue(labels.toString(), labels.contains("container  Order → Service"))
        assertTrue(labels.toString(), labels.contains("position  3 → 4"))
        assertTrue(labels.toString(), labels.contains("usage  line 2  resolves to method sum()"))
        assertFalse("the score is debug data, not a percentage", labels.any { it.contains("0.91") || it.contains('%') })
    }

    @Test
    fun `R10 all endpoints are framed and every grouped usage is a detail`() {
        val oldA = method("first")
        val oldB = method("second")
        val newTarget = method("target")
        val result = ComparisonResult(
            EngineId("e"), "1", Status.ANALYZED,
            relationships = listOf(
                Relationship(
                    EnumSet.of(RelationshipKind.EXTRACT),
                    listOf(RelationshipEndpoint(oldA, TextRange(0, 5)), RelationshipEndpoint(oldB, TextRange(10, 15))),
                    listOf(RelationshipEndpoint(newTarget, TextRange(20, 26))),
                    Confidence.HIGH,
                    listOf(
                        Evidence.Content(3, 4, TextRange(0, 3), TextRange(20, 23)),
                        Evidence.Content(2, 4, TextRange(10, 12), TextRange(24, 26)),
                    ),
                ),
            ),
        )

        val row = refactorings(result).children.single()

        assertEquals("the first old endpoint is the primary range", TextRange(0, 5), row.oldRange)
        assertEquals("the new endpoint is the primary range", TextRange(20, 26), row.newRange)
        assertEquals(
            "the other endpoint is framed too",
            listOf(RangePair(TextRange(10, 15), null)),
            row.frames,
        )
        assertEquals(
            "both shared-content ranges are highlighted",
            listOf(RangePair(TextRange(0, 3), TextRange(20, 23)), RangePair(TextRange(10, 12), TextRange(24, 26))),
            row.details,
        )
    }

    @Test
    fun `R11 a declaration absent on one side keeps that side null`() {
        val result = ComparisonResult(
            EngineId("e"), "1", Status.ANALYZED,
            changes = listOf(node("gone", Presence.REMOVED, TextRange(0, 4), null)),
        )

        val row = otherChanges(result).single { it.title == "method gone()" }

        assertEquals(TextRange(0, 4), row.oldRange)
        assertNull(row.newRange)
    }

    @Test
    fun `R12 the palette has six distinct colours and the seventh rename reuses the first`() {
        val result = ComparisonResult(
            EngineId("e"), "1", Status.ANALYZED,
            relationships = (1..7).map { i ->
                rename("old$i", i * 10, i * 10 + 5, "new$i", i * 10, i * 10 + 5).copy(
                    evidence = listOf(Evidence.Reference(method("new$i"), TextRange(i * 10, i * 10 + 2), TextRange(i * 10, i * 10 + 2))),
                )
            },
        )

        val colours = RefactoringsModel.renameColours(result)

        assertEquals(7, colours.size)
        assertEquals(0, colours.values.first())
        assertEquals(5, colours.values.elementAt(5))
        assertEquals("the seventh rename reuses the first colour", 0, colours.values.elementAt(6))
        assertEquals(6, colours.values.distinct().size)
        val usages = RefactoringsModel.usageHighlights(result)
        assertEquals("every usage has its rename's colour", listOf(0, 1, 2, 3, 4, 5, 0), usages.map { it.colour })
        assertEquals(RelationshipPalette.SIZE, listOf(0, 1, 2, 3, 4, 5).map(RelationshipPalette::colour).distinct().size)
    }

    @Test
    fun `R13 the change type filter changes only Other changes in both views`() {
        val result = ComparisonResult(
            EngineId("e"), "1", Status.ANALYZED,
            changes = listOf(
                node("renamed", Presence.MATCHED, TextRange(0, 5), TextRange(0, 3)),
                node("changed", Presence.MATCHED, TextRange(10, 15), TextRange(10, 15), content = Content.CHANGED),
                node("moved", Presence.MATCHED, TextRange(20, 25), TextRange(20, 25), moved = true),
            ),
            relationships = listOf(rename("renamed", 0, 5, "sum", 0, 3)),
        )

        val rows = RefactoringsModel.build(result, "", "", formattingInline = false, hidden = setOf(ChangeType.CHANGED, ChangeType.MOVED))
        val refactorings = rows.single { it.title == RefactoringsModel.REFACTORINGS }
        val other = rows.single { it.title == RefactoringsModel.OTHER_CHANGES }

        assertEquals("a relationship row has no change type", 1, refactorings.children.count { it.kind == ItemKind.RELATIONSHIP })
        assertEquals("the hidden changes and the covered rename are gone from Other changes", emptyList<String>(), other.children.titleOnly())
        assertEquals("both views count the same hidden changes", 2, OutlineModel.hiddenCount(result, setOf(ChangeType.CHANGED, ChangeType.MOVED)))
        assertEquals(
            "the summary counts every change and the hidden ones",
            "1 changed · 1 moved · 2 hidden",
            OutlineModel.summary(result, setOf(ChangeType.CHANGED, ChangeType.MOVED)),
        )
    }

    @Test
    fun `R14 a relationship is hidden only when all of its kinds are hidden`() {
        val result = ComparisonResult(
            EngineId("e"), "1", Status.ANALYZED,
            changes = listOf(node("total", Presence.MATCHED, TextRange(0, 5), TextRange(0, 3), moved = true)),
            relationships = listOf(
                Relationship(
                    EnumSet.of(RelationshipKind.RENAME, RelationshipKind.MOVE),
                    listOf(RelationshipEndpoint(method("total"), TextRange(0, 5))),
                    listOf(RelationshipEndpoint(method("sum"), TextRange(0, 3))),
                    Confidence.HIGH,
                    listOf(Evidence.Name("total", "sum")),
                ),
            ),
        )

        val renameHidden = RefactoringsModel.build(result, "", "", formattingInline = false, hiddenKinds = setOf(RelationshipKind.RENAME))
        assertEquals("a shown move keeps the row", listOf("rename and move total → sum  high"), refactorings(renameHidden).children.map { it.label })

        val allHidden = RefactoringsModel.build(
            result, "", "", formattingInline = false,
            hiddenKinds = setOf(RelationshipKind.RENAME, RelationshipKind.MOVE),
        )
        assertEquals(listOf(RefactoringsModel.NO_REFACTORINGS), refactorings(allHidden).children.map { it.label })
        assertTrue("the declarations stay in Other changes", otherChanges(allHidden).any { it.title == "method total()" })
    }

    @Test
    fun `R15 a relationship never hides an uncertain, unanalyzed or changed declaration`() {
        val uncertain = ChangeNode(
            method("ambiguous"), null, TextRange(30, 39), Presence.UNCERTAIN,
            candidates = listOf(Candidate(method("other"), TextRange(30, 35))),
        )
        val unanalysed = node("broken", Presence.MATCHED, TextRange(40, 46), TextRange(40, 46), content = Content.UNANALYZED)
        val changed = node("changed", Presence.MATCHED, TextRange(50, 57), TextRange(50, 57), content = Content.CHANGED)
        val result = ComparisonResult(
            EngineId("e"), "1", Status.PARTIAL,
            changes = listOf(uncertain, unanalysed, changed),
            relationships = listOf(
                relationship(EnumSet.of(RelationshipKind.MOVE), method("broken"), 40, 46, method("broken"), 40, 46),
                relationship(EnumSet.of(RelationshipKind.MOVE), method("changed"), 50, 57, method("changed"), 50, 57),
            ),
        )

        val shown = RefactoringsModel.build(result, "", "", formattingInline = false)
        val shownOther = otherChanges(shown)
        assertTrue("the unanalyzed declaration is never covered", shownOther.any { it.title == "method broken()" && Tag.UNANALYZED in it.tags })
        assertTrue("the uncertain declaration keeps its group", shownOther.any { it.title == "Uncertain (1)" })
        assertTrue("the changed declaration keeps its changed label", shownOther.any { it.title == "method changed()" && Tag.CHANGED in it.tags })
        assertEquals("both relationships are rows", 2, refactorings(shown).children.size)

        val hidden = RefactoringsModel.build(result, "", "", formattingInline = false, hiddenKinds = setOf(RelationshipKind.MOVE))
        val hiddenOther = otherChanges(hidden)
        assertTrue("a hidden relationship never hides the changed declaration", hiddenOther.any { it.title == "method changed()" && Tag.CHANGED in it.tags })
        assertTrue("a hidden relationship never hides the unanalyzed declaration", hiddenOther.any { it.title == "method broken()" })
        assertEquals(listOf(RefactoringsModel.NO_REFACTORINGS), refactorings(hidden).children.map { it.label })
    }

    @Test
    fun `R15 a changed endpoint keeps its changed label in Other changes`() {
        val old = method("total")
        val new = method("sum")
        val changed = node("sum", Presence.MATCHED, TextRange(0, 5), TextRange(0, 3), content = Content.CHANGED)
            .copy(renamedFrom = old)
        val result = ComparisonResult(
            EngineId("e"), "1", Status.ANALYZED,
            changes = listOf(changed),
            relationships = listOf(rename("total", 0, 5, "sum", 0, 3)),
        )

        val other = otherChanges(result)

        assertEquals(
            "the changed declaration keeps both its rename and changed labels",
            listOf("method total → sum()  rename candidate, changed"),
            other.map { it.label },
        )
    }

    @Test
    fun `R16 a file with no relationships says No refactorings found and then shows Other changes`() {
        val result = ComparisonResult(
            EngineId("e"), "1", Status.ANALYZED,
            changes = listOf(node("validate", Presence.MATCHED, TextRange(0, 8), TextRange(0, 8), content = Content.CHANGED)),
        )

        val rows = RefactoringsModel.build(result, "", "", formattingInline = false)

        assertEquals(listOf(RefactoringsModel.REFACTORINGS, RefactoringsModel.OTHER_CHANGES), rows.map { it.title })
        assertEquals(listOf(RefactoringsModel.NO_REFACTORINGS), rows[0].children.map { it.label })
        assertEquals(listOf("method validate()  changed"), rows[1].children.map { it.label })
    }

    @Test
    fun `R16 all relationships hidden also says No refactorings found`() {
        val result = ComparisonResult(
            EngineId("e"), "1", Status.ANALYZED,
            changes = listOf(node("total", Presence.MATCHED, TextRange(0, 5), TextRange(0, 3))),
            relationships = listOf(rename("total", 0, 5, "sum", 0, 3)),
        )

        val rows = RefactoringsModel.build(result, "", "", formattingInline = false, hiddenKinds = RelationshipKind.entries.toSet())

        assertEquals(listOf(RefactoringsModel.NO_REFACTORINGS), rows[0].children.map { it.label })
    }

    @Test
    fun `R3 rows are sorted by the first new-side position, not by the result order`() {
        val result = ComparisonResult(
            EngineId("e"), "1", Status.ANALYZED,
            relationships = listOf(
                rename("late", 0, 4, "late", 30, 34),
                rename("early", 0, 4, "early", 10, 14),
                rename("middle", 0, 4, "middle", 20, 24),
            ),
        )

        assertEquals(
            listOf("rename early → early  high", "rename middle → middle  high", "rename late → late  high"),
            refactorings(result).children.map { it.label },
        )
    }

    @Test
    fun `R12 the light and dark palettes each have six distinct colours`() {
        try {
            JBColor.setDark(false)
            assertEquals(RelationshipPalette.SIZE, (0 until RelationshipPalette.SIZE).map { RelationshipPalette.colour(it).rgb }.distinct().size)
            JBColor.setDark(true)
            assertEquals(RelationshipPalette.SIZE, (0 until RelationshipPalette.SIZE).map { RelationshipPalette.colour(it).rgb }.distinct().size)
        } finally {
            JBColor.setDark(false)
        }
    }

    private fun refactorings(result: ComparisonResult) =
        RefactoringsModel.build(result, LINE_TEXT, LINE_TEXT, formattingInline = false).single { it.title == RefactoringsModel.REFACTORINGS }

    private fun otherChanges(result: ComparisonResult) =
        RefactoringsModel.build(result, LINE_TEXT, LINE_TEXT, formattingInline = false).single { it.title == RefactoringsModel.OTHER_CHANGES }.children

    private fun refactorings(rows: List<OutlineItem>) = rows.single { it.title == RefactoringsModel.REFACTORINGS }

    private fun otherChanges(rows: List<OutlineItem>) = rows.single { it.title == RefactoringsModel.OTHER_CHANGES }.children

    private fun List<OutlineItem>.titleOnly(): List<String> = map { it.title }

    private companion object {
        const val LINE_TEXT = "line1\nline2\nline3\nline4\nline5\nline6\n"
    }

    private fun method(name: String) = Subject.Declaration(DeclarationKind.METHOD, name, "")

    private fun rename(old: String, oldStart: Int, oldEnd: Int, new: String, newStart: Int, newEnd: Int) =
        relationship(EnumSet.of(RelationshipKind.RENAME), method(old), oldStart, oldEnd, method(new), newStart, newEnd)

    private fun relationship(
        kinds: Set<RelationshipKind>,
        old: Subject,
        oldStart: Int,
        oldEnd: Int,
        new: Subject,
        newStart: Int,
        newEnd: Int,
        confidence: Confidence = Confidence.HIGH,
        evidence: List<Evidence> = emptyList(),
    ) = Relationship(
        kinds,
        listOf(RelationshipEndpoint(old, TextRange(oldStart, oldEnd))),
        listOf(RelationshipEndpoint(new, TextRange(newStart, newEnd))),
        confidence,
        evidence,
    )

    private fun node(
        name: String,
        presence: Presence,
        old: TextRange?,
        new: TextRange?,
        content: Content = Content.UNCHANGED,
        moved: Boolean = false,
    ) = ChangeNode(method(name), old, new, presence, moved = moved, content = content)
}
