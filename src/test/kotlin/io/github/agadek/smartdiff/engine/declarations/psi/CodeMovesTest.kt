@file:Suppress("DEPRECATION")

package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.declarations.analysis.CodeMoveDetector
import io.github.agadek.smartdiff.ui.ChangeType
import io.github.agadek.smartdiff.ui.HiddenLines
import io.github.agadek.smartdiff.ui.ItemKind
import io.github.agadek.smartdiff.ui.MovedCodeMarkers
import io.github.agadek.smartdiff.ui.OutlineItem
import io.github.agadek.smartdiff.ui.OutlineModel
import io.github.agadek.smartdiff.ui.RefactoringsModel
import io.github.agadek.smartdiff.ui.Tag

/** Code moves found by the declaration engine and shown by the outline (relationships/08-moves-inside-declarations). */
class CodeMovesTest : BasePlatformTestCase() {

    fun `test R2 R3 R12 a block reordered in one method is one code move`() {
        // relationships/08-moves-inside-declarations R2, R3, R11, R12
        val result = compare(REORDER_OLD, REORDER_NEW)
        val move = codeMoves(result).single()
        assertEquals(Confidence.HIGH, move.confidence)
        assertEquals("both ends name process", move.old.single().subject, move.new.single().subject)
        assertEquals("process", (move.new.single().subject as Subject.Declaration).name)
        val process = node(result, "process")
        assertEquals(1, process.codeMoves.size)
        assertTrue("a move inside one declaration lists no other target", process.codeMovedTo.isEmpty())
        val row = codeMoveRows(OutlineModel.build(result, REORDER_OLD, REORDER_NEW, formattingInline = true)).single()
        assertEquals("moved code lines 9–11 → 3–5", row.title)
        assertEquals(listOf(Tag.MOVED), row.tags)
        assertTrue("a code move row gets moved-code markers", row.moved)
    }

    fun `test R13 a block moved between methods notes both ends`() {
        // relationships/08-moves-inside-declarations R3, R13, R16
        val result = compare(BETWEEN_OLD, BETWEEN_NEW)
        val move = codeMoves(result).single()
        assertEquals("process", (move.old.single().subject as Subject.Declaration).name)
        assertEquals("finish", (move.new.single().subject as Subject.Declaration).name)
        val rows = OutlineModel.build(result, BETWEEN_OLD, BETWEEN_NEW, formattingInline = true)
        val all = flatten(rows)
        val process = all.single { it.kind == ItemKind.CHANGE && it.title.startsWith("method process") }
        assertTrue("the source notes where its code went: ${process.label}", process.note.orEmpty().contains("code moved to finish()"))
        val row = codeMoveRows(rows).single()
        assertEquals("from process(List<Order>)", row.note)
        assertEquals("moved code process → finish", RefactoringsModel.title(move))
    }

    fun `test R11 R14 a moved block with an edit is medium and changed`() {
        // relationships/08-moves-inside-declarations R11, R14, R15
        val new = REORDER_NEW.replace("\"invalid order \"", "\"order is invalid: \"")
        val result = compare(REORDER_OLD, new)
        val move = codeMoves(result).single()
        assertEquals(Confidence.MEDIUM, move.confidence)
        val codeMove = node(result, "process").codeMoves.single()
        assertEquals(Content.CHANGED, codeMove.content)
        assertTrue("the edit inside the move is highlighted", codeMove.changedRuns.isNotEmpty())
        val row = codeMoveRows(OutlineModel.build(result, REORDER_OLD, new, formattingInline = true)).single()
        assertEquals(listOf(Tag.MOVED, Tag.CHANGED), row.tags)
    }

    fun `test R1 with the setting off the result has no code moves and is otherwise the same`() {
        // relationships/08-moves-inside-declarations R1, R19
        val on = compare(BETWEEN_OLD, BETWEEN_NEW)
        val off = compare(BETWEEN_OLD, BETWEEN_NEW, detect = false)
        assertTrue(codeMoves(off).isEmpty())
        assertEquals(on.relationships.filter { RelationshipKind.CODE_MOVE !in it.kinds }, off.relationships)
        assertEquals("only the code move fields differ", strip(off.changes), strip(on.changes))
        assertEquals(off.changes, strip(off.changes))
    }

    fun `test R20 the summary line does not count code moves`() {
        // relationships/08-moves-inside-declarations R20
        val on = compare(BETWEEN_OLD, BETWEEN_NEW)
        val off = compare(BETWEEN_OLD, BETWEEN_NEW, detect = false)
        assertEquals(OutlineModel.summary(off), OutlineModel.summary(on))
    }

