package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy.INDENTATION_SIGNIFICANT
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy.INSIGNIFICANT
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy.LINE_BREAKS_SIGNIFICANT
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy.SIGNIFICANT
import org.junit.Assert.assertEquals
import org.junit.Test

/** Classification steps and whitespace policies (technical specification §8). */
class ContentClassifierTest {

    @Test
    fun `identical tokens are unchanged under every policy`() {
        WhitespacePolicy.entries.forEach { assertEquals(Content.UNCHANGED, classify("int  x = 1;", "int  x = 1;", it)) }
    }

    @Test
    fun `a parse error wins over everything, including a signature change`() {
        assertEquals(Content.UNANALYZED, classify("{ a; }", "{ a; }", INSIGNIFICANT, parseError = true))
        assertEquals(Content.UNANALYZED, classify("{ a; }", "{ b; }", INSIGNIFICANT, parseError = true, signatureChanged = true))
    }

    @Test
    fun `a signature change is changed even when the own tokens are identical`() {
        assertEquals(Content.CHANGED, classify("{ a; }", "{ a; }", INSIGNIFICANT, signatureChanged = true))
    }

    @Test
    fun `a token change is changed`() {
        WhitespacePolicy.entries.forEach { assertEquals(Content.CHANGED, classify("return a;", "return b;", it)) }
    }

    @Test
    fun `insignificant whitespace ignores all whitespace`() {
        assertEquals(Content.FORMATTING_ONLY, classify("return a+b;", "return  a + b;", INSIGNIFICANT))
        assertEquals(Content.FORMATTING_ONLY, classify("if (a) { b(); }", "if (a) {\n    b();\n}", INSIGNIFICANT))
    }

    @Test
    fun `whitespace never joins or splits other tokens`() {
        assertEquals(Content.CHANGED, classify("int ab;", "int a b;", INSIGNIFICANT))
    }

    @Test
    fun `line-break policy ignores spaces but not line breaks`() {
        assertEquals(Content.FORMATTING_ONLY, classify("val x = a+b", "val x  =  a + b", LINE_BREAKS_SIGNIFICANT))
        assertEquals(Content.FORMATTING_ONLY, classify("foo(\n  a)", "foo(\n\n      a)", LINE_BREAKS_SIGNIFICANT))
        assertEquals(Content.CHANGED, classify("val x = a + b", "val x = a\n+ b", LINE_BREAKS_SIGNIFICANT))
        assertEquals(Content.CHANGED, classify("foo(\n  a)", "foo(a)", LINE_BREAKS_SIGNIFICANT))
    }

    @Test
    fun `indentation policy ignores spaces within a line but not line breaks or indentation`() {
        assertEquals(Content.FORMATTING_ONLY, classify("val x = a+b", "val x  =  a + b", INDENTATION_SIGNIFICANT))
        assertEquals(Content.FORMATTING_ONLY, classify("object A:\n  def f = 1", "object A:\n\n  def f  = 1", INDENTATION_SIGNIFICANT))
        assertEquals(Content.CHANGED, classify("val x = a + b", "val x = a\n+ b", INDENTATION_SIGNIFICANT))
        assertEquals(Content.CHANGED, classify("def f =\n    a\n    b", "def f =\n    a\n  b", INDENTATION_SIGNIFICANT))
        // Indentation is compared as written: re-indenting everything, or a tab for spaces, is not formatting-only.
        assertEquals(Content.CHANGED, classify("object A:\n  def f = 1", "object A:\n    def f = 1", INDENTATION_SIGNIFICANT))
        assertEquals(Content.CHANGED, classify("object A:\n  def f = 1", "object A:\n\tdef f = 1", INDENTATION_SIGNIFICANT))
    }

    @Test
    fun `significant whitespace is never formatting-only`() {
        assertEquals(Content.CHANGED, classify("if a:\n  b", "if a:\n    b", SIGNIFICANT))
        assertEquals(Content.CHANGED, classify("x = 1", "x  = 1", SIGNIFICANT))
    }

    @Test
    fun `comments are compared by exact text`() {
        WhitespacePolicy.entries.forEach {
            assertEquals(Content.CHANGED, classify("// one\nx;", "// two\nx;", it))
            assertEquals(Content.CHANGED, classify("/* a b */ x;", "/* a  b */ x;", it))
        }
        assertEquals(Content.FORMATTING_ONLY, classify("// one\nx;", "// one\n\n  x;", INSIGNIFICANT))
    }

    @Test
    fun `string contents always count`() {
        WhitespacePolicy.entries.forEach { assertEquals(Content.CHANGED, classify("f(\"a b\");", "f(\"a  b\");", it)) }
    }

    @Test
    fun `adjacent whitespace tokens count as one run`() {
        // A container's own tokens with a child cut out of the middle: the whitespace before and after the child.
        val withChild = tokens("{") + tokens("\n    ") + tokens("\n") + tokens("}")
        val withoutChild = tokens("{\n}")
        assertEquals(Content.FORMATTING_ONLY, ContentClassifier.classify(withChild, withoutChild, LINE_BREAKS_SIGNIFICANT, false))
        assertEquals(Content.FORMATTING_ONLY, ContentClassifier.classify(withChild, withoutChild, INSIGNIFICANT, false))
        assertEquals(Content.FORMATTING_ONLY, ContentClassifier.classify(withChild, withoutChild, INDENTATION_SIGNIFICANT, false))
        assertEquals(Content.CHANGED, ContentClassifier.classify(withChild, withoutChild, SIGNIFICANT, false))
    }

    @Test
    fun `empty own tokens are unchanged`() {
        assertEquals(Content.UNCHANGED, classify("", "", INSIGNIFICANT))
        assertEquals(Content.FORMATTING_ONLY, classify("", "  ", INSIGNIFICANT))
        assertEquals(Content.CHANGED, classify("", ";", INSIGNIFICANT))
    }

    private fun classify(
        old: String,
        new: String,
        policy: WhitespacePolicy,
        parseError: Boolean = false,
        signatureChanged: Boolean = false,
    ) = ContentClassifier.classify(tokens(old), tokens(new), policy, parseError, signatureChanged)
}
