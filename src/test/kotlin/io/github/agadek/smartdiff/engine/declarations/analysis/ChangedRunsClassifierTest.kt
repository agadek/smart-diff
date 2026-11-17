package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy.INDENTATION_SIGNIFICANT
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy.INSIGNIFICANT
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy.SIGNIFICANT
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.FileRegion
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.random.Random

/** The token comparison of [ContentClassifier.changedRuns] and its use by [DeclarationAnalysis] (declarations/10). */
class ChangedRunsClassifierTest {

    // declarations/10-fine-grained-differencing R2, R3
    @Test
    fun `one changed operator gives one run on each side`() {
        assertEquals(listOf("<" to ">="), runs("if (a < limit) return;", "if (a >= limit) return;"))
    }

    // declarations/10-fine-grained-differencing R4
    @Test
    fun `a re-wrapped argument list in Java gives no run`() {
        assertEquals(emptyList<Pair<String?, String?>>(), runs("f(a, b, c);", "f(a,\n        b,\n        c);"))
    }

    // declarations/10-fine-grained-differencing R2, R4
    @Test
    fun `a reformatted method with one edit highlights only the edit`() {
        val old = "int total() {\n  int sum=0;\n  for (Item i : items) { sum += i.price(); }\n  return sum;\n}"
        val new = "int total() {\n  int sum = 0;\n  for (Item i : items) {\n    sum += i.price(limit);\n  }\n  return sum;\n}"
        assertEquals(listOf(null to "limit"), runs(old, new))
    }

    // declarations/10-fine-grained-differencing R4
    @Test
    fun `a re-indented Python body gives runs on the indentation`() {
        val runs = runs("def f():\n    a()\n    b()", "def f():\n    a()\n        b()", INDENTATION_SIGNIFICANT)
        assertEquals(listOf("\n    " to "\n        "), runs)
    }

    // declarations/10-fine-grained-differencing R5
    @Test
    fun `a changed comment is a run`() {
        assertEquals(listOf("// old" to "// new"), runs("a(); // old\nb();", "a(); // new\nb();"))
        assertEquals(listOf("/* x  y */" to "/* x y */"), runs("a(/* x  y */);", "a(/* x y */);"))
    }

    // declarations/10-fine-grained-differencing R6
    @Test
    fun `two edits separated by spaces form one run, and by an unchanged token two runs`() {
        assertEquals(listOf("a b" to "x y"), runs("f(a b);", "f(x y);"))
        assertEquals(listOf("a" to "x", "b" to "y"), runs("f(a, b);", "f(x, y);"))
    }

    // declarations/10-fine-grained-differencing R7
    @Test
    fun `two swapped statements give runs on both sides`() {
        val runs = runs("first();\nsecond();", "second();\nfirst();")
        assertTrue("$runs", runs.any { it.first != null })
        assertTrue("$runs", runs.any { it.second != null })
    }

    // declarations/10-fine-grained-differencing R8
    @Test
    fun `every differing token lies in a run`() {
        val alphabet = listOf("a", "b", "c", "(", ")", "+", ";", " ", "\n", "// c")
        val random = Random(10)
        repeat(300) {
            val old = List(random.nextInt(0, 30)) { alphabet.random(random) }
            val new = old.toMutableList()
            repeat(random.nextInt(1, 6)) {
                when (random.nextInt(3)) {
                    0 -> if (new.isNotEmpty()) new.removeAt(random.nextInt(new.size))
                    1 -> new.add(random.nextInt(new.size + 1), alphabet.random(random))
                    else -> if (new.isNotEmpty()) new[random.nextInt(new.size)] = alphabet.random(random)
                }
            }
            val oldTokens = old.map(::token)
            val newTokens = new.map(::token)
            val oldStarts = starts(oldTokens)
            val newStarts = starts(newTokens)
            val runs = ContentClassifier.changedRuns(oldTokens, oldStarts, newTokens, newStarts, SIGNIFICANT, parseError = false)!!
            // Under SIGNIFICANT every token is compared, so the tokens outside the runs must be the same sequence on
            // both sides. A run joined across matched whitespace (R6) may cover that whitespace on one side only, so
            // whitespace is left out of the check.
            fun outside(tokens: List<Token>, starts: List<Int>, side: (RangePair) -> TextRange?) =
                tokens.indices.filter { i -> tokens[i].kind != TokenKind.WHITESPACE && runs.none { r -> side(r)?.let { starts[i] in it } == true } }
                    .map { tokens[it].text }
            val outsideOld = outside(oldTokens, oldStarts) { it.old }
            val outsideNew = outside(newTokens, newStarts) { it.new }
            assertEquals("$old -> $new: $runs", outsideOld, outsideNew)
            assertRunsInside(runs, old.joinToString("").length, new.joinToString("").length)
        }
    }

