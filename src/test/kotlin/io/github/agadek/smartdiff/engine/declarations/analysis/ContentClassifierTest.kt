package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.RenameNameMapping
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy.INDENTATION_SIGNIFICANT
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy.INSIGNIFICANT
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy.KOTLIN_LINE_BREAKS
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy.LINE_BREAKS_SIGNIFICANT
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy.SCALA2_LINE_BREAKS
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy.SCALA3_INDENTATION
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy.SEPARATION_SIGNIFICANT
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy.SIGNIFICANT
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Classification steps and whitespace policies (technical specification §8). */
class ContentClassifierTest {

    @Test
    fun `identical tokens are unchanged under every policy`() {
        WhitespacePolicy.entries.forEach { assertEquals(Content.UNCHANGED, classify("int  x = 1;", "int  x = 1;", it)) }
    }

    @Test
    fun `a parse error wins over a change, including a signature change`() {
        assertEquals(Content.UNANALYZED, classify("{ a; }", "{ b; }", INSIGNIFICANT, parseError = true))
        assertEquals(Content.UNANALYZED, classify("{ a; }", "{ b; }", INSIGNIFICANT, parseError = true, signatureChanged = true))
        assertEquals(Content.UNANALYZED, classify("{ a; }", "{ a; }", INSIGNIFICANT, parseError = true, signatureChanged = true))
        assertEquals(Content.UNANALYZED, classify("{ a; }", "{  a; }", INSIGNIFICANT, parseError = true))
    }