    fun `test R17 the change type filter hides a move without edits and keeps one with edits`() {
        // relationships/08-moves-inside-declarations R17
        val plain = compare(REORDER_OLD, REORDER_NEW)
        assertTrue(codeMoveRows(OutlineModel.build(plain, REORDER_OLD, REORDER_NEW, formattingInline = true, hidden = setOf(ChangeType.MOVED))).isEmpty())
        val edited = REORDER_NEW.replace("\"invalid order \"", "\"order is invalid: \"")
        val withEdit = compare(REORDER_OLD, edited)
        assertEquals(1, codeMoveRows(OutlineModel.build(withEdit, REORDER_OLD, edited, formattingInline = true, hidden = setOf(ChangeType.MOVED))).size)
    }

    fun `test R18 a code move gets markers on both sides`() {
        // relationships/08-moves-inside-declarations R18
        val result = compare(REORDER_OLD, REORDER_NEW)
        val targets = MovedCodeMarkers.targets(OutlineModel.build(result, REORDER_OLD, REORDER_NEW, formattingInline = true))
        val move = node(result, "process").codeMoves.single()
        assertTrue(targets.any { it.oldRange == move.oldRange && it.newRange == move.newRange })
    }

    fun `test R28 hiding a code move row fades no lines`() {
        // relationships/08-moves-inside-declarations R28
        val result = compare(REORDER_OLD, REORDER_NEW)
        assertEquals(
            HiddenLines.of(result.copy(relationships = emptyList(), changes = strip(result.changes)), setOf(ChangeType.MOVED), REORDER_OLD, REORDER_NEW),
            HiddenLines.of(result, setOf(ChangeType.MOVED), REORDER_OLD, REORDER_NEW),
        )
    }

    fun `test R4 a short statement moved alone is no code move`() {
        // relationships/08-moves-inside-declarations R4
        val old = REORDER_OLD.replace(VALID, "        audit.log(order);\n")
        val new = REORDER_NEW.replace(VALID, "        audit.log(order);\n")
        assertTrue(codeMoves(compare(old, new)).isEmpty())
    }

    fun `test R6 a block left in place and copied elsewhere is no code move`() {
        // relationships/08-moves-inside-declarations R6, R9
        val duplicated = REORDER_NEW.replace("        repository.save(order);\n", "        repository.save(order);\n$VALID")
        assertTrue(codeMoves(compare(REORDER_OLD, duplicated)).isEmpty())
    }

    fun `test R10 a block extracted to a new method is no code move`() {
        // relationships/08-moves-inside-declarations R9, R10
        val new = "class OrderService {\n    void process(Order order) {\n" + REST + "        validate(order);\n    }\n\n" +
            "    void validate(Order order) {\n" + VALID + "    }\n}\n"
        assertTrue(codeMoves(compare(REORDER_OLD, new)).isEmpty())
    }

    fun `test equal requests give equal code moves`() {
        // relationships/08-moves-inside-declarations, The algorithm: determinism (relationships/02 R19)
        assertEquals(compare(BETWEEN_OLD, BETWEEN_NEW), compare(BETWEEN_OLD, BETWEEN_NEW))
    }

    fun `test R22 a language without statements gives no code moves`() {
        // relationships/08-moves-inside-declarations R22
        val old = "{\n  \"a\": [1, 2, 3, 4, 5, 6, 7, 8],\n  \"b\": [9, 10, 11, 12, 13, 14, 15, 16]\n}\n"
        val new = "{\n  \"a\": [9, 10, 11, 12, 13, 14, 15, 16],\n  \"b\": [1, 2, 3, 4, 5, 6, 7, 8]\n}\n"
        val result = DeclarationEngine(project).compare(ComparisonRequest(old, new, "JSON", "a.json"), Cancellation.NONE)
        assertTrue(codeMoves(result).isEmpty())
    }

    fun `test R4 a moved statement of 11 tokens is no code move and one of 12 is`() {
        // relationships/08-moves-inside-declarations R4
        val eleven = "        audit.log(order, user, id);\n"
        assertTrue(codeMoves(compare(REORDER_OLD.replace(VALID, eleven), REORDER_NEW.replace(VALID, eleven))).isEmpty())
        val twelve = "        audit.log(order.id(), user);\n"
        assertEquals(1, codeMoves(compare(REORDER_OLD.replace(VALID, twelve), REORDER_NEW.replace(VALID, twelve))).size)
    }

