package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

/** Links removed helpers to the changed declarations their code was inlined into (relationships/05). */
class InliningDetectionTest {

    @Test
    fun `a removed helper is linked to the changed caller it was inlined into`() {
        // relationships/05-inlining-detection R1, R2, R12, R19
        val old = tree {
            cls("A") {
                method("render", "Order,StringBuilder", "{ return heading(order, text); }")
                method("heading", "Order,StringBuilder", "{ $LOOP }")
            }
        }
        val new = tree {
            cls("A") { method("render", "Order,StringBuilder", "{ $LOOP return text.toString(); }") }
        }

        val result = compare(old, new)

        assertEquals("render(Order,StringBuilder) <- heading(Order,StringBuilder)\n", inlinings(result))
        val heading = result.node("heading")
        val render = result.node("render")
        assertEquals(Presence.REMOVED, heading.presence)
        assertEquals(Presence.MATCHED, render.presence)
        assertEquals(listOf("render(Order,StringBuilder)"), heading.inlinedInto.map { name(it.subject) })
        assertEquals(listOf("heading(Order,StringBuilder)"), render.inlinedFrom.map { name(it.subject) })
        val inlined = heading.inlinedInto.single()
        assertTrue(inlined.newRange.start >= render.newRange!!.start && inlined.newRange.end <= render.newRange!!.end)
        assertTrue(inlined.oldRange.start >= heading.oldRange!!.start && inlined.oldRange.end <= heading.oldRange!!.end)
        val relationship = result.relationships.single { RelationshipKind.INLINE in it.kinds }
        assertEquals(Confidence.HIGH, relationship.confidence)
    }

    @Test
    fun `the target is the changed declaration that gained the code`() {
        // relationships/05-inlining-detection R2, R15
        val old = tree {
            cls("A") {
                method("render", "Order,StringBuilder", "{ start(); return heading(order, text); }")
                method("heading", "Order,StringBuilder", "{ $LOOP }")
            }
        }
        val new = tree {
            cls("A") { method("render", "Order,StringBuilder", "{ start(); $LOOP return text.toString(); }") }
        }

        val result = compare(old, new)
        val relationship = result.relationships.single { RelationshipKind.INLINE in it.kinds }
        val target = new.all.single { it.name == "render" }

        assertEquals(target.range, relationship.new.single().range)
        assertEquals("the removed declaration keeps its presence", Presence.REMOVED, result.node("heading").presence)
        assertEquals("the target is not reclassified", Presence.MATCHED, result.node("render").presence)
    }

    @Test
    fun `code the target already had is not an inlining`() {
        // relationships/05-inlining-detection R3
        val old = tree {
            cls("A") {
                method("render", "Order,StringBuilder", "{ $LOOP }")
                method("heading", "Order,StringBuilder", "{ $LOOP }")
            }
        }
        val new = tree {
            cls("A") { method("render", "Order,StringBuilder", "{ $LOOP more(); }") }
        }

        assertEquals("", inlinings(compare(old, new)))
    }

    @Test
    fun `code from an unchanged declaration is not an inlining`() {
        // relationships/05-inlining-detection R3
        val old = tree {
            cls("A") {
                method("render", "Order,StringBuilder", "{ $LOOP }")
                method("heading", "Order,StringBuilder", "{ $LOOP }")
            }
        }
        val new = tree { cls("A") { method("render", "Order,StringBuilder", "{ $LOOP }") } }

        assertEquals("", inlinings(compare(old, new)))
    }

    @Test
    fun `reformatted inlined code is linked`() {
        // relationships/05-inlining-detection R4
        val old = tree {
            cls("A") {
                method("render", "Order,StringBuilder", "{ return heading(order, text); }")
                method("heading", "Order,StringBuilder", "{ $LOOP }")
            }
        }
        val new = tree {
            cls("A") {
                method(
                    "render", "Order,StringBuilder",
                    "{\n        ${LOOP.replace(" { ", "\n        {\n            ").replace("; }", ";\n        }")}\n        return text.toString();\n    }",
                )
            }
        }

        assertEquals("render(Order,StringBuilder) <- heading(Order,StringBuilder)\n", inlinings(compare(old, new)))
    }

