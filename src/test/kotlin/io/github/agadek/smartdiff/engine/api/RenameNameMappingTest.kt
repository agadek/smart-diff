package io.github.agadek.smartdiff.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared name mapping of the reference pass and the outline (relationships/04 R7, R10). */
class RenameNameMappingTest {

    @Test
    fun `R7 the mapping replaces only the reference range`() {
        val old = "class A { int total() { return total(); } }"
        val new = "class A { int sum() { return sum(); } }"
        val first = Evidence.Reference(method("sum"), TextRange(14, 19), TextRange(14, 17))
        val second = Evidence.Reference(method("sum"), TextRange(31, 36), TextRange(29, 32))

        assertEquals("class A { int sum() { return sum(); } }", RenameNameMapping.map(old, new, listOf(first, second)))
    }

    @Test
    fun `R7 whitespace follows the language policy but comments and strings do not`() {
        assertTrue(RenameNameMapping.sameText("a  b", "a b", WhitespacePolicy.INSIGNIFICANT))
        assertTrue(RenameNameMapping.sameText("a\nb", "a\n\nb", WhitespacePolicy.LINE_BREAKS_SIGNIFICANT))
        assertFalse(RenameNameMapping.sameText("a b", "ab", WhitespacePolicy.LINE_BREAKS_SIGNIFICANT))
        assertTrue(RenameNameMapping.sameText("a  b", "a b", WhitespacePolicy.SEPARATION_SIGNIFICANT))
        assertTrue(RenameNameMapping.sameText("a\nb", "a\n\nb", WhitespacePolicy.SEPARATION_SIGNIFICANT))
        assertFalse(RenameNameMapping.sameText("a b", "ab", WhitespacePolicy.SEPARATION_SIGNIFICANT))
        assertFalse(RenameNameMapping.sameText("a\n  b", "a\n b", WhitespacePolicy.INDENTATION_SIGNIFICANT))
        assertFalse(RenameNameMapping.sameText("a  b", "a b", WhitespacePolicy.SIGNIFICANT))
        assertFalse(RenameNameMapping.sameText("\"a b\"", "\"a  b\"", WhitespacePolicy.INSIGNIFICANT))
        assertFalse(RenameNameMapping.sameText("// a b", "// a  b", WhitespacePolicy.INSIGNIFICANT))
    }

    @Test
    fun `R10 leading whitespace and unchanged strings do not decide the label`() {
        val old = "\n    // total stays in the comment\n    int twice() { return total(); }"
        val new = "\n    // total stays in the comment\n    int twice() { return sum(); }"
        val reference = Evidence.Reference(method("sum"), TextRange(60, 65), TextRange(60, 63))

        assertTrue(
            RenameNameMapping.isRenameOnly(
                old, new, listOf(reference),
                TextRange(0, old.length), TextRange(0, new.length), WhitespacePolicy.INSIGNIFICANT,
            ),
        )
    }

    @Test
    fun `R7 Kotlin backticks and Scala symbols and chars are tokens, not whitespace`() {
        // Kotlin backtick identifiers may contain whitespace and comment markers.
        assertFalse(RenameNameMapping.sameText("`a b`", "`a  b`", WhitespacePolicy.LINE_BREAKS_SIGNIFICANT))
        assertTrue(RenameNameMapping.sameText("`a b`", "`a b`", WhitespacePolicy.LINE_BREAKS_SIGNIFICANT))
        // A Scala symbol literal is code: whitespace after it is still layout.
        assertTrue(RenameNameMapping.sameText("'foo  bar", "'foo bar", WhitespacePolicy.LINE_BREAKS_SIGNIFICANT))
        assertFalse(RenameNameMapping.sameText("'foo", "'bar", WhitespacePolicy.LINE_BREAKS_SIGNIFICANT))
        // A char literal is compared as written.
        assertTrue(RenameNameMapping.sameText("'a'  x", "'a' x", WhitespacePolicy.LINE_BREAKS_SIGNIFICANT))
        assertFalse(RenameNameMapping.sameText("'a'", "'b'", WhitespacePolicy.LINE_BREAKS_SIGNIFICANT))
        // Kotlin and Scala nest block comments, so text after an inner close is still comment.
        assertFalse(
            RenameNameMapping.sameText("/* a /* b */  c */", "/* a /* b */ c */", WhitespacePolicy.LINE_BREAKS_SIGNIFICANT),
        )
        assertTrue(
            RenameNameMapping.sameText("/* a /* b */ c */", "/* a /* b */ c */", WhitespacePolicy.LINE_BREAKS_SIGNIFICANT),
        )
    }

    @Test
    fun `R10 wrapped call sites are formatting under the new policies`() {
        // declarations/07-kotlin-scala-formatting R10
        assertTrue(
            RenameNameMapping.sameText(
                "fun total(a: Int, b: Int) = f(a, b)\nfun use() = total(1, 2)",
                "fun total(a: Int, b: Int) = f(\n  a,\n  b\n)\nfun use() = total(\n  1,\n  2\n)",
                WhitespacePolicy.KOTLIN_LINE_BREAKS,
            ),
        )
        assertTrue(
            RenameNameMapping.sameText(
                "def total(a: Int, b: Int) = f(a, b)\ndef use = total(1, 2)",
                "def total(a: Int, b: Int) = f(\n  a,\n  b\n)\ndef use = total(\n  1,\n  2\n)",
                WhitespacePolicy.SCALA2_LINE_BREAKS,
            ),
        )
        assertTrue(
            RenameNameMapping.sameText(
                "\n  def total(a: Int): Int =\n    a + total(1)",
                "\n      def total(a: Int): Int =\n        a + total(\n          1\n        )",
                WhitespacePolicy.SCALA3_INDENTATION,
            ),
        )
        // A real change next to a re-wrap is still a change.
        assertFalse(
            RenameNameMapping.sameText(
                "fun total(a: Int, b: Int) = f(a, b)",
                "fun total(a: Int, b: Int) = f(\n  a,\n  b + 1\n)",
                WhitespacePolicy.KOTLIN_LINE_BREAKS,
            ),
        )
    }

    @Test
    fun `R10 a rename with re-wrapped call sites has nothing changed but the rename`() {
        // declarations/07-kotlin-scala-formatting R10
        val old = "fun total(a: Int) = f(a)\nfun use() = total(1)"
        val new = "fun sum(a: Int) = f(\n  a\n)\nfun use() = sum(\n  1\n)"
        val first = old.indexOf("total")
        val firstNew = new.indexOf("sum")
        val second = old.indexOf("total", first + 1)
        val secondNew = new.indexOf("sum", firstNew + 1)
        val references = listOf(
            Evidence.Reference(method("sum"), TextRange(first, first + 5), TextRange(firstNew, firstNew + 3)),
            Evidence.Reference(method("sum"), TextRange(second, second + 5), TextRange(secondNew, secondNew + 3)),
        )
        assertTrue(
            RenameNameMapping.isRenameOnly(
                old, new, references,
                TextRange(0, old.length), TextRange(0, new.length), WhitespacePolicy.KOTLIN_LINE_BREAKS,
            ),
        )
    }

    @Test
    fun `R7 a reference range outside the compared text is refused`() {
        val reference = Evidence.Reference(method("sum"), TextRange(0, 9), TextRange(0, 1))
        try {
            RenameNameMapping.map("abc", "abc", listOf(reference))
            throw AssertionError("the mapping must refuse a range outside the text")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    private fun method(name: String) = Subject.Declaration(DeclarationKind.METHOD, name, "()")
}