    @Test
    fun `identical tokens are unchanged even with a parse error`() {
        WhitespacePolicy.entries.forEach { assertEquals(Content.UNCHANGED, classify("{ a; ", "{ a; ", it, parseError = true)) }
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
    fun `separation policy keeps separator presence and line breaks but not amount`() {
        assertEquals(Content.FORMATTING_ONLY, classify("echo hi", "echo   hi", SEPARATION_SIGNIFICANT))
        assertEquals(Content.FORMATTING_ONLY, classify("foo(\n  a)", "foo(\n\n      a)", SEPARATION_SIGNIFICANT))
        assertEquals(Content.CHANGED, classify("a=1", "a = 1", SEPARATION_SIGNIFICANT))
        assertEquals(Content.CHANGED, classify("[ -n x ]", "[-n x]", SEPARATION_SIGNIFICANT))
        assertEquals(Content.CHANGED, classify("echo hi >out", "echo hi > out", SEPARATION_SIGNIFICANT))
        assertEquals(Content.CHANGED, classify("foo(\n  a)", "foo(a)", SEPARATION_SIGNIFICANT))
        assertEquals(Content.CHANGED, classify("val x = a + b", "val x = a\n+ b", SEPARATION_SIGNIFICANT))
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
        assertEquals(Content.FORMATTING_ONLY, ContentClassifier.classify(withChild, withoutChild, SEPARATION_SIGNIFICANT, false))
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

    // declarations/07-kotlin-scala-formatting R1: a break inside parentheses or square brackets is formatting-only.
    @Test
    fun `R1 wrapped argument and parameter lists are formatting-only in Kotlin and Scala 2`() {
        for (policy in listOf(KOTLIN_LINE_BREAKS, SCALA2_LINE_BREAKS)) {
            assertEquals(Content.FORMATTING_ONLY, classify("foo(a, b)", "foo(\n  a,\n  b\n)", policy))
            assertEquals(Content.FORMATTING_ONLY, classify("items[0][1]", "items[\n  0\n][\n  1\n]", policy))
            assertEquals(Content.FORMATTING_ONLY, classify("fun f(a: Int, b: Int) {}", "fun f(\n  a: Int,\n  b: Int\n) {}", policy))
            assertEquals(Content.FORMATTING_ONLY, classify("foo(\n  a)", "foo(\n\n      a)", policy))
            assertEquals(Content.CHANGED, classify("foo(a, b)", "foo(a, b + 1)", policy))
        }
    }

    // declarations/07-kotlin-scala-formatting R2: a break after a continuing token is formatting-only.
    @Test
    fun `R2 a break after comma opening bracket equals arrow or dot is formatting-only`() {
        assertEquals(Content.FORMATTING_ONLY, classify("foo(a, b)", "foo(a,\n    b)", KOTLIN_LINE_BREAKS))
        assertEquals(Content.FORMATTING_ONLY, classify("val x = 1", "val x =\n    1", KOTLIN_LINE_BREAKS))
        assertEquals(Content.FORMATTING_ONLY, classify("def f = 1", "def f =\n  1", SCALA2_LINE_BREAKS))
        // The token before the run is a single operator token, as the lexer emits it.
        assertEquals(
            Content.FORMATTING_ONLY,
            cls(listOf(op("it"), ws(" "), op("->"), ws("\n"), op("it")), listOf(op("it"), ws(" "), op("->"), ws(" "), op("it")), KOTLIN_LINE_BREAKS),
        )
        assertEquals(
            Content.FORMATTING_ONLY,
            cls(listOf(op("x"), ws(" "), op("=>"), ws("\n"), op("x")), listOf(op("x"), ws(" "), op("=>"), ws(" "), op("x")), SCALA2_LINE_BREAKS),
        )
        assertEquals(
            Content.FORMATTING_ONLY,
            cls(listOf(op("a"), ws("\n"), op("."), op("b")), listOf(op("a"), op("."), op("b")), KOTLIN_LINE_BREAKS),
        )
    }

    // declarations/07-kotlin-scala-formatting R3: a break before a closing token is formatting-only.
    @Test
    fun `R3 a break before close comma dot or short-circuit operator is formatting-only`() {
        for (policy in listOf(KOTLIN_LINE_BREAKS, SCALA2_LINE_BREAKS)) {
            assertEquals(Content.FORMATTING_ONLY, classify("foo(a)", "foo(a\n)", policy))
            assertEquals(Content.FORMATTING_ONLY, classify("foo(a, b)", "foo(a\n, b)", policy))
            assertEquals(Content.FORMATTING_ONLY, classify("a.b()", "a\n  .b()", policy))
        }
        // Multi-character operators are single lexer tokens, so they are built explicitly here.
        for (operator in listOf("?.", "?:", "&&", "||")) {
            assertEquals(
                Content.FORMATTING_ONLY,
                cls(listOf(op("a"), ws("\n"), op(operator), op("b")), listOf(op("a"), op(operator), op("b")), KOTLIN_LINE_BREAKS),
            )
        }
        assertEquals(
            Content.FORMATTING_ONLY,
            cls(listOf(op("f"), op("("), op("a"), ws("\n"), op(")")), listOf(op("f"), op("("), op("a"), op(")")), SCALA2_LINE_BREAKS),
        )
    }

    // declarations/07-kotlin-scala-formatting R4: braces inside parentheses are compared as a block.
    @Test
    fun `R4 a break between statements in a lambda inside parentheses stays changed`() {
        // Both sides keep the `;` separator; only the break after it differs. The lambda's `{` is on top of the
        // bracket stack, so this is compared as in a block, not as inside foo's parentheses.
        assertEquals(Content.CHANGED, classify("foo { a(); b() }", "foo { a();\n  b() }", KOTLIN_LINE_BREAKS))
        assertEquals(Content.CHANGED, classify("foo({ a(); b() })", "foo({ a();\n  b() })", KOTLIN_LINE_BREAKS))
        assertEquals(Content.CHANGED, classify("foo { a(); b() }", "foo { a();\n  b() }", SCALA2_LINE_BREAKS))
        // A break right after `{` is fine: the brace is a continuing token, not a block statement separator.
        assertEquals(Content.FORMATTING_ONLY, classify("foo { a() }", "foo {\n  a()\n}", KOTLIN_LINE_BREAKS))
    }

    // declarations/07-kotlin-scala-formatting R5: other breaks stay changed.
    @Test
    fun `R5 other line breaks stay changed`() {
        for (policy in listOf(KOTLIN_LINE_BREAKS, SCALA2_LINE_BREAKS)) {
            assertEquals(Content.CHANGED, classify("val x = a + b", "val x = a\n + b", policy))
            assertEquals(Content.CHANGED, classify("fun f() {}", "fun f()\n{}", policy))
        }
    }

    // declarations/07-kotlin-scala-formatting R6, R7: only the Scala 3 bracket positions are ignored.
    @Test
    fun `R6 R7 Scala 3 ignores bracket breaks but not other breaks or indentation`() {
        assertEquals(Content.FORMATTING_ONLY, classify("f(a, b)", "f(\n  a,\n  b\n)", SCALA3_INDENTATION))
        assertEquals(Content.FORMATTING_ONLY, classify("f(foo(a))", "f(\n  foo(\n    a\n  )\n)", SCALA3_INDENTATION))
        assertEquals(Content.CHANGED, classify("f(x => x)", "f(x =>\n  x)", SCALA3_INDENTATION))
        assertEquals(Content.CHANGED, classify("def f =\n  a\n  b", "def f =\n    a\n  b", SCALA3_INDENTATION))
        assertEquals(Content.CHANGED, classify("f(a\n, b)", "f(a, b)", SCALA3_INDENTATION))
    }

    // declarations/07-kotlin-scala-formatting R8: a uniform whole-declaration shift is formatting-only.
    @Test
    fun `R8 a whole Scala 3 declaration shifted by two spaces is formatting-only`() {
        val old = "\n  def f(a: Int): Int =\n    a + 1"
        val new = "\n    def f(a: Int): Int =\n      a + 1"
        assertEquals(Content.FORMATTING_ONLY, classify(old, new, SCALA3_INDENTATION))
        // A non-uniform prefix falls back to comparing indentation as written.
        assertEquals(Content.CHANGED, classify(old, "\n\tdef f(a: Int): Int =\n    a + 1", SCALA3_INDENTATION))
        // Shifting only some lines is not the whole declaration.
        assertEquals(Content.CHANGED, classify(old, "\n  def f(a: Int): Int =\n      a + 1", SCALA3_INDENTATION))
    }

    // declarations/07-kotlin-scala-formatting R9: a parse error is never formatting-only under the new rules.
    @Test
    fun `R9 a parse error with wrapped arguments is unanalyzed`() {
        assertEquals(Content.UNANALYZED, classify("foo(a, b)", "foo(\n a,\n b\n)", KOTLIN_LINE_BREAKS, parseError = true))
        assertEquals(Content.UNANALYZED, classify("f(a, b)", "f(\n a,\n b\n)", SCALA2_LINE_BREAKS, parseError = true))
        assertEquals(Content.UNANALYZED, classify("f(a, b)", "f(\n a,\n b\n)", SCALA3_INDENTATION, parseError = true))
    }

    // declarations/07-kotlin-scala-formatting R10: the PSI and text normalizers agree on every test input.
    @Test
    fun `R10 the PSI and text paths agree on every classifier input`() {
        val cases = listOf(
            Triple("foo(a, b)", "foo(\n  a,\n  b\n)", KOTLIN_LINE_BREAKS),
            Triple("val x = 1", "val x =\n  1", KOTLIN_LINE_BREAKS),
            Triple("a.b()", "a\n  .b()", KOTLIN_LINE_BREAKS),
            Triple("a?.b()", "a\n  ?.b()", KOTLIN_LINE_BREAKS),
            Triple("a && b", "a\n  && b", KOTLIN_LINE_BREAKS),
            Triple("foo { it -> it }", "foo { it ->\n  it }", KOTLIN_LINE_BREAKS),
            Triple("a + b", "a +\n  b", KOTLIN_LINE_BREAKS),
            Triple("a + b", "a\n  + b", KOTLIN_LINE_BREAKS),
            Triple("foo { a(); b() }", "foo { a()\n  b() }", KOTLIN_LINE_BREAKS),
            Triple("f(x => x)", "f(x =>\n  x)", SCALA2_LINE_BREAKS),
            Triple("def f = 1", "def f =\n  1", SCALA2_LINE_BREAKS),
            Triple("a.b", "a\n  .b", SCALA2_LINE_BREAKS),
            Triple("f(a, b)", "f(\n  a,\n  b\n)", SCALA3_INDENTATION),
            Triple("f(x => x)", "f(x =>\n  x)", SCALA3_INDENTATION),
            Triple("\n  def f =\n    a", "\n    def f =\n      a", SCALA3_INDENTATION),
        )
        for ((old, new, policy) in cases) {
            val psi = classifyTokens(psiTokens(old), psiTokens(new), policy) != Content.CHANGED
            val text = RenameNameMapping.sameText(old, new, policy)
            assertEquals("PSI and text paths disagree for '$old' → '$new' under $policy", psi, text)
        }
    }

    private fun op(text: String) = Token("OP", text, TokenKind.OTHER)
    private fun ws(text: String) = Token("WHITE_SPACE", text, TokenKind.WHITESPACE)
    private fun cls(old: List<Token>, new: List<Token>, policy: WhitespacePolicy) =
        ContentClassifier.classify(old, new, policy, false)

    /** Tokens the way the Kotlin and Scala lexers emit them, with operators kept whole. */
    private fun psiTokens(text: String): List<Token> {
        val regex = Regex(
            """\s+|//[^\n]*|/\*.*?\*/|"[^"]*"|->|=>|\?\.|\?:|&&|\|\||\.\.|\w+|.""",
            RegexOption.DOT_MATCHES_ALL,
        )
        return regex.findAll(text).map { match ->
            val value = match.value
            Token("T", value, if (value.isBlank()) TokenKind.WHITESPACE else TokenKind.OTHER)
        }.toList()
    }

    private fun classifyTokens(
        old: List<Token>,
        new: List<Token>,
        policy: WhitespacePolicy,
    ) = ContentClassifier.classify(old, new, policy, false)

    private fun classify(
        old: String,
        new: String,
        policy: WhitespacePolicy,
        parseError: Boolean = false,
        signatureChanged: Boolean = false,
    ) = ContentClassifier.classify(tokens(old), tokens(new), policy, parseError, signatureChanged)
}
