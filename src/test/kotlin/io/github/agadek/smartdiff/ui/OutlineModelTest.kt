package io.github.agadek.smartdiff.ui

import io.github.agadek.smartdiff.engine.api.Candidate
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.Origin
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.RelationshipEndpoint
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.ui.changes.ChangesTreeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The outline rows and summary built from engine-neutral results (technical specification §10), without the IDE. */
class OutlineModelTest {

    @Test
    fun `titles name kind, name and signature, or a region's description`() {
        assertEquals("method parse(String,int)", OutlineModel.title(Subject.Declaration(DeclarationKind.METHOD, "parse", "String,int")))
        assertEquals("enum constant RED", OutlineModel.title(Subject.Declaration(DeclarationKind.ENUM_CONSTANT, "RED", null)))
        assertEquals("constructor(Int)", OutlineModel.title(Subject.Declaration(DeclarationKind.CONSTRUCTOR, "", "Int")))
        assertEquals("lines 3-4 changed", OutlineModel.title(Subject.Region("lines 3-4 changed")))
        assertEquals("function Date.formatIsoDate()", OutlineModel.title(Subject.Declaration(DeclarationKind.FUNCTION, "formatIsoDate", "", "Date")))
        assertEquals("property List<T>.second", OutlineModel.title(Subject.Declaration(DeclarationKind.PROPERTY, "second", null, "List<T>")))
    }

    @Test
    fun `a rename candidate shows both names, is never hidden and is counted`() {
        // declarations/06-change-labels R4, R11
        val renamed = node("sum", renamedFrom = Subject.Declaration(DeclarationKind.METHOD, "total", ""))
        val result = result(renamed, node("other", content = Content.CHANGED))
        val everything = ChangeType.entries.toSet()

        assertEquals("method total → sum()  rename candidate", render(OutlineModel.build(result, "", "", formattingInline = false, hidden = everything)))
        assertEquals("1 changed · 1 rename candidate · 1 hidden", OutlineModel.summary(result, everything))
    }

    @Test
    fun `R28 a high and a medium rename show the same row`() {
        // relationships/02-relationship-model R28
        val old = Subject.Declaration(DeclarationKind.METHOD, "total", "")
        val new = Subject.Declaration(DeclarationKind.METHOD, "sum", "")
        val node = node("sum", renamedFrom = old)
        fun row(confidence: Confidence): String = render(
            OutlineModel.build(
                ComparisonResult(
                    EngineId("e"), "1", Status.ANALYZED,
                    changes = listOf(node),
                    relationships = listOf(
                        Relationship(
                            kinds = java.util.EnumSet.of(RelationshipKind.RENAME),
                            old = listOf(RelationshipEndpoint(old, TextRange(0, 5))),
                            new = listOf(RelationshipEndpoint(new, TextRange(6, 11))),
                            confidence = confidence,
                            evidence = emptyList(),
                        ),
                    ),
                ),
                "", "", formattingInline = false,
            ),
        )

        val high = row(Confidence.HIGH)
        assertEquals("both confidences show one rename candidate row", high, row(Confidence.MEDIUM))
        assertEquals("method total → sum()  rename candidate", high)
    }

    @Test
    fun `only reported nodes and their containers are shown, and a container has no change label`() {
        val result = result(
            node(
                "Parser", presence = Presence.MATCHED,
                children = listOf(
                    node("parse", content = Content.CHANGED),
                    node("unchanged"),
                    node("added", presence = Presence.ADDED, old = null),
                ),
            ),
            node("Untouched", children = listOf(node("alsoUntouched"))),
        )

        assertEquals(
            """
            class Parser
              method parse()  changed
              method added()  added
            """.trimIndent(),
            render(OutlineModel.build(result, "", "", formattingInline = false)),
        )
    }

    @Test
    fun `labels list presence, moved, content and signature changed`() {
        // declarations/06-change-labels R2
        val result = result(
            node("a", moved = true, content = Content.FORMATTING_ONLY),
            node("b", signatureChanged = true, content = Content.CHANGED),
            node("c", presence = Presence.REMOVED, new = null),
            node("d", content = Content.UNANALYZED),
        )

        assertEquals(
            """
            method a()  moved, formatting-only
            method b()  changed, signature changed
            method c()  removed
            method d()  unanalyzed
            """.trimIndent(),
            render(OutlineModel.build(result, "", "", formattingInline = false)),
        )
    }

    @Test
    fun `every change label appears when it applies`() {
        // declarations/06-change-labels R1
        val result = result(
            node("added", presence = Presence.ADDED, old = null),
            node("removed", presence = Presence.REMOVED, new = null),
            node("changed", content = Content.CHANGED),
            node("moved", moved = true),
            node("formatting", content = Content.FORMATTING_ONLY),
            node("signature", signatureChanged = true, content = Content.CHANGED),
            node("renamed", renamedFrom = Subject.Declaration(DeclarationKind.METHOD, "old", "")),
            node("uncertain", presence = Presence.UNCERTAIN, old = null),
            node("unanalyzed", content = Content.UNANALYZED),
        )

        assertEquals(
            """
            method added()  added
            method removed()  removed
            method changed()  changed
            method moved()  moved
            method formatting()  formatting-only
            method signature()  changed, signature changed
            method old → renamed()  rename candidate
            method unanalyzed()  unanalyzed
            Uncertain (1)
              method uncertain()  uncertain
            """.trimIndent(),
            render(OutlineModel.build(result, "", "", formattingInline = true)),
        )
    }