    @Test
    fun `a few changed tokens inside the inlined code still link`() {
        // relationships/05-inlining-detection R4
        val old = tree {
            cls("A") {
                method("render", "Order,StringBuilder", "{ return heading(order, text); }")
                method("heading", "Order,StringBuilder", "{ $LOOP }")
            }
        }
        val new = tree {
            cls("A") {
                method("render", "Order,StringBuilder", "{ ${LOOP.replace("order.items()", "items")} return text.toString(); }")
            }
        }

        assertEquals("render(Order,StringBuilder) <- heading(Order,StringBuilder)\n", inlinings(compare(old, new)))
    }

    @Test
    fun `a removed body under ten tokens is not linked`() {
        // relationships/05-inlining-detection R5
        val old = tree {
            cls("A") {
                method("render", "int,int", "{ return heading(total, tax); }")
                method("heading", "int,int", "{ return total + tax; }")
            }
        }
        val new = tree {
            cls("A") { method("render", "int,int", "{ return total + tax; }") }
        }

        assertEquals("", inlinings(compare(old, new)))
    }

    @Test
    fun `one helper inlined into two callers links both`() {
        // relationships/05-inlining-detection R11
        val old = tree {
            cls("A") {
                method("render", "Order,StringBuilder", "{ return heading(order, text); }")
                method("notify", "Order,StringBuilder", "{ return heading(order, text); }")
                method("heading", "Order,StringBuilder", "{ $LOOP }")
            }
        }
        val new = tree {
            cls("A") {
                method("render", "Order,StringBuilder", "{ $LOOP return text.toString(); }")
                method("notify", "Order,StringBuilder", "{ $LOOP return text.toString(); }")
            }
        }

        val result = compare(old, new)

        assertEquals(2, result.relationships.count { RelationshipKind.INLINE in it.kinds })
        assertEquals(
            listOf("render(Order,StringBuilder)", "notify(Order,StringBuilder)"),
            result.node("heading").inlinedInto.map { name(it.subject) },
        )
    }

    @Test
    fun `two helpers inlined into one target link both`() {
        // relationships/05-inlining-detection R11
        val old = tree {
            cls("A") {
                method("render", "Order,StringBuilder", "{ return heading(order, text); }")
                method("heading", "Order,StringBuilder", "{ $LOOP }")
                method("notify", "Order,StringBuilder", "{ $OTHER }")
            }
        }
        val new = tree {
            cls("A") { method("render", "Order,StringBuilder", "{ $LOOP $OTHER return text.toString(); }") }
        }

        val result = compare(old, new)

        assertEquals(2, result.relationships.count { RelationshipKind.INLINE in it.kinds })
        assertEquals(2, result.node("render").inlinedFrom.size)
        assertEquals(
            listOf("heading(Order,StringBuilder)", "notify(Order,StringBuilder)"),
            result.node("render").inlinedFrom.map { name(it.subject) }.sorted(),
        )
    }

    @Test
    fun `an inlining is its own relationship when the target moved`() {
        // relationships/05-inlining-detection R20
        val old = tree {
            cls("A") {
                method("render", "Order,StringBuilder", "{ return heading(order, text); }")
                method("first", "Order", "{ return order; }")
                method("second", "Order", "{ return order; }")
                method("heading", "Order,StringBuilder", "{ $LOOP }")
            }
        }
        val new = tree {
            cls("A") {
                method("first", "Order", "{ return order; }")
                method("second", "Order", "{ return order; }")
                method("render", "Order,StringBuilder", "{ $LOOP return text.toString(); }")
            }
        }

        val result = compare(old, new)
        val render = result.node("render")

        assertTrue(render.moved)
        assertTrue(result.relationships.any { RelationshipKind.MOVE in it.kinds })
        assertEquals("render(Order,StringBuilder)", result.node("heading").inlinedInto.single().let { name(it.subject) })
    }

    @Test
    fun `detection is skipped above the work limit`() {
        // relationships/05-inlining-detection R17
        val old = tree {
            cls("A") {
                method("render", "Order,StringBuilder", "{ return heading(order, text); }")
                method("heading", "Order,StringBuilder", "{ $LOOP }")
            }
        }
        val new = tree { cls("A") { method("render", "Order,StringBuilder", "{ $LOOP return text.toString(); }") } }
        val matching = DeclarationMatcher.match(old, new, Cancellation.NONE)

        val found = ExtractionDetector.detectInliningLinks(matching, { true }, Cancellation.NONE)
        val skipped = ExtractionDetector.detectInliningLinks(matching, { true }, Cancellation.NONE, maxWork = 100)

        assertEquals(1, found.size)
        assertTrue(skipped.isEmpty())
        assertEquals(Status.ANALYZED, compare(old, new).status)
    }

