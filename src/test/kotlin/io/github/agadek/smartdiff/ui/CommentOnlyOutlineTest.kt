package io.github.agadek.smartdiff.ui

import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comment-only labels, filter and summary in the outline, without the IDE (outline/11). */
class CommentOnlyOutlineTest {

    private val result = ComparisonResult(
        EngineId("e"), "1", Status.ANALYZED,
        fileRegion = ChangeNode(Subject.Region("file"), TextRange(0, 1), TextRange(0, 1), Presence.MATCHED, content = Content.CHANGED, commentOnly = true),
        changes = listOf(
            node("documented", commentOnly = true),
            node("relocated", commentOnly = true, moved = true),
            node("fixed"),
            node("tidied", content = Content.FORMATTING_ONLY),
        ),
    )

    // outline/11-comment-only-changes R1
    @Test
    fun `with the option off the outline shows changed as before`() {
        assertEquals(
            """
            File region  changed
            method documented()  changed
            method relocated()  moved, changed
            method fixed()  changed
            method tidied()  formatting-only
            """.trimIndent(),
            render(OutlineModel.build(result, "", "", formattingInline = true)),
        )
        assertEquals("4 changed · 1 moved · 1 formatting-only", OutlineModel.summary(result))
    }

    // outline/11-comment-only-changes R3, R6, R7
    @Test
    fun `with the option on comment-only replaces changed and keeps the other labels`() {
        assertEquals(
            """
            File region  comment-only
            method documented()  comment-only
            method relocated()  moved, comment-only
            method fixed()  changed
            method tidied()  formatting-only
            """.trimIndent(),
            render(OutlineModel.build(result, "", "", formattingInline = true, markCommentOnly = true)),
        )
    }

    // outline/11-comment-only-changes R10
    @Test
    fun `the summary counts comment-only after formatting-only`() {
        assertEquals("1 changed · 1 moved · 1 formatting-only · 3 comment-only", OutlineModel.summary(result, markCommentOnly = true))
    }

    // outline/11-comment-only-changes R8
    @Test
    fun `the filter hides comment-only changes while the option is on`() {
        val hidden = setOf(ChangeType.COMMENT_ONLY)
        assertEquals(
            """
            method relocated()  moved, comment-only
            method fixed()  changed
            method tidied()  formatting-only
            """.trimIndent(),
            render(OutlineModel.build(result, "", "", formattingInline = true, hidden = hidden, markCommentOnly = true)),
        )
        assertEquals(2, OutlineModel.hiddenCount(result, hidden, markCommentOnly = true))
        assertTrue(OutlineModel.summary(result, hidden, markCommentOnly = true).endsWith("· 2 hidden"))
    }

    // outline/11-comment-only-changes R9
    @Test
    fun `a hidden comment-only type hides nothing while the option is off`() {
        val hidden = setOf(ChangeType.COMMENT_ONLY)
        assertEquals(
            render(OutlineModel.build(result, "", "", formattingInline = true)),
            render(OutlineModel.build(result, "", "", formattingInline = true, hidden = hidden)),
        )
        assertEquals(0, OutlineModel.hiddenCount(result, hidden))
    }

    // outline/11-comment-only-changes R8, R9; the Changes tree and the merge outline are out of scope
    @Test
    fun `only the outline offers the comment-only type, and only while the option is on`() {
        assertTrue(ChangeType.COMMENT_ONLY in ChangeType.forOutline(markCommentOnly = true))
        assertFalse(ChangeType.COMMENT_ONLY in ChangeType.forOutline(markCommentOnly = false))
        assertFalse(ChangeType.COMMENT_ONLY in ChangeType.withoutCommentOnly)
    }

    // outline/11-comment-only-changes R11
    @Test
    fun `the refactorings view shows comment-only in its other changes`() {
        val other = RefactoringsModel.build(result, "", "", formattingInline = true, markCommentOnly = true)
            .single { it.title == RefactoringsModel.OTHER_CHANGES }.children
        val documented = other.single { it.title == "method documented()" }
        assertEquals(listOf(Tag.COMMENT_ONLY), documented.tags)
    }

    // outline/11-comment-only-changes R13: the engine's content stays changed
    @Test
    fun `comment-only is a label, not a classification`() {
        assertEquals(Content.CHANGED, result.changes.first().content)
    }

    private fun node(
        name: String,
        content: Content = Content.CHANGED,
        commentOnly: Boolean = false,
        moved: Boolean = false,
    ) = ChangeNode(
        Subject.Declaration(DeclarationKind.METHOD, name, ""), TextRange(0, 1), TextRange(0, 1), Presence.MATCHED,
        moved = moved, content = content, commentOnly = commentOnly,
    )

    private fun render(rows: List<OutlineItem>): String = buildString {
        fun visit(item: OutlineItem, depth: Int) {
            appendLine("  ".repeat(depth) + item.label)
            item.children.forEach { visit(it, depth + 1) }
        }
        rows.forEach { visit(it, 0) }
    }.trimEnd()
}