    @Test
    fun `a kind change shows both kinds and carries signature changed`() {
        // declarations/06-change-labels R3
        val kindChange = node("Shape", signatureChanged = true, content = Content.CHANGED)
            .copy(kindChangedFrom = DeclarationKind.TRAIT)

        assertEquals(
            "trait → class Shape  changed, signature changed",
            render(OutlineModel.build(result(kindChange), "", "", formattingInline = false)),
        )
    }

    @Test
    fun `formatting-only changes are collected in a group per container unless shown inline`() {
        val result = result(
            node(
                "Parser",
                children = listOf(node("a", content = Content.FORMATTING_ONLY), node("b", content = Content.CHANGED), node("c", content = Content.FORMATTING_ONLY)),
            ),
            node("top", content = Content.FORMATTING_ONLY),
        )

        assertEquals(
            """
            class Parser
              method b()  changed
              Formatting-only (2)
                method a()  formatting-only
                method c()  formatting-only
            Formatting-only (1)
              method top()  formatting-only
            """.trimIndent(),
            render(OutlineModel.build(result, "", "", formattingInline = false)),
        )
        assertEquals(
            """
            class Parser
              method a()  formatting-only
              method b()  changed
              method c()  formatting-only
            method top()  formatting-only
            """.trimIndent(),
            render(OutlineModel.build(result, "", "", formattingInline = true)),
        )
    }

    @Test
    fun `uncertain nodes are listed in a top-level group with their candidates`() {
        val candidates = listOf(
            Candidate(Subject.Declaration(DeclarationKind.METHOD, "run", "int"), TextRange(10, 20)),
            Candidate(Subject.Declaration(DeclarationKind.METHOD, "run", "long"), TextRange(30, 40)),
        )
        val result = result(
            node("Runner", children = listOf(node("run", presence = Presence.UNCERTAIN, old = null, candidates = candidates))),
            node("other", content = Content.CHANGED),
        )

        val rows = OutlineModel.build(result, "", "", formattingInline = false)

        assertEquals(
            """
            method other()  changed
            Uncertain (1)
              method run()  uncertain
                candidate: method run(int)
                candidate: method run(long)
            """.trimIndent(),
            render(rows),
        )
        val candidate = rows[1].children[0].children[0]
        assertEquals("a candidate is on the side where the uncertain node is absent", TextRange(10, 20), candidate.oldRange)
        assertNull(candidate.newRange)
    }

    @Test
    fun `file region comes first and unanalyzed ranges last, with their lines`() {
        val old = "a\nb\nc\n"
        val new = "a\nb\nc\nd\n"
        val result = ComparisonResult(
            EngineId("e"), "1", Status.PARTIAL,
            fileRegion = ChangeNode(Subject.Region("file"), TextRange(0, 2), TextRange(0, 2), Presence.MATCHED, content = Content.CHANGED),
            changes = listOf(node("m", content = Content.CHANGED)),
            unanalyzed = listOf(RangePair(TextRange(2, 6), TextRange(6, 8))),
        )

        assertEquals(
            """
            File region  changed
            method m()  changed
            Unanalyzed (1)
              Unanalyzed source, old lines 2-3, new line 4
            """.trimIndent(),
            render(OutlineModel.build(result, old, new, formattingInline = false)),
        )
    }

    @Test
    fun `an unchanged file region is not shown`() {
        val result = ComparisonResult(
            EngineId("e"), "1", Status.ANALYZED,
            fileRegion = ChangeNode(Subject.Region("file"), TextRange(0, 2), TextRange(0, 2), Presence.MATCHED),
        )

        assertEquals("", render(OutlineModel.build(result, "", "", formattingInline = false)))
        assertEquals("No structural changes", OutlineModel.summary(result))
    }

    @Test
    fun `the summary counts each kind of change`() {
        val result = ComparisonResult(
            EngineId("e"), "1", Status.PARTIAL,
            fileRegion = ChangeNode(Subject.Region("file"), TextRange(0, 1), TextRange(0, 1), Presence.MATCHED, content = Content.FORMATTING_ONLY),
            changes = listOf(
                node(
                    "A",
                    children = listOf(
                        node("a", content = Content.CHANGED),
                        node("b", content = Content.CHANGED, moved = true),
                        node("c", presence = Presence.ADDED, old = null),
                        node("d", presence = Presence.REMOVED, new = null),
                        node("e", presence = Presence.UNCERTAIN, old = null),
                    ),
                ),
            ),
            unanalyzed = listOf(RangePair(TextRange(0, 1), null)),
        )

        assertEquals("2 changed · 1 added · 1 removed · 1 moved · 1 formatting-only · 1 uncertain · 1 unanalyzed", OutlineModel.summary(result))
    }

