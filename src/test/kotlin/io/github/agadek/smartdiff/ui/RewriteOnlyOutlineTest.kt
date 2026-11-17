package io.github.agadek.smartdiff.ui

import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.EquivalenceRule
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.JAVA_NUMERIC_LITERALS
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.JAVA_REDUNDANT_PARENTHESES
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.KOTLIN_TEMPLATE_BRACES
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.SCALA_REDUNDANT_PARENTHESES
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rewrite-only labels, filter and summary in the outline, without the IDE (declarations/08). */
class RewriteOnlyOutlineTest {

    private val all = EquivalenceRule.entries.toSet()

    private val result = ComparisonResult(
        EngineId("e"), "1", Status.ANALYZED,
        fileRegion = ChangeNode(
            Subject.Region("file"), TextRange(0, 1), TextRange(0, 1), Presence.MATCHED, content = Content.CHANGED,
            rewriteRules = setOf(JAVA_NUMERIC_LITERALS),
        ),
        changes = listOf(
            node("hex", setOf(JAVA_NUMERIC_LITERALS)),
            node("relocated", setOf(JAVA_REDUNDANT_PARENTHESES), moved = true),
            node("both", setOf(JAVA_NUMERIC_LITERALS, JAVA_REDUNDANT_PARENTHESES)),
            node("fixed"),
            node("documented", commentOnly = true),
            node("tidied", content = Content.FORMATTING_ONLY),
        ),
    )

    // declarations/08-equivalence-rules R1
    @Test
    fun `with the option off the outline shows changed as before`() {
        val plain = result.copy(
            fileRegion = result.fileRegion!!.copy(rewriteRules = emptySet()),
            changes = result.changes.map { it.copy(rewriteRules = emptySet()) },
        )
        assertEquals(render(OutlineModel.build(plain, "", "", formattingInline = true)), render(OutlineModel.build(result, "", "", formattingInline = true)))
        assertEquals(OutlineModel.summary(plain), OutlineModel.summary(result))
        assertEquals("6 changed · 1 moved · 1 formatting-only", OutlineModel.summary(result))
    }

    // declarations/08-equivalence-rules R4, R8, R9
    @Test
    fun `with the option on rewrite-only replaces changed and keeps the other labels`() {
        assertEquals(
            """
            File region  rewrite-only
            method hex()  rewrite-only
            method relocated()  moved, rewrite-only
            method both()  rewrite-only
            method fixed()  changed
            method documented()  changed
            method tidied()  formatting-only
            """.trimIndent(),
            render(OutlineModel.build(result, "", "", formattingInline = true, rewriteOnly = all)),
        )
    }

    // declarations/08-equivalence-rules R7
    @Test
    fun `a node that needs a disabled rule stays changed`() {
        val rows = render(OutlineModel.build(result, "", "", formattingInline = true, rewriteOnly = setOf(JAVA_NUMERIC_LITERALS)))
        assertTrue(rows, "method hex()  rewrite-only" in rows)
        assertTrue(rows, "method relocated()  moved, changed" in rows)
        assertTrue(rows, "method both()  changed" in rows)
    }

    // declarations/09-kotlin-scala-equivalence-rules R3
    @Test
    fun `a disabled Kotlin rule keeps a Kotlin row changed and leaves Java and Scala rows rewrite-only`() {
        val mixed = result.copy(
            fileRegion = null,
            changes = listOf(
                node("java", setOf(JAVA_REDUNDANT_PARENTHESES)),
                node("kotlin", setOf(KOTLIN_TEMPLATE_BRACES)),
                node("scala", setOf(SCALA_REDUNDANT_PARENTHESES)),
            ),
        )
        val rows = render(OutlineModel.build(mixed, "", "", formattingInline = true, rewriteOnly = all - KOTLIN_TEMPLATE_BRACES))
        assertEquals(
            """
            method java()  rewrite-only
            method kotlin()  changed
            method scala()  rewrite-only
            """.trimIndent(),
            rows,
        )
    }

    // declarations/08-equivalence-rules R15
    @Test
    fun `the summary counts rewrite-only after comment-only`() {
        assertEquals(
            "1 changed · 1 moved · 1 formatting-only · 1 comment-only · 4 rewrite-only",
            OutlineModel.summary(result, markCommentOnly = true, rewriteOnly = all),
        )
    }

    // declarations/08-equivalence-rules R13
    @Test
    fun `the filter hides rewrite-only changes while the option is on`() {
        val hidden = setOf(ChangeType.REWRITE_ONLY)
        assertEquals(
            """
            method relocated()  moved, rewrite-only
            method fixed()  changed
            method documented()  changed
            method tidied()  formatting-only
            """.trimIndent(),
            render(OutlineModel.build(result, "", "", formattingInline = true, hidden = hidden, rewriteOnly = all)),
        )
        assertEquals(3, OutlineModel.hiddenCount(result, hidden, rewriteOnly = all))
        assertTrue(OutlineModel.summary(result, hidden, rewriteOnly = all).endsWith("· 3 hidden"))
    }

    // declarations/08-equivalence-rules R14
    @Test
    fun `a hidden rewrite-only type hides nothing while the option is off`() {
        val hidden = setOf(ChangeType.REWRITE_ONLY)
        assertEquals(
            render(OutlineModel.build(result, "", "", formattingInline = true)),
            render(OutlineModel.build(result, "", "", formattingInline = true, hidden = hidden)),
        )
        assertEquals(0, OutlineModel.hiddenCount(result, hidden))
    }

    // declarations/08-equivalence-rules R13, R14; the Changes tree and the merge outline are out of scope
    @Test
    fun `only the outline offers the rewrite-only type, and only while the option is on`() {
        assertTrue(ChangeType.REWRITE_ONLY in ChangeType.forOutline(markCommentOnly = false, markRewriteOnly = true))
        assertFalse(ChangeType.REWRITE_ONLY in ChangeType.forOutline(markCommentOnly = true, markRewriteOnly = false))
        assertFalse(ChangeType.REWRITE_ONLY in ChangeType.forOutline(markCommentOnly = true))
        assertFalse(ChangeType.REWRITE_ONLY in ChangeType.withoutCommentOnly)
    }

    // declarations/08-equivalence-rules R16
    @Test
    fun `the refactorings view shows rewrite-only in its other changes`() {
        val other = RefactoringsModel.build(result, "", "", formattingInline = true, rewriteOnly = all)
            .single { it.title == RefactoringsModel.OTHER_CHANGES }.children
        assertEquals(listOf(Tag.REWRITE_ONLY), other.single { it.title == "method hex()" }.tags)
    }

    // declarations/08-equivalence-rules R4: the engine's content stays changed
    @Test
    fun `rewrite-only is a label, not a classification`() {
        assertEquals(Content.CHANGED, result.changes.first().content)
    }

    private fun node(
        name: String,
        rewriteRules: Set<EquivalenceRule> = emptySet(),
        content: Content = Content.CHANGED,
        commentOnly: Boolean = false,
        moved: Boolean = false,
    ) = ChangeNode(
        Subject.Declaration(DeclarationKind.METHOD, name, ""), TextRange(0, 1), TextRange(0, 1), Presence.MATCHED,
        moved = moved, content = content, commentOnly = commentOnly, rewriteRules = rewriteRules,
    )

    private fun render(rows: List<OutlineItem>): String = buildString {
        fun visit(item: OutlineItem, depth: Int) {
            appendLine("  ".repeat(depth) + item.label)
            item.children.forEach { visit(it, depth + 1) }
        }
        rows.forEach { visit(it, 0) }
    }.trimEnd()
}
