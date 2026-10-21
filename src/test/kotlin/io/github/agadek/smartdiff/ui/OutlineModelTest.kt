package io.github.agadek.smartdiff.ui

import io.github.agadek.smartdiff.engine.api.Candidate
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
import org.junit.Test

/** The outline rows and summary built from engine-neutral results (technical specification §10), without the IDE. */
class OutlineModelTest {

    @Test
    fun `titles name kind, name and signature, or a region's description`() {
        assertEquals("method parse(String,int)", OutlineModel.title(Subject.Declaration(DeclarationKind.METHOD, "parse", "String,int")))
        assertEquals("enum constant RED", OutlineModel.title(Subject.Declaration(DeclarationKind.ENUM_CONSTANT, "RED", null)))
        assertEquals("constructor(Int)", OutlineModel.title(Subject.Declaration(DeclarationKind.CONSTRUCTOR, "", "Int")))
        assertEquals("lines 3-4 changed", OutlineModel.title(Subject.Region("lines 3-4 changed")))
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
    fun `a change whose every label is hidden is left out, and the summary says how many are hidden`() {
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
    fun `every row's label is its title followed by its change labels`() {
        val item = OutlineModel.build(result(node("a", moved = true, content = Content.CHANGED)), "", "", formattingInline = false).single()

        assertEquals("method a()  moved, changed", item.label)
    }

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
    ): ChangeNode {
        val kind = if (name.first().isUpperCase()) DeclarationKind.CLASS else DeclarationKind.METHOD
        val signature = if (kind == DeclarationKind.METHOD) "" else null
        return ChangeNode(Subject.Declaration(kind, name, signature), old, new, presence, moved, signatureChanged, content, candidates, children = children)
    }

    private fun render(rows: List<OutlineItem>): String = buildString {
        fun visit(item: OutlineItem, depth: Int) {
            appendLine("  ".repeat(depth) + item.label)
            item.children.forEach { visit(it, depth + 1) }
        }
        rows.forEach { visit(it, 0) }
    }.trimEnd()
}