    @Test
    fun `the summary counts every label in a fixed order`() {
        // declarations/06-change-labels R6
        val result = ComparisonResult(
            EngineId("e"), "1", Status.ANALYZED,
            changes = listOf(
                node("changed", content = Content.CHANGED),
                node("formatting", content = Content.FORMATTING_ONLY),
                node("moved", moved = true),
                node("removed", presence = Presence.REMOVED, new = null),
                node("added", presence = Presence.ADDED, old = null),
                node("renamed", renamedFrom = Subject.Declaration(DeclarationKind.METHOD, "old", "")),
                node("uncertain", presence = Presence.UNCERTAIN, old = null),
                node("extracted", presence = Presence.ADDED, old = null).copy(
                    extractedFrom = listOf(Origin(method("source"), TextRange(0, 1), TextRange(0, 1))),
                ),
            ),
            unanalyzed = listOf(RangePair(TextRange(0, 1), null)),
        )

        assertEquals(
            "1 changed · 1 extracted · 1 added · 1 removed · 1 moved · 1 formatting-only · 1 rename candidate · 1 uncertain · 1 unanalyzed",
            OutlineModel.summary(result),
        )
        assertEquals("a zero count is left out", "1 changed", OutlineModel.summary(result(node("changed", content = Content.CHANGED))))
    }

    @Test
    fun `a signature change and a kind change count as changed`() {
        // declarations/06-change-labels R8
        val result = result(
            node("signature", signatureChanged = true, content = Content.CHANGED),
            node("kind", signatureChanged = true, content = Content.CHANGED).copy(kindChangedFrom = DeclarationKind.TRAIT),
        )

        assertEquals("2 changed", OutlineModel.summary(result))
    }

    @Test
    fun `a change whose every label is hidden is left out, and the summary says how many are hidden`() {
        // declarations/06-change-labels R9, R12
        val result = result(
            node(
                "Parser",
                content = Content.FORMATTING_ONLY,
                children = listOf(
                    node("a", content = Content.FORMATTING_ONLY),
                    node("b", content = Content.CHANGED),
                    node("c", moved = true, content = Content.FORMATTING_ONLY),
                    node("d", moved = true),
                ),
            ),
            node("e", presence = Presence.ADDED, old = null),
            node("f", content = Content.FORMATTING_ONLY),
        )
        val hidden = setOf(ChangeType.FORMATTING_ONLY, ChangeType.ADDED)

        assertEquals(
            """
            class Parser
              method b()  changed
              method c()  moved, formatting-only
              method d()  moved
            """.trimIndent(),
            render(OutlineModel.build(result, "", "", formattingInline = false, hidden = hidden)),
        )
        assertEquals("a hidden container still counts", 4, OutlineModel.hiddenCount(result, hidden))
        assertEquals("1 changed · 1 added · 2 moved · 4 formatting-only · 4 hidden", OutlineModel.summary(result, hidden))
    }

    @Test
    fun `hiding changed covers signature changes, and uncertain and unanalyzed changes are never hidden`() {
        // declarations/06-change-labels R11
        val result = ComparisonResult(
            EngineId("e"), "1", Status.PARTIAL,
            fileRegion = ChangeNode(Subject.Region("file"), TextRange(0, 1), TextRange(0, 1), Presence.MATCHED, content = Content.CHANGED),
            changes = listOf(
                node("a", signatureChanged = true, content = Content.CHANGED),
                node("b", content = Content.UNANALYZED),
                node("c", presence = Presence.UNCERTAIN, old = null),
            ),
            unanalyzed = listOf(RangePair(TextRange(0, 1), null)),
        )

        assertEquals(
            """
            method b()  unanalyzed
            Uncertain (1)
              method c()  uncertain
            Unanalyzed (1)
              Unanalyzed source, old line 1
            """.trimIndent(),
            render(OutlineModel.build(result, "a\n", "", formattingInline = false, hidden = ChangeType.entries.toSet())),
        )
        assertEquals(2, OutlineModel.hiddenCount(result, ChangeType.entries.toSet()))
    }

    @Test
    fun `the filter hides changed, added, removed, moved and formatting-only`() {
        // declarations/06-change-labels R10
        val result = result(
            node("changed", content = Content.CHANGED),
            node("signature", signatureChanged = true, content = Content.CHANGED),
            node("added", presence = Presence.ADDED, old = null),
            node("removed", presence = Presence.REMOVED, new = null),
            node("moved", moved = true),
            node("formatting", content = Content.FORMATTING_ONLY),
        )
        val hiddenRows = mapOf(
            ChangeType.CHANGED to listOf("changed", "signature"),
            ChangeType.ADDED to listOf("added"),
            ChangeType.REMOVED to listOf("removed"),
            ChangeType.MOVED to listOf("moved"),
            ChangeType.FORMATTING_ONLY to listOf("formatting"),
        )

        for ((type, hidden) in hiddenRows) {
            val rows = render(OutlineModel.build(result, "", "", formattingInline = true, hidden = setOf(type)))
            hidden.forEach { name ->
                assertTrue("$type hides $name", rows.lines().none { it.startsWith("method $name()") })
            }
            val kept = if (type == ChangeType.CHANGED) "added" else "changed"
            assertTrue("$type keeps $kept", rows.lines().any { it.startsWith("method $kept()") })
        }
    }