    fun `test R5 a statement at similarity 0_8 is part of a move and one below is not`() {
        // relationships/08-moves-inside-declarations R5. The first statement has 11 tokens, so it is no move alone (R4).
        val first = "        double rate = order.rate() * factor;\n"
        // 10 of its adjacent token pairs, 8 shared: 0.8.
        val atOld = first + "        audit.log(order, user, id);\n"
        val atNew = first + "        audit.log(order, admin, id);\n"
        val at = compare(REORDER_OLD.replace(VALID, atOld), REORDER_NEW.replace(VALID, atNew))
        val move = codeMoves(at).single()
        assertEquals(0.8, move.evidence.filterIsInstance<Evidence.Score>().single().value, 1e-9)
        assertEquals(Confidence.MEDIUM, move.confidence)
        // 9 adjacent token pairs, 7 shared: 0.78.
        val belowOld = first + "        audit.log(order, -id);\n"
        val belowNew = first + "        audit.log(user, -id);\n"
        assertTrue(codeMoves(compare(REORDER_OLD.replace(VALID, belowOld), REORDER_NEW.replace(VALID, belowNew))).isEmpty())
    }

    fun `test R7 a statement inside a moved statement is part of its move`() {
        // relationships/08-moves-inside-declarations R7
        val result = compare(REORDER_OLD, REORDER_NEW)
        val move = node(result, "process").codeMoves.single()
        assertEquals("the move starts at the if", REORDER_OLD.indexOf("if (order.customer()"), move.oldRange.start)
        val inner = REORDER_OLD.indexOf("throw new")
        assertTrue("the throw inside it is part of it", inner > move.oldRange.start && inner < move.oldRange.end)
        assertEquals("no move of its own", 1, codeMoves(result).size)
    }

    fun `test R8 two blocks moved to two places are two moves that share no statement`() {
        // relationships/08-moves-inside-declarations R2, R8
        val audit = "        audit.log(order.id(), user);\n"
        val old = "class OrderService {\n    void process(Order order) {\n" + REST + VALID + audit + "    }\n\n" +
            "    void finish() {\n        writer.close();\n    }\n}\n"
        val new = "class OrderService {\n    void process(Order order) {\n" + VALID + REST + "    }\n\n" +
            "    void finish() {\n" + audit + "        writer.close();\n    }\n}\n"
        val moves = codeMoves(compare(old, new)).map { it.evidence.filterIsInstance<Evidence.Content>().single() }
        assertEquals(2, moves.size)
        val (a, b) = moves
        assertTrue("old sides apart", a.oldRange.end <= b.oldRange.start || b.oldRange.end <= a.oldRange.start)
        assertTrue("new sides apart", a.newRange.end <= b.newRange.start || b.newRange.end <= a.newRange.start)
    }

    fun `test R9 code moved out of a removed method or into an added one is no code move`() {
        // relationships/08-moves-inside-declarations R9
        val removedOld = "class OrderService {\n    void process(Order order) {\n" + REST + "    }\n\n" +
            "    void legacy(Order order) {\n" + VALID + "    }\n}\n"
        val removedNew = "class OrderService {\n    void process(Order order) {\n" + VALID + REST + "    }\n}\n"
        assertTrue(codeMoves(compare(removedOld, removedNew)).isEmpty())
        val addedNew = "class OrderService {\n    void process(Order order) {\n" + REST + "    }\n\n" +
            "    void check(Order order) {\n" + VALID + "    }\n}\n"
        assertTrue(codeMoves(compare(REORDER_OLD, addedNew)).isEmpty())
    }

    fun `test R21 more than 2 000 removed statements give no code moves`() {
        // relationships/08-moves-inside-declarations R21
        fun methods(count: Int, swapped: Boolean) = buildString {
            append("class Big {\n")
            for (i in 0 until count) {
                val save = "        repository$i.save$i(order$i, total$i, now$i, user$i, extra$i);\n"
                val log = "        audit$i.log$i(order$i, user$i, id$i, at$i);\n"
                append("    void m$i() {\n").append(if (swapped) save + log else log + save).append("    }\n")
            }
            append("}\n")
        }
        assertEquals("one move per method below the limit", 3, codeMoves(compare(methods(3, false), methods(3, true))).size)
        assertTrue(codeMoves(compare(methods(2_001, false), methods(2_001, true))).isEmpty())
    }

