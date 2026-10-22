package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

/** Links from added methods to the code they were extracted from (extraction detection specification §4, §5). */
class ExtractionDetectionTest {

    @Test
    fun `an extraction from a changed method is linked from both ends`() {
        val old = tree { cls("A") { method("summary", "Order", "{ start(); $LOOP finish(); }") } }
        val new = tree {
            cls("A") {
                method("summary", "Order", "{ start(); describe(order, text); finish(); }")
                method("describe", "Order,StringBuilder", "{ $LOOP }")
            }
        }

        val result = compare(old, new)

        assertEquals("describe(Order,StringBuilder) <- summary(Order)\n", links(result))
        val summary = result.node("summary")
        val describe = result.node("describe")
        assertEquals(Presence.ADDED, describe.presence)
        val origin = describe.extractedFrom.single()
        assertEquals(summary.subject, origin.subject)
        assertEquals(summary.oldRange, origin.declarationRange)
        assertEquals(LOOP, old.text(origin.range))
        assertEquals(listOf(describe.subject), summary.extractedTo)
        assertTrue(RangePair(origin.range, null) in summary.details)
        assertEquals(Content.CHANGED, summary.content)
    }

    @Test
    fun `reformatted extracted code is linked`() {
        val old = tree { cls("A") { method("summary", body = "{ start(); $LOOP }") } }
        val new = tree {
            cls("A") {
                method("summary", body = "{ start(); describe(); }")
                method("describe", body = "{\n    ${LOOP.replace(" { ", "\n    {\n        ").replace("; }", ";\n    }")}\n}")
            }
        }

        assertEquals("describe() <- summary()\n", links(compare(old, new)))
    }

    @Test
    fun `a few changed tokens inside the extracted code still link`() {
        val old = tree { cls("A") { method("summary", body = "{ start(); $LOOP }") } }
        val new = tree {
            cls("A") {
                method("summary", body = "{ start(); describe(order.items()); }")
                method("describe", "List", "{ ${LOOP.replace("order.items()", "items")} }")
            }
        }

        assertEquals("describe(List) <- summary()\n", links(compare(old, new)))
    }

    @Test
    fun `code copied into a helper and kept in the source is not linked`() {
        val old = tree { cls("A") { method("summary", body = "{ start(); $LOOP }") } }
        val new = tree {
            cls("A") {
                method("summary", body = "{ begin(); $LOOP }")
                method("describe", body = "{ $LOOP }")
            }
        }

        assertEquals("", links(compare(old, new)))
    }

    @Test
    fun `code copied from an unchanged declaration is not linked`() {
        val old = tree { cls("A") { method("summary", body = "{ $LOOP }") } }
        val new = tree {
            cls("A") {
                method("summary", body = "{ $LOOP }")
                method("describe", body = "{ $LOOP }")
            }
        }

        assertEquals("", links(compare(old, new)))
    }

    @Test
    fun `a short body is not linked`() {
        val old = tree { cls("A") { method("summary", body = "{ start(); total += price(item); finish(); }") } }
        val new = tree {
            cls("A") {
                method("summary", body = "{ start(); add(item); finish(); }")
                method("add", "Item", "{ total += price(item); }")
            }
        }

        assertEquals("", links(compare(old, new)))
    }

    @Test
    fun `a body only partly made of removed code is not linked`() {
        val old = tree { cls("A") { method("summary", body = "{ start(); $LOOP }") } }
        val new = tree {
            cls("A") {
                method("summary", body = "{ start(); describe(); }")
                method("describe", body = "{ $LOOP log.info(\"described\", order.id(), order.customer()); audit.add(order); }")
            }
        }

        assertEquals("", links(compare(old, new)))
    }

    @Test
    fun `tiles scattered across the source are not linked`() {
        val kept = "keep(one); keep(two); keep(three); keep(four); keep(five); keep(six); keep(seven);"
        val old = tree { cls("A") { method("run", body = "{ alpha.beta(gamma); $kept delta.epsilon(zeta); }") } }
        val new = tree {
            cls("A") {
                method("run", body = "{ $kept }")
                method("helper", body = "{ alpha.beta(gamma); delta.epsilon(zeta); }")
            }
        }

        assertEquals("", links(compare(old, new)))
    }