    @Test
    fun `the tree filter hides each change type`() {
        // changes-tree/04-hide-and-filter R7
        val result = result(
            node("changed", content = Content.CHANGED),
            node("signature", signatureChanged = true, content = Content.CHANGED),
            node("added", presence = Presence.ADDED, old = null),
            node("removed", presence = Presence.REMOVED, new = null),
            node("moved", moved = true),
            node("formatting", content = Content.FORMATTING_ONLY),
        )
        val hiddenRows = mapOf(
            ChangeType.CHANGED to listOf("changed", "signature"),
            ChangeType.ADDED to listOf("added"),
            ChangeType.REMOVED to listOf("removed"),
            ChangeType.MOVED to listOf("moved"),
            ChangeType.FORMATTING_ONLY to listOf("formatting"),
        )

        for ((type, hidden) in hiddenRows) {
            val filtered = ChangesTreeFilter.filter(OutlineModel.build(result, "", "", formattingInline = true), setOf(type))
            val rows = render(filtered.items)
            hidden.forEach { name ->
                assertTrue("$type hides $name", rows.lines().none { it.startsWith("method $name()") })
            }
            val kept = if (type == ChangeType.CHANGED) "added" else "changed"
            assertTrue("$type keeps $kept", rows.lines().any { it.startsWith("method $kept()") })
            assertEquals("$type counts the hidden change", hidden.size, filtered.hiddenCount)
        }
    }

    @Test
    fun `hiding changed also hides a signature change from the tree`() {
        // changes-tree/04-hide-and-filter R8
        val result = result(node("signature", signatureChanged = true, content = Content.CHANGED))

        val filtered = ChangesTreeFilter.filter(
            OutlineModel.build(result, "", "", formattingInline = false),
            setOf(ChangeType.CHANGED),
        )

        assertTrue("the signature change is hidden", filtered.items.isEmpty())
        assertEquals(1, filtered.hiddenCount)
    }

    @Test
    fun `a change with one shown label stays in the tree`() {
        // changes-tree/04-hide-and-filter R9
        val result = result(node("moved", moved = true, content = Content.FORMATTING_ONLY))

        val filtered = ChangesTreeFilter.filter(
            OutlineModel.build(result, "", "", formattingInline = false),
            setOf(ChangeType.MOVED),
        )

        assertEquals("method moved()  moved, formatting-only", render(filtered.items))
        assertEquals(0, filtered.hiddenCount)
    }

    @Test
    fun `a hidden container with a shown child stays without labels in the tree`() {
        // changes-tree/04-hide-and-filter R10
        val result = result(
            node("Parser", content = Content.CHANGED, children = listOf(node("added", presence = Presence.ADDED, old = null))),
        )

        val filtered = ChangesTreeFilter.filter(
            OutlineModel.build(result, "", "", formattingInline = false),
            setOf(ChangeType.CHANGED),
        )

        assertEquals("class Parser\n  method added()  added", render(filtered.items))
        assertEquals("the hidden container still counts", 1, filtered.hiddenCount)
    }

    @Test
    fun `a shown change with a hidden child stays with its own labels in the tree`() {
        // changes-tree/04-hide-and-filter R7, R9
        val result = result(
            node("Parser", content = Content.CHANGED, children = listOf(node("added", presence = Presence.ADDED, old = null))),
        )

        val filtered = ChangesTreeFilter.filter(
            OutlineModel.build(result, "", "", formattingInline = false),
            setOf(ChangeType.ADDED),
        )

        assertEquals("class Parser  changed", render(filtered.items))
        assertEquals("the hidden child still counts", 1, filtered.hiddenCount)
        assertEquals(
            "1 changed · 1 added · 1 hidden",
            ChangesTreeFilter.summary("1 changed · 1 added", filtered),
        )
    }

    @Test
    fun `uncertain rename candidate and unanalyzed rows are never hidden from the tree`() {
        // changes-tree/04-hide-and-filter R11
        val result = ComparisonResult(
            EngineId("e"), "1", Status.PARTIAL,
            changes = listOf(
                node("sum", renamedFrom = Subject.Declaration(DeclarationKind.METHOD, "total", "")),
                node("uncertain", presence = Presence.UNCERTAIN, old = null),
                node("unanalyzed", content = Content.UNANALYZED),
            ),
            unanalyzed = listOf(RangePair(TextRange(0, 1), null)),
        )

        val filtered = ChangesTreeFilter.filter(
            OutlineModel.build(result, "a\n", "", formattingInline = false),
            ChangeType.entries.toSet(),
        )

        assertEquals(
            """
            method total → sum()  rename candidate
            method unanalyzed()  unanalyzed
            Uncertain (1)
              method uncertain()  uncertain
            Unanalyzed (1)
              Unanalyzed source, old line 1
            """.trimIndent(),
            render(filtered.items),
        )
        assertEquals("no never-hidden row is counted", 0, filtered.hiddenCount)
    }