    fun `test R25 a declaration over the token comparison's limit gives no code moves`() {
        // relationships/08-moves-inside-declarations R25, declarations/10 R10
        fun filler(count: Int) = (0 until count).joinToString("") { "        f$it.g$it(a$it, b$it, c$it);\n" }
        fun pair(count: Int) = REORDER_OLD.replace(REST, REST + filler(count)) to REORDER_NEW.replace(REST, REST + filler(count))
        val (smallOld, smallNew) = pair(10)
        assertEquals(1, codeMoves(compare(smallOld, smallNew)).size)
        val (bigOld, bigNew) = pair(2_000)
        assertTrue(codeMoves(compare(bigOld, bigNew)).isEmpty())
    }

    fun `test a failure in code move detection gives no code moves and the same result otherwise`() {
        // relationships/08-moves-inside-declarations, Failure
        val off = compare(BETWEEN_OLD, BETWEEN_NEW, detect = false)
        CodeMoveDetector.failureForTests = IllegalStateException("code move detection failed")
        try {
            assertEquals(off, compare(BETWEEN_OLD, BETWEEN_NEW))
        } finally {
            CodeMoveDetector.failureForTests = null
        }
    }

    private fun compare(old: String, new: String, detect: Boolean = true): ComparisonResult =
        DeclarationEngine(project).compare(ComparisonRequest(old, new, "JAVA", "OrderService.java", detectCodeMoves = detect), Cancellation.NONE)

    private fun codeMoves(result: ComparisonResult) = result.relationships.filter { RelationshipKind.CODE_MOVE in it.kinds }.also { moves ->
        moves.forEach { move ->
            val content = move.evidence.filterIsInstance<Evidence.Content>().single()
            // The engine contract's rule for code moves: the moved code lies inside its endpoints.
            assertTrue(content.oldRange.start >= move.old.single().range.start && content.oldRange.end <= move.old.single().range.end)
            assertTrue(content.newRange.start >= move.new.single().range.start && content.newRange.end <= move.new.single().range.end)
        }
    }

    private fun node(result: ComparisonResult, name: String): ChangeNode {
        fun find(nodes: List<ChangeNode>): ChangeNode? = nodes.firstNotNullOfOrNull { node ->
            if ((node.subject as? Subject.Declaration)?.name == name) node else find(node.children)
        }
        return find(result.changes) ?: error("no node $name")
    }

    private fun strip(nodes: List<ChangeNode>): List<ChangeNode> =
        nodes.map { it.copy(codeMoves = emptyList(), codeMovedTo = emptyList(), children = strip(it.children)) }

    private fun flatten(rows: List<OutlineItem>): List<OutlineItem> = rows.flatMap { listOf(it) + flatten(it.children) }

    private fun codeMoveRows(rows: List<OutlineItem>) = flatten(rows).filter { it.kind == ItemKind.CODE_MOVE }

    private companion object {
        const val VALID = "        if (order.customer() == null || order.items().isEmpty()) {\n" +
            "            throw new IllegalArgumentException(\"invalid order \" + order.id());\n" +
            "        }\n"
        const val REST = "        double total = 0;\n" +
            "        for (Item item : order.items()) {\n" +
            "            total += item.price() * item.count();\n" +
            "        }\n" +
            "        order.setTotal(total);\n" +
            "        repository.save(order);\n"
        const val REORDER_OLD = "class OrderService {\n    void process(Order order) {\n" + REST + VALID + "    }\n}\n"
        const val REORDER_NEW = "class OrderService {\n    void process(Order order) {\n" + VALID + REST + "    }\n}\n"

        val BETWEEN_OLD = """
            class Report {
                void process(List<Order> orders) {
                    double sum = 0;
                    for (Order order : orders) {
                        sum += order.total();
                    }
                    for (Order order : orders) {
                        logger.info("processed order " + order.id() + " for " + order.customer());
                    }
                    this.sum = sum;
                }

                void finish() {
                    writer.flush();
                    writer.close();
                }
            }
        """.trimIndent() + "\n"

        val BETWEEN_NEW = """
            class Report {
                void process(List<Order> orders) {
                    double sum = 0;
                    for (Order order : orders) {
                        sum += order.total();
                    }
                    this.sum = sum;
                }

                void finish() {
                    writer.flush();
                    for (Order order : orders) {
                        logger.info("processed order " + order.id() + " for " + order.customer());
                    }
                    writer.close();
                }
            }
        """.trimIndent() + "\n"
    }
}