    // declarations/10-fine-grained-differencing R9
    @Test
    fun `a parse error and a provider without offsets give no runs`() {
        assertNull(ContentClassifier.changedRuns(tokens("a"), listOf(0), tokens("b"), listOf(0), INSIGNIFICANT, parseError = true))
        assertNull(ContentClassifier.changedRuns(tokens("a"), emptyList(), tokens("b"), listOf(0), INSIGNIFICANT, parseError = false))
    }

    // declarations/10-fine-grained-differencing R10
    @Test
    fun `over 20 000 tokens or an edit distance over 2 000 gives no runs`() {
        assertEquals(20_000, ContentClassifier.MAX_RUN_TOKENS)
        assertEquals(2_000, ContentClassifier.MAX_RUN_EDITS)
        val many = List(20_001) { token("a") }
        assertNull(ContentClassifier.changedRuns(many, starts(many), listOf(token("a")), listOf(0), SIGNIFICANT, parseError = false))
        val atLimit = List(20_000) { token("a") }
        val shorter = atLimit.drop(1)
        assertNotNull(ContentClassifier.changedRuns(atLimit, starts(atLimit), shorter, starts(shorter), SIGNIFICANT, parseError = false))

        val old = List(1_000) { token("a") }
        // 1 000 removed plus 1 000 added: exactly the limit.
        val edited = List(1_000) { token("b") }
        assertNotNull(ContentClassifier.changedRuns(old, starts(old), edited, starts(edited), SIGNIFICANT, parseError = false))
        // 1 000 removed plus 1 001 added: one over.
        val over = List(1_001) { token("b") }
        assertNull(ContentClassifier.changedRuns(old, starts(old), over, starts(over), SIGNIFICANT, parseError = false))
    }

    @Test
    fun `equal inputs give equal results`() {
        val old = "a(1); b(2); c(3); d(4);"
        val new = "b(2); a(1); d(5); c(3);"
        assertEquals(runs(old, new), runs(old, new))
    }

    // declarations/10-fine-grained-differencing details, step 7
    @Test
    fun `cancellation stops the comparison`() {
        val old = List(5_000) { token(if (it % 2 == 0) "a" else "b") }
        val new = List(5_000) { token(if (it % 3 == 0) "a" else "c") }
        var calls = 0
        val cancellation = Cancellation { if (++calls > 3) throw CancellationException("stop") }
        try {
            ContentClassifier.changedRuns(old, starts(old), new, starts(new), SIGNIFICANT, parseError = false, cancellation)
        } catch (e: CancellationException) {
            return
        }
        throw AssertionError("not canceled")
    }

    // declarations/10-fine-grained-differencing R2
    @Test
    fun `a changed method in a result carries its runs inside its own range`() {
        val old = tree { cls("Cart", "{}") { method("total", body = "{ return a < b; }") } }
        val new = tree { cls("Cart", "{}") { method("total", body = "{ return a <= b; }") } }
        val method = compare(old, new).changes.single().children.single()
        assertEquals(Content.CHANGED, method.content)
        assertTrue(method.comparedByTokens)
        assertEquals(1, method.changedRuns.size)
        method.changedRuns.forEach { run ->
            run.old?.let { assertTrue(it.start >= method.oldRange!!.start && it.end <= method.oldRange!!.end) }
            run.new?.let { assertTrue(it.start >= method.newRange!!.start && it.end <= method.newRange!!.end) }
        }
    }

    // declarations/10-fine-grained-differencing R2, R10
    @Test
    fun `a formatting-only declaration is compared with no runs, and an unchanged one is not compared`() {
        val old = tree { cls("Cart", "{}") { method("total", body = "{ return a<b; }"); method("same", body = "{}") } }
        val new = tree { cls("Cart", "{}") { method("total", body = "{ return a < b; }"); method("same", body = "{}") } }
        val cls = compare(old, new).changes.single()
        val total = cls.children.single { (it.subject as Subject.Declaration).name == "total" }
        assertEquals(Content.FORMATTING_ONLY, total.content)
        assertTrue(total.comparedByTokens)
        assertEquals(emptyList<RangePair>(), total.changedRuns)
        assertFalse(cls.comparedByTokens)
    }