    @Test
    fun `every row's label is its title followed by its change labels`() {
        // declarations/06-change-labels R13
        val item = OutlineModel.build(result(node("a", moved = true, content = Content.CHANGED)), "", "", formattingInline = false).single()

        assertEquals("method a()  moved, changed", item.label)
    }

    @Test
    fun `R9 a rename row notes one usage or twelve usages`() {
        // relationships/04-rename-grouping R9
        val fixture = usageFixture()
        assertEquals("method total → sum()  rename candidate, 1 usage", render(OutlineModel.build(fixture.result, fixture.old, fixture.new, formattingInline = false)).lines().single { it.contains("rename candidate") }.trimStart())

        val relationship = fixture.result.relationships.single()
        val symbol = (relationship.new.single().subject as Subject.Declaration)
        val twelve = (1..12).map { Evidence.Reference(symbol, TextRange(it, it + 1), TextRange(it, it + 1)) }
        val many = fixture.result.copy(relationships = listOf(relationship.copy(evidence = listOf(Evidence.Name("total", "sum")) + twelve + Evidence.Score(0.9))))
        assertEquals(
            "method total → sum()  rename candidate, 12 usages",
            render(OutlineModel.build(many, fixture.old, fixture.new, formattingInline = false)).lines().single { it.contains("rename candidate") }.trimStart(),
        )
    }

    @Test
    fun `R10 a declaration whose only change is a grouped usage is labelled usages renamed`() {
        // relationships/04-rename-grouping R10, declarations/06-change-labels R1, R4
        val fixture = usageFixture()

        val rows = render(OutlineModel.build(fixture.result, fixture.old, fixture.new, formattingInline = false))

        assertTrue(rows, rows.contains("class Order  changed"))
        assertTrue(rows, rows.contains("method total → sum()  rename candidate, 1 usage"))
        assertTrue(rows, rows.contains("method twice()  usages renamed"))
        assertEquals("1 changed · 1 rename candidate · 1 usages renamed", OutlineModel.summary(fixture.result, oldText = fixture.old, newText = fixture.new))
    }

    @Test
    fun `R10 a comment that keeps the old name does not make the label`() {
        // relationships/04-rename-grouping R10
        val fixture = usageFixture(oldComment = "total", newComment = "total")

        val rows = render(OutlineModel.build(fixture.result, fixture.old, fixture.new, formattingInline = false))

        assertTrue(rows, rows.contains("method twice()  usages renamed"))
    }

    @Test
    fun `R10 a declaration with another change keeps changed`() {
        // relationships/04-rename-grouping R10
        val fixture = usageFixture(extraNewChange = true)

        val rows = render(OutlineModel.build(fixture.result, fixture.old, fixture.new, formattingInline = false))

        assertTrue(rows, rows.contains("method twice()  changed, usages renamed").not())
        assertTrue(rows, rows.contains("method twice()  changed"))
    }

    @Test
    fun `R10 a rename endpoint stays rename candidate even when it contains a usage`() {
        // relationships/04-rename-grouping R10
        val fixture = usageFixture(recursiveUsage = true)

        val rows = render(OutlineModel.build(fixture.result, fixture.old, fixture.new, formattingInline = false))

        assertTrue(rows, rows.contains("method total → sum()  rename candidate, 2 usages"))
    }

    @Test
    fun `R10 an unanalyzed declaration is never labelled usages renamed`() {
        // relationships/04-rename-grouping R10, R12
        val fixture = usageFixture()
        fun unanalyse(node: ChangeNode): ChangeNode {
            val marked = if (node.subject == Subject.Declaration(DeclarationKind.METHOD, "twice", "")) node.copy(content = Content.UNANALYZED) else node
            return marked.copy(children = marked.children.map(::unanalyse))
        }
        val result = fixture.result.copy(status = Status.PARTIAL, changes = fixture.result.changes.map(::unanalyse))

        val rows = render(OutlineModel.build(result, fixture.old, fixture.new, formattingInline = false))

        assertTrue(rows, rows.contains("method twice()  unanalyzed"))
        assertFalse(rows, rows.contains("usages renamed"))
    }

    @Test
    fun `R10 leading whitespace does not change the label`() {
        // relationships/04-rename-grouping R10
        val fixture = usageFixture(leadingWhitespace = true)

        val rows = render(OutlineModel.build(fixture.result, fixture.old, fixture.new, formattingInline = false))

        assertTrue(rows, rows.contains("method twice()  usages renamed"))
    }

