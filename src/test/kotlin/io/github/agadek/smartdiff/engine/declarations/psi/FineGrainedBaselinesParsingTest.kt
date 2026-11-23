package io.github.agadek.smartdiff.engine.declarations.psi

import io.github.agadek.smartdiff.engine.api.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.BitSet

/** The pure parts of the fine-grained validation harness (declarations/10 R21), without Difftastic or GumTree. */
class FineGrainedBaselinesParsingTest {

    // declarations/10-fine-grained-differencing R21
    @Test
    fun `the JSON reader reads objects, arrays, strings with escapes, numbers and literals`() {
        val value = MiniJson.parse("""{"a": [1, -2.5e1, true, false, null], "b": "x\"\né", "c": {}}""") as Map<*, *>
        assertEquals(listOf(1.0, -25.0, true, false, null), value["a"])
        assertEquals("x\"\né", value["b"])
        assertEquals(emptyMap<String, Any>(), value["c"])
    }

    // declarations/10-fine-grained-differencing R21
    @Test
    fun `byte columns map to character offsets`() {
        val offsets = TextOffsets("ab\n    val é = x + 1\n")
        assertEquals(3, offsets.lineStart(1))
        // '+' is character 14 of the line but byte 15, after the two-byte 'é'.
        assertEquals(3 + 14, offsets.lineColumn(1, 15))
        assertEquals(3 + 8, offsets.advanceBytes(3, 8))
        assertEquals(3 + 9, offsets.advanceBytes(3, 10))
        // A column past the line's end stops at the line's end.
        assertEquals(3 + 17, offsets.lineColumn(1, 99))
    }

    // declarations/10-fine-grained-differencing R21
    @Test
    fun `Difftastic changes become marked characters on each side`() {
        val old = "fun f() {\n    val é = x + 1\n}\n"
        val new = "fun f() {\n    val é = x * 2\n}\n"
        val json = """{"aligned_lines":[[0,0],[1,1],[2,2]],"chunks":[[{"lhs":{"line_number":1,"changes":[
            {"start":15,"end":16,"content":"+","highlight":"keyword"},{"start":17,"end":18,"content":"1","highlight":"normal"}]},
            "rhs":{"line_number":1,"changes":[{"start":15,"end":16,"content":"*","highlight":"keyword"},
            {"start":17,"end":18,"content":"2","highlight":"normal"}]}}]],"language":"Kotlin","path":"b.kt","status":"changed"}"""
        val result = DifftasticJson.parse(json, old, new)
        assertTrue(result.structural)
        assertEquals("+1", marked(old, result.marks.old))
        assertEquals("*2", marked(new, result.marks.new))
    }

    // declarations/10-fine-grained-differencing R21
    @Test
    fun `a Difftastic line fallback is not structural and an unchanged file has no marks`() {
        assertFalse(DifftasticJson.parse("""{"chunks":[],"language":"Text (exceeded DFT_GRAPH_LIMIT)","status":"changed"}""", "", "").structural)
        val unchanged = DifftasticJson.parse("""{"language":"Java","path":"a.java","status":"unchanged"}""", "a", "a")
        assertTrue(unchanged.marks.old.isEmpty && unchanged.marks.new.isEmpty)
    }

    // declarations/10-fine-grained-differencing R21
    @Test
    fun `GumTree node actions mark their own characters and tree actions the whole subtree`() {
        val old = "return x + f(1);"
        val new = "return x * f(2) - y;"
        // An update marks the label on both sides; an inserted inner node marks itself minus the matched nodes inside it;
        // an inserted tree marks all of it; a deleted node marks itself.
        val json = """{"matches":[
            {"src":"identifier: x [7,8]","dest":"identifier: x [7,8]"},
            {"src":"call [11,15]","dest":"call [11,15]"},
            {"src":"integer: 1 [13,14]","dest":"integer: 2 [13,14]"}],
          "actions":[
            {"action":"update-node","tree":"integer: 1 [13,14]","label":"2"},
            {"action":"insert-node","tree":"binary [7,19]","parent":"return [0,20]","at":1},
            {"action":"insert-node","tree":"binary [7,15]","parent":"binary [7,19]","at":0},
            {"action":"insert-tree","tree":"identifier: y [18,19]","parent":"binary [7,19]","at":2},
            {"action":"delete-node","tree":"+: + [9,10]"}]}"""
        val marks = GumTreeJson.parse(json, old, new, endIsByteLength = false)
        assertEquals("+1", marked(old, marks.old))
        // binary [7,15] owns " * " between x and the call; binary [7,19] owns " - " and y is its inserted tree.
        assertEquals(" * 2 - y", marked(new, marks.new))
    }