    @Test
    fun `duplicated code merged into one helper links to both sources`() {
        val old = tree {
            cls("A") {
                method("a", body = "{ first(); $LOOP }")
                method("b", body = "{ second(); $LOOP }")
            }
        }
        val new = tree {
            cls("A") {
                method("a", body = "{ first(); describe(); }")
                method("b", body = "{ second(); describe(); }")
                method("describe", body = "{ $LOOP }")
            }
        }

        val result = compare(old, new)

        assertEquals("describe() <- a(), b()\n", links(result))
        assertEquals(listOf(result.node("describe").subject), result.node("a").extractedTo)
        assertEquals(listOf(result.node("describe").subject), result.node("b").extractedTo)
    }

    @Test
    fun `one source extracted to two helpers links both`() {
        val other = "if (order.isPaid()) { receipt.print(order.id(), order.total()); mail.send(order.customer()); }"
        val old = tree { cls("A") { method("summary", body = "{ start(); $LOOP $other }") } }
        val new = tree {
            cls("A") {
                method("summary", body = "{ start(); describe(); notify(); }")
                method("describe", body = "{ $LOOP }")
                method("notify", body = "{ $other }")
            }
        }

        val result = compare(old, new)

        assertEquals("describe() <- summary()\nnotify() <- summary()\n", links(result))
        val summary = result.node("summary")
        assertEquals(listOf(result.node("describe").subject, result.node("notify").subject), summary.extractedTo)
        assertEquals(2, summary.details.count { it.new == null })
    }

    @Test
    fun `an extraction into a member of an added class is linked`() {
        val old = tree { cls("Service") { method("shippingCost", "Order", "{ $LOOP }") } }
        val new = tree {
            cls("Service") { method("shippingCost", "Order", "{ return Rates.costFor(order); }") }
            cls("Rates") { method("costFor", "Order", "{ $LOOP }") }
        }

        assertEquals("costFor(Order) <- shippingCost(Order)\n", links(compare(old, new)))
    }

    @Test
    fun `a function can be a target and a constructor a source`() {
        val old = tree { cls("A") { constructor("Order", "{ init(); $LOOP }") } }
        val new = tree {
            cls("A") { constructor("Order", "{ init(); describe(); }") }
            function("describe", body = "{ $LOOP }")
        }

        assertEquals("describe() <- (Order)\n", links(compare(old, new)))
    }

    @Test
    fun `a rename candidate is not turned into an extraction`() {
        val old = tree { cls("A") { method("build", body = "{ $LOOP }") } }
        val new = tree { cls("A") { method("make", body = "{ $LOOP }") } }

        val result = compare(old, new)

        val make = result.node("make")
        assertNotNull(make.renamedFrom)
        assertEquals("", links(result))
    }

    @Test
    fun `detection is skipped above the work limit`() {
        val old = tree { cls("A") { method("summary", body = "{ start(); $LOOP }") } }
        val new = tree {
            cls("A") {
                method("summary", body = "{ start(); describe(); }")
                method("describe", body = "{ $LOOP }")
            }
        }
        val matching = DeclarationMatcher.match(old, new, Cancellation.NONE)

        val found = ExtractionDetector.detect(matching, { true }, Cancellation.NONE)
        val skipped = ExtractionDetector.detect(matching, { true }, Cancellation.NONE, maxWork = 100)

        assertEquals(1, found.ofTarget.size)
        assertSame(Extractions.NONE, skipped)
    }

    @Test(expected = CancellationException::class)
    fun `detection checks cancellation`() {
        val old = tree { cls("A") { method("summary", body = "{ start(); $LOOP }") } }
        val new = tree {
            cls("A") {
                method("summary", body = "{ start(); describe(); }")
                method("describe", body = "{ $LOOP }")
            }
        }
        val matching = DeclarationMatcher.match(old, new, Cancellation.NONE)

        ExtractionDetector.detect(matching, { true }, Cancellation { throw CancellationException() })
    }