    @Test
    fun `R10 a string literal that keeps the old name does not make the label`() {
        // relationships/04-rename-grouping R10
        val unchanged = usageFixture(oldLiteral = "total", newLiteral = "total")
        val changed = usageFixture(oldLiteral = "total", newLiteral = "sum")

        val unchangedRows = render(OutlineModel.build(unchanged.result, unchanged.old, unchanged.new, formattingInline = false))
        val changedRows = render(OutlineModel.build(changed.result, changed.old, changed.new, formattingInline = false))

        assertTrue(unchangedRows, unchangedRows.contains("method twice()  usages renamed"))
        assertTrue(changedRows, changedRows.contains("method twice()  changed"))
        assertFalse(changedRows, changedRows.contains("usages renamed"))
    }

    @Test
    fun `R10 usages from two renames in one declaration map both`() {
        // relationships/04-rename-grouping R10
        val old = "class Order {\n    int total() { return 1; }\n    int price() { return 2; }\n    int use() { return total() + price(); }\n}\n"
        val new = "class Order {\n    int sum() { return 1; }\n    int cost() { return 2; }\n    int use() { return sum() + cost(); }\n}\n"
        val oldTotal = old.indexOf("int total()")
        val newTotal = new.indexOf("int sum()")
        val oldPrice = old.indexOf("int price()")
        val newPrice = new.indexOf("int cost()")
        val oldUse = old.indexOf("int use()")
        val newUse = new.indexOf("int use()")
        val oldTotalSubject = Subject.Declaration(DeclarationKind.METHOD, "total", "")
        val newTotalSubject = Subject.Declaration(DeclarationKind.METHOD, "sum", "")
        val oldPriceSubject = Subject.Declaration(DeclarationKind.METHOD, "price", "")
        val newPriceSubject = Subject.Declaration(DeclarationKind.METHOD, "cost", "")
        val oldTotalUsage = TextRange(old.indexOf("total() + price()"), old.indexOf("total() + price()") + 5)
        val newTotalUsage = TextRange(new.indexOf("sum() + cost()"), new.indexOf("sum() + cost()") + 3)
        val oldPriceUsage = TextRange(old.lastIndexOf("price()"), old.lastIndexOf("price()") + 5)
        val newPriceUsage = TextRange(new.lastIndexOf("cost()"), new.lastIndexOf("cost()") + 4)
        val totalReference = Evidence.Reference(newTotalSubject, oldTotalUsage, newTotalUsage)
        val priceReference = Evidence.Reference(newPriceSubject, oldPriceUsage, newPriceUsage)
        val total = Relationship(
            java.util.EnumSet.of(RelationshipKind.RENAME),
            listOf(RelationshipEndpoint(oldTotalSubject, TextRange(oldTotal, old.indexOf("}", oldTotal) + 1))),
            listOf(RelationshipEndpoint(newTotalSubject, TextRange(newTotal, new.indexOf("}", newTotal) + 1))),
            Confidence.HIGH,
            listOf(Evidence.Name("total", "sum"), totalReference, Evidence.Score(0.9)),
        )
        val price = Relationship(
            java.util.EnumSet.of(RelationshipKind.RENAME),
            listOf(RelationshipEndpoint(oldPriceSubject, TextRange(oldPrice, old.indexOf("}", oldPrice) + 1))),
            listOf(RelationshipEndpoint(newPriceSubject, TextRange(newPrice, new.indexOf("}", newPrice) + 1))),
            Confidence.HIGH,
            listOf(Evidence.Name("price", "cost"), priceReference, Evidence.Score(0.9)),
        )
        val sumNode = ChangeNode(
            newTotalSubject, TextRange(oldTotal, old.indexOf("}", oldTotal) + 1), TextRange(newTotal, new.indexOf("}", newTotal) + 1),
            Presence.MATCHED, renamedFrom = oldTotalSubject,
        )
        val costNode = ChangeNode(
            newPriceSubject, TextRange(oldPrice, old.indexOf("}", oldPrice) + 1), TextRange(newPrice, new.indexOf("}", newPrice) + 1),
            Presence.MATCHED, renamedFrom = oldPriceSubject,
        )
        val use = ChangeNode(
            Subject.Declaration(DeclarationKind.METHOD, "use", ""),
            TextRange(oldUse, old.length), TextRange(newUse, new.length), Presence.MATCHED, content = Content.CHANGED,
        )
        val order = ChangeNode(
            Subject.Declaration(DeclarationKind.CLASS, "Order", null),
            TextRange(0, old.length), TextRange(0, new.length), Presence.MATCHED, children = listOf(sumNode, costNode, use),
        )
        val result = ComparisonResult(EngineId("e"), "1", Status.ANALYZED, changes = listOf(order), relationships = listOf(total, price))

        val rows = render(OutlineModel.build(result, old, new, formattingInline = false))

        assertTrue(rows, rows.contains("method use()  usages renamed"))
        assertTrue(rows, rows.contains("method total → sum()  rename candidate, 1 usage"))
        assertTrue(rows, rows.contains("method price → cost()  rename candidate, 1 usage"))
    }