    // declarations/10-fine-grained-differencing R21
    @Test
    fun `GumTree tree-sitter ends are byte lengths from a character start`() {
        val old = "s = \"éé\"; t = 1"
        val new = "s = \"éé\"; t = 2"
        // The string node starts at character 4 and is 6 bytes long, so it ends at character 8, not 10.
        val json = """{"matches":[{"src":"string [4,10]","dest":"string [4,10]"},{"src":"integer: 1 [14,15]","dest":"integer: 2 [14,15]"}],
          "actions":[{"action":"update-node","tree":"integer: 1 [14,15]","label":"2"},{"action":"move-tree","tree":"string [4,10]","parent":"x [0,15]","at":0}]}"""
        val marks = GumTreeJson.parse(json, old, new, endIsByteLength = true)
        assertEquals("\"éé\"1", marked(old, marks.old))
        assertEquals("\"éé\"2", marked(new, marks.new))
    }

    // declarations/10-fine-grained-differencing R21
    @Test
    fun `own characters leave out children, count no whitespace, and missed needs every baseline`() {
        val text = "class A { void f() { a(); } int g; }"
        val own = CharSets.own(TextRange(0, text.length), listOf(TextRange(10, 27)))
        assertEquals("class A {  int g; }", marked(text, own))
        val everything = BitSet().apply { set(0, text.length) }
        assertEquals("classA{intg;}", marked(text, CharSets.restrict(everything, own, text)))
        val ours = CharSets.of(listOf(TextRange(28, 31)))
        val difftastic = CharSets.of(listOf(TextRange(28, 33)))
        val gumtree = CharSets.of(listOf(TextRange(32, 34)))
        assertEquals(" g", marked(text, CharSets.missed(ours, listOf(difftastic))))
        assertEquals("g", marked(text, CharSets.missed(ours, listOf(difftastic, gumtree))))
        assertTrue(CharSets.missed(ours, emptyList()).isEmpty)
    }

    // declarations/10-fine-grained-differencing R21
    @Test
    fun `the median and the snippet markers`() {
        assertEquals(0.0, CharSets.median(emptyList()), 0.0)
        assertEquals(2.0, CharSets.median(listOf(3.0, 1.0, 2.0)), 0.0)
        assertEquals(1.5, CharSets.median(listOf(2.0, 1.0)), 0.0)
        val text = "a\n\tbc\nd"
        val own = BitSet().apply { set(0, text.length) }
        val snippet = CharSets.snippet(text, own, listOf("ours" to CharSets.of(listOf(TextRange(4, 5)))))
        assertEquals("    2|  bc\n ours|   ^\n", snippet)
    }

    // declarations/10-fine-grained-differencing R22
    @Test
    fun `the text outside the runs keeps order, drops whitespace and leaves out the runs and other characters`() {
        val text = "a + b\n  c d"
        val own = BitSet().apply { set(0, 10) }
        assertEquals("a+c", CharSets.outside(text, own, CharSets.of(listOf(TextRange(4, 5)))))
        assertEquals("abc", CharSets.outside(text, own, CharSets.of(listOf(TextRange(2, 3)))))
        // Whole-word names become one placeholder, so a renamed name compares equal; a longer word keeps its letters.
        assertEquals("§(ab)", CharSets.outside("parseInt(ab)", BitSet().apply { set(0, 12) }, BitSet(), listOf("parseInt", "b")))
        assertEquals("j.getNullable()§", CharSets.outside("j.getNullable() b", BitSet().apply { set(0, 17) }, BitSet(), listOf("b", "j6")))
    }

    private fun marked(text: String, set: BitSet): String = text.indices.filter { set[it] }.map { text[it] }.joinToString("")
}