    @Test(expected = CancellationException::class)
    fun `detection checks cancellation`() {
        // relationships/05-inlining-detection R17
        val old = tree {
            cls("A") {
                method("render", "Order,StringBuilder", "{ return heading(order, text); }")
                method("heading", "Order,StringBuilder", "{ $LOOP }")
            }
        }
        val new = tree { cls("A") { method("render", "Order,StringBuilder", "{ $LOOP return text.toString(); }") } }
        val matching = DeclarationMatcher.match(old, new, Cancellation.NONE)

        ExtractionDetector.detectInlinings(matching, { true }, Cancellation { throw CancellationException() })
    }

    @Test
    fun `declarations without token offsets take no part`() {
        // relationships/05-inlining-detection R16
        val old = tree {
            cls("A") {
                method("render", "Order,StringBuilder", "{ return heading(order, text); }")
                method("heading", "Order,StringBuilder", "{ $LOOP }")
            }
        }.withoutOffsets()
        val new = tree {
            cls("A") { method("render", "Order,StringBuilder", "{ $LOOP return text.toString(); }") }
        }.withoutOffsets()

        assertEquals("", inlinings(compare(old, new)))
    }

    @Test
    fun `an inlining into a renamed declaration is a separate link`() {
        // relationships/05-inlining-detection R20
        val old = tree {
            cls("A") {
                method("render", "Order,StringBuilder", "{ $STEPS return heading(order, text); }")
                method("heading", "Order,StringBuilder", "{ $LOOP }")
            }
        }
        val new = tree {
            cls("A") { method("make", "Order,StringBuilder", "{ $STEPS $LOOP return text.toString(); }") }
        }

        val result = compare(old, new)

        assertTrue(result.relationships.any { RelationshipKind.RENAME in it.kinds })
        assertEquals(1, result.relationships.count { RelationshipKind.INLINE in it.kinds })
        assertEquals("make(Order,StringBuilder)", result.node("heading").inlinedInto.single().let { name(it.subject) })
    }

    private fun compare(old: DeclarationTree, new: DeclarationTree): ComparisonResult =
        DeclarationAnalysis(EngineId("smartdiff.test.declarations"), "1").compare(old, new, 5_000, Cancellation.NONE)

    private fun ChangeNode.flatten(): List<ChangeNode> = listOf(this) + children.flatMap { it.flatten() }

    private fun ComparisonResult.node(name: String): ChangeNode =
        changes.flatMap { it.flatten() }.single { (it.subject as Subject.Declaration).name == name }

    /** One line per removed node with inlinings: `target(signature) <- source(signature)`. */
    private fun inlinings(result: ComparisonResult): String = buildString {
        for (node in result.changes.flatMap { it.flatten() }) {
            if (node.inlinedInto.isEmpty()) continue
            for (target in node.inlinedInto) {
                append(name(target.subject)).append(" <- ").append(name(node.subject)).append('\n')
            }
        }
    }

    private fun name(subject: Subject): String = (subject as Subject.Declaration).let { "${it.name}(${it.signature.orEmpty()})" }

    private fun DeclarationTree.withoutOffsets(): DeclarationTree {
        fun strip(d: Declaration): Declaration = Declaration(
            d.kind, d.name, d.signature, d.range, d.children.map(::strip), d.ownTokens, d.hasParseError, d.leadingWhitespace,
            d.displaySignature, d.receiver, ownTokenStarts = emptyList(), body = d.body,
        )
        return DeclarationTree(topLevel.map(::strip), fileRegion, whitespacePolicy)
    }

    private companion object {
        const val LOOP = "for (Item item : order.items()) { text.append(item.name()).append(item.quantity()); }"
        const val OTHER = "if (order.isPaid()) { receipt.print(order.id(), order.total()); mail.send(order.customer()); }"
        val STEPS = (1..30).joinToString(" ") { "step$it();" }
    }
}