    @Test
    fun `R11 the rename row frames every usage and ChangeNode details stay untouched`() {
        // relationships/04-rename-grouping R11
        val fixture = usageFixture()

        val rename = flatten(OutlineModel.build(fixture.result, fixture.old, fixture.new, formattingInline = false))
            .single { it.title.contains("→") }

        assertEquals(listOf(RangePair(fixture.oldUsage, fixture.newUsage)), rename.details)
        assertTrue("the engine's nodes keep no detail", fixture.result.changes.flatMap { listOf(it) + it.children }.all { it.details.isEmpty() })
    }

    @Test
    fun `R8 a grouped usage is never hidden and stays counted`() {
        // relationships/04-rename-grouping R8; declarations/06-change-labels R11, R14
        val fixture = usageFixture()

        val rows = OutlineModel.build(fixture.result, fixture.old, fixture.new, formattingInline = false, hidden = ChangeType.entries.toSet())

        assertTrue(flatten(rows).any { it.title.contains("twice") })
        assertEquals("1 changed · 1 rename candidate · 1 usages renamed · 1 hidden", OutlineModel.summary(fixture.result, ChangeType.entries.toSet(), fixture.old, fixture.new))
        assertEquals("a grouped usage is not hidden", 1, OutlineModel.hiddenCount(fixture.result, ChangeType.entries.toSet()))
    }

    private data class UsageFixture(
        val old: String,
        val new: String,
        val result: ComparisonResult,
        val oldUsage: TextRange,
        val newUsage: TextRange,
    )

    /**
     * A class with `total` renamed to `sum`, one pure usage in `twice`, and nodes and a relationship that match it. The
     * options change the text so a test can check what keeps and what loses `usages renamed`.
     */
    private fun usageFixture(
        oldComment: String = "",
        newComment: String = "",
        extraNewChange: Boolean = false,
        recursiveUsage: Boolean = false,
        leadingWhitespace: Boolean = false,
        oldLiteral: String? = null,
        newLiteral: String? = null,
    ): UsageFixture {
        val oldCommentLine = if (oldComment.isEmpty()) "" else "    // $oldComment\n"
        val newCommentLine = if (newComment.isEmpty()) "" else "    // $newComment\n"
        val oldLiteralCode = oldLiteral?.let { " + \"$it\".length()" }.orEmpty()
        val newLiteralCode = newLiteral?.let { " + \"$it\".length()" }.orEmpty()
        val old = "class Order {\n    int total() { return 2; }\n$oldCommentLine    int twice() { return total() * 2$oldLiteralCode; }\n}\n"
        val newChange = if (extraNewChange) " + 1" else ""
        val new = "class Order {\n    int sum() { return 2; }\n$newCommentLine    int twice() { return sum() * 2$newChange$newLiteralCode; }\n}\n"
        val oldSumStart = old.indexOf("int total()")
        val oldSumEnd = old.indexOf("}", oldSumStart) + 1
        val newSumStart = new.indexOf("int sum()")
        val newSumEnd = new.indexOf("}", newSumStart) + 1
        val oldTwiceStart = if (leadingWhitespace) old.indexOf("\n    int twice()") + 1 else old.indexOf("int twice()")
        val newTwiceStart = if (leadingWhitespace) new.indexOf("\n    int twice()") + 1 else new.indexOf("int twice()")
        val oldUsage = TextRange(old.indexOf("total() * 2"), old.indexOf("total() * 2") + "total".length)
        val newUsage = TextRange(new.indexOf("sum() * 2"), new.indexOf("sum() * 2") + "sum".length)
        val oldSubject = Subject.Declaration(DeclarationKind.METHOD, "total", "")
        val newSubject = Subject.Declaration(DeclarationKind.METHOD, "sum", "")
        val sum = ChangeNode(
            newSubject, TextRange(oldSumStart, oldSumEnd), TextRange(newSumStart, newSumEnd), Presence.MATCHED,
            renamedFrom = oldSubject,
        )
        val twice = ChangeNode(
            Subject.Declaration(DeclarationKind.METHOD, "twice", ""),
            TextRange(oldTwiceStart, old.length), TextRange(newTwiceStart, new.length), Presence.MATCHED, content = Content.CHANGED,
        )
        val order = ChangeNode(
            Subject.Declaration(DeclarationKind.CLASS, "Order", null),
            TextRange(0, old.length), TextRange(0, new.length), Presence.MATCHED, content = Content.CHANGED,
            children = listOf(sum, twice),
        )
        val references = mutableListOf(Evidence.Reference(newSubject, oldUsage, newUsage))
        if (recursiveUsage) references += Evidence.Reference(newSubject, TextRange(oldSumStart, oldSumStart + 5), TextRange(newSumStart, newSumStart + 3))
        references.sortWith(compareBy({ it.oldRange.start }, { it.newRange.start }))
        val relationship = Relationship(
            kinds = java.util.EnumSet.of(RelationshipKind.RENAME),
            old = listOf(RelationshipEndpoint(oldSubject, TextRange(oldSumStart, oldSumEnd))),
            new = listOf(RelationshipEndpoint(newSubject, TextRange(newSumStart, newSumEnd))),
            confidence = Confidence.HIGH,
            evidence = listOf(Evidence.Name("total", "sum")) + references + Evidence.Score(0.9),
        )
        return UsageFixture(
            old, new,
            ComparisonResult(EngineId("e"), "1", Status.ANALYZED, changes = listOf(order), relationships = listOf(relationship)),
            oldUsage, newUsage,
        )
    }