    // declarations/10-fine-grained-differencing R9
    @Test
    fun `a declaration with a parse error has no runs`() {
        val old = tree { method("f", body = "{ a(); }") }
        val new = tree { method("f", body = "{ b( }", parseError = true) }
        val node = compare(old, new).changes.single()
        assertFalse(node.comparedByTokens)
        assertTrue(node.changedRuns.isEmpty())
    }

    // declarations/10-fine-grained-differencing R2
    @Test
    fun `a changed file region carries its runs`() {
        fun region(text: String): DeclarationTree {
            val tokens = tokens(text)
            return DeclarationTree(emptyList(), FileRegion(listOf(TextRange(0, text.length)), tokens, tokenStarts = starts(tokens)), INSIGNIFICANT)
        }
        val region = compare(region("import a.B;"), region("import a.C;")).fileRegion!!
        assertTrue(region.comparedByTokens)
        assertEquals(listOf(RangePair(TextRange(9, 10), TextRange(9, 10))), region.changedRuns)
        // A file region without offsets is not compared.
        assertFalse(compare(tree(fileRegion = "import a.B;"), tree(fileRegion = "import a.C;")).fileRegion!!.comparedByTokens)
    }

    @Test
    fun `a rename alone gives no run`() {
        val old = tree { method("add", body = "{ return add(x); }") }
        val new = tree { method("plus", body = "{ return plus(x) + 1; }") }
        val node = compare(old, new).changes.single()
        if (node.comparedByTokens) {
            val newText = "{ return plus(x) + 1; }"
            val start = node.newRange!!.start
            val texts = node.changedRuns.map { run -> run.new?.let { newText.substring(it.start - start, it.end - start) } }
            assertFalse("$texts", texts.any { it?.contains("plus") == true })
        }
    }

    @Test
    fun `an exception leaves the node without runs and keeps its classification`() {
        val good = tree { method("f", body = "{ a(); }") }.topLevel.single()
        // Starts that run backwards make a run end before it starts.
        val brokenTokens = tokens("{ x y z }")
        val broken = Declaration(
            good.kind, good.name, good.signature, good.range, ownTokens = brokenTokens,
            ownTokenStarts = brokenTokens.indices.map { good.range.start + 100 - it * 10 },
        )
        val old = DeclarationTree(listOf(good), tree().fileRegion, INSIGNIFICANT)
        val new = DeclarationTree(listOf(broken), tree().fileRegion, INSIGNIFICANT)
        val node = compare(old, new).changes.single()
        assertEquals(Content.CHANGED, node.content)
        assertTrue(node.changedRuns.isEmpty())
    }

    private fun compare(old: DeclarationTree, new: DeclarationTree): ComparisonResult =
        DeclarationAnalysis(EngineId("test"), "1").compare(old, new, 1_000, Cancellation.NONE)

    private fun assertRunsInside(runs: List<RangePair>, oldLength: Int, newLength: Int) {
        runs.forEach { run ->
            run.old?.let { assertTrue("$it", it.end <= oldLength) }
            run.new?.let { assertTrue("$it", it.end <= newLength) }
        }
    }

    private fun token(text: String): Token = tokens(text).singleOrNull() ?: Token("WHITE_SPACE", text, TokenKind.WHITESPACE)

    private fun starts(tokens: List<Token>): List<Int> = tokens.runningFold(0) { offset, token -> offset + token.text.length }.dropLast(1)

    /** The runs between [old] and [new] as the texts they cover, old then new. */
    private fun runs(old: String, new: String, policy: WhitespacePolicy = INSIGNIFICANT): List<Pair<String?, String?>> {
        val oldTokens = tokens(old)
        val newTokens = tokens(new)
        val runs = ContentClassifier.changedRuns(oldTokens, starts(oldTokens), newTokens, starts(newTokens), policy, parseError = false)!!
        fun text(source: String, range: TextRange?) = range?.let { source.substring(it.start, it.end) }
        return runs.map { text(old, it.old) to text(new, it.new) }
    }

    @Suppress("unused")
    private fun ChangeNode.all(): List<ChangeNode> = listOf(this) + children.flatMap { it.all() }
}