    @Test
    fun `declarations without token offsets take no part`() {
        val old = tree { cls("A") { method("summary", body = "{ start(); $LOOP }") } }.withoutOffsets()
        val new = tree {
            cls("A") {
                method("summary", body = "{ start(); describe(); }")
                method("describe", body = "{ $LOOP }")
            }
        }.withoutOffsets()

        assertEquals("", links(compare(old, new)))
    }

    @Test
    fun `removed tokens are the ones an alignment deletes`() {
        fun removed(old: String, new: String): String {
            val ids = (old + new).toSet().withIndex().associate { (i, c) -> c to i }
            val deleted = ExtractionDetector.removed(
                old.map { ids.getValue(it) }.toIntArray(), new.map { ids.getValue(it) }.toIntArray(),
                ExtractionDetector.Budget(Long.MAX_VALUE, Cancellation.NONE),
            )
            return old.filterIndexed { i, _ -> deleted[i] }
        }

        assertEquals("", removed("abc", "abc"))
        assertEquals("abc", removed("abc", "xyz"))
        assertEquals("b", removed("abc", "ac"))
        // Myers' example: the shortest edit script has 5 edits, 3 of them deletions.
        assertEquals(3, removed("abcabba", "cbabac").length)
        assertEquals("bcd", removed("abcde", "aXe"))
    }

    @Test
    fun `the alignment keeps a longest common subsequence`() {
        val random = java.util.Random(7)
        repeat(300) {
            val old = IntArray(random.nextInt(40)) { random.nextInt(4) }
            val new = IntArray(random.nextInt(40)) { random.nextInt(4) }
            val deleted = ExtractionDetector.removed(old, new, ExtractionDetector.Budget(Long.MAX_VALUE, Cancellation.NONE))
            val kept = old.filterIndexed { i, _ -> !deleted[i] }
            val lcs = Array(old.size + 1) { IntArray(new.size + 1) }
            for (i in old.indices.reversed()) for (j in new.indices.reversed()) {
                lcs[i][j] = if (old[i] == new[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
            assertEquals(lcs[0][0], kept.size)
            var j = 0
            for (k in kept) {
                while (j < new.size && new[j] != k) j++
                assertTrue("kept tokens are a subsequence of the new side", j < new.size)
                j++
            }
        }
    }

    private fun compare(old: DeclarationTree, new: DeclarationTree): ComparisonResult =
        DeclarationAnalysis(EngineId("smartdiff.test.declarations"), "1").compare(old, new, 5_000, Cancellation.NONE)

    private fun ChangeNode.flatten(): List<ChangeNode> = listOf(this) + children.flatMap { it.flatten() }

    private fun ComparisonResult.node(name: String): ChangeNode =
        changes.flatMap { it.flatten() }.single { (it.subject as Subject.Declaration).name == name }

    /** One line per added node with links: `target(signature) <- source(signature), …`. */
    private fun links(result: ComparisonResult): String = buildString {
        for (node in result.changes.flatMap { it.flatten() }) {
            if (node.extractedFrom.isEmpty()) continue
            append(name(node.subject)).append(" <- ").append(node.extractedFrom.joinToString(", ") { name(it.subject) }).append('\n')
        }
    }

    private fun name(subject: Subject): String = (subject as Subject.Declaration).let { "${it.name}(${it.signature.orEmpty()})" }

    /** The source a range of a hand-built tree covers, from its declarations' own tokens. */
    private fun DeclarationTree.text(range: TextRange): String = buildString {
        for (declaration in all) {
            for ((i, token) in declaration.ownTokens.withIndex()) {
                if (declaration.ownTokenStarts.getOrNull(i)?.let { it >= range.start && it < range.end } == true) append(token.text)
            }
        }
    }

    private fun DeclarationTree.withoutOffsets(): DeclarationTree {
        fun strip(d: Declaration): Declaration = Declaration(
            d.kind, d.name, d.signature, d.range, d.children.map(::strip), d.ownTokens, d.hasParseError, d.leadingWhitespace,
            d.displaySignature, d.receiver, ownTokenStarts = emptyList(), body = d.body,
        )
        return DeclarationTree(topLevel.map(::strip), fileRegion, whitespacePolicy)
    }

    private companion object {
        const val LOOP = "for (Item item : order.items()) { text.append(item.name()).append(item.quantity()); }"
    }
}