    @Test
    fun `extractions name both ends, qualified by container only when it differs`() {
        // declarations/06-change-labels R5, R7
        val summaryOld = TextRange(10, 50)
        val shippingOld = TextRange(60, 90)
        val summary = node("summary", old = summaryOld, content = Content.CHANGED).copy(
            extractedTo = listOf(method("describe")), details = listOf(RangePair(TextRange(20, 30), null)),
        )
        val describe = node("describe", presence = Presence.ADDED, old = null).copy(
            extractedFrom = listOf(Origin(method("summary"), summaryOld, TextRange(20, 30))),
        )
        val shipping = node("shippingCost", old = shippingOld, content = Content.CHANGED).copy(extractedTo = listOf(method("costFor")))
        val costFor = node("costFor", presence = Presence.ADDED, old = null).copy(
            extractedFrom = listOf(Origin(method("shippingCost"), shippingOld, TextRange(62, 88))),
        )
        val result = result(
            node("Service", children = listOf(summary, describe, shipping)),
            node("Rates", presence = Presence.ADDED, old = null, children = listOf(costFor)),
        )

        val rows = OutlineModel.build(result, "", "", formattingInline = false)

        assertEquals(
            """
            class Service
              method summary()  changed, code extracted to describe()
              method describe()  added, extracted from summary()
              method shippingCost()  changed, code extracted to Rates.costFor()
            class Rates  added
              method costFor()  added, extracted from Service.shippingCost()
            """.trimIndent(),
            render(rows),
        )
        // The extraction frames the extracted code on the old side; the source highlights it in its details.
        assertEquals(TextRange(20, 30), rows[0].children[1].oldRange)
        assertEquals(TextRange(62, 88), rows[1].children[0].oldRange)
        assertEquals(listOf(RangePair(TextRange(20, 30), null)), rows[0].children[0].details)
        assertEquals("2 changed · 2 extracted · 1 added", OutlineModel.summary(result))
    }

    @Test
    fun `an extraction is hidden with added changes and a note lists at most three names`() {
        val sources = (1..5).map { i -> node("s$i", old = TextRange(i * 10, i * 10 + 5), content = Content.CHANGED) }
        val helper = node("helper", presence = Presence.ADDED, old = null).copy(
            extractedFrom = sources.map { Origin(it.subject, it.oldRange!!, it.oldRange!!) },
        )
        val result = result(node("A", children = sources + helper))

        val shown = render(OutlineModel.build(result, "", "", formattingInline = false))
        val hidden = render(OutlineModel.build(result, "", "", formattingInline = false, hidden = setOf(ChangeType.ADDED)))

        assertTrue(shown, shown.contains("method helper()  added, extracted from s1(), s2(), s3(), +2 more"))
        assertTrue(hidden, !hidden.contains("helper"))
        assertEquals("5 changed · 1 extracted · 1 hidden", OutlineModel.summary(result, setOf(ChangeType.ADDED)))
    }

    private fun method(name: String) = Subject.Declaration(DeclarationKind.METHOD, name, "")

    private fun result(vararg changes: ChangeNode) = ComparisonResult(EngineId("e"), "1", Status.ANALYZED, changes = changes.toList())

    private fun node(
        name: String,
        presence: Presence = Presence.MATCHED,
        old: TextRange? = TextRange(0, 1),
        new: TextRange? = TextRange(0, 1),
        moved: Boolean = false,
        signatureChanged: Boolean = false,
        content: Content = Content.UNCHANGED,
        candidates: List<Candidate> = emptyList(),
        children: List<ChangeNode> = emptyList(),
        renamedFrom: Subject? = null,
    ): ChangeNode {
        val kind = if (name.first().isUpperCase()) DeclarationKind.CLASS else DeclarationKind.METHOD
        val signature = if (kind == DeclarationKind.METHOD) "" else null
        return ChangeNode(Subject.Declaration(kind, name, signature), old, new, presence, moved, signatureChanged, content, candidates, children = children, renamedFrom = renamedFrom)
    }

    private fun flatten(items: List<OutlineItem>): List<OutlineItem> = items.flatMap { listOf(it) + flatten(it.children) }

    private fun render(rows: List<OutlineItem>): String = buildString {
        fun visit(item: OutlineItem, depth: Int) {
            appendLine("  ".repeat(depth) + item.label)
            item.children.forEach { visit(it, depth + 1) }
        }
        rows.forEach { visit(it, 0) }
    }.trimEnd()
}
