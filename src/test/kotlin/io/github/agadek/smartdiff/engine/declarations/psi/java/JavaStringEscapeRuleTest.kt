package io.github.agadek.smartdiff.engine.declarations.psi.java

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.declarations.model.Token

/** The Java string escape rule on the Java lexer's tokens (declarations/08 R11). */
class JavaStringEscapeRuleTest : BasePlatformTestCase() {

    private fun rewritten(literal: String): Token =
        JavaEquivalenceRules.StringEscapes.rewrite(javaTokens("x = $literal;")).first { it.type.endsWith("LITERAL") }

    private fun assertSame(a: String, b: String) = assertEquals("$a and $b", rewritten(a), rewritten(b))
    private fun assertDifferent(a: String, b: String) = assertFalse("$a and $b", rewritten(a) == rewritten(b))

    // declarations/08-equivalence-rules R11
    fun `test the lexer names the literal types the rule reads`() {
        assertEquals("STRING_LITERAL", rewritten("\"a\"").type)
        assertEquals("CHARACTER_LITERAL", rewritten("'a'").type)
        assertEquals("TEXT_BLOCK_LITERAL", rewritten("\"\"\"\n  a\n  \"\"\"").type)
    }

    // declarations/08-equivalence-rules R11
    fun `test unicode escapes with several u are the character`() {
        assertSame("\"\\u0041\"", "\"A\"")
        assertSame("\"\\uuu0041\"", "\"A\"")
        assertSame("\"\\u00e9t\\u00E9\"", "\"été\"")
    }

    // declarations/08-equivalence-rules R11
    fun `test octal escapes are the character`() {
        assertSame("\"\\101\"", "\"A\"")
        assertSame("\"\\0\"", "\"\\u0000\"")
        assertSame("\"\\477\"", "\"'7\"") // \47 is the quote, then 7: a three-digit octal escape starts with 0 to 3
        assertSame("'\\047'", "'\\''")
    }

    // declarations/08-equivalence-rules R11
    fun `test every simple escape is the character it stands for`() {
        assertSame("\"\\t\"", "\"\\u0009\"")
        assertSame("\"\\n\"", "\"\\012\"")
        assertSame("\"\\r\"", "\"\\015\"")
        assertSame("\"\\b\"", "\"\\u0008\"")
        assertSame("\"\\f\"", "\"\\u000C\"")
        assertSame("\"\\\\\"", "\"\\134\"")
        assertSame("\"\\s\"", "\" \"")
        assertSame("\"\\s\"", "\"\\040\"")
    }

    // declarations/08-equivalence-rules R11
    fun `test quotes in strings and characters`() {
        assertSame("\"\\'\"", "\"'\"")
        assertSame("\"\\\"\"", "\"\\042\"")
        assertSame("'\"'", "'\\\"'")
        assertSame("'\\''", "'\\47'")
    }

    // declarations/08-equivalence-rules R11
    fun `test a different character stays different`() {
        assertDifferent("\"\\u0041\"", "\"B\"")
        assertDifferent("'a'", "'b'")
        assertDifferent("\"a\"", "'a'")
    }

    // declarations/08-equivalence-rules R11; text blocks are out of scope
    fun `test a text block is left unchanged`() {
        val block = "\"\"\"\n  \\u0041\n  \"\"\""
        assertEquals(block, rewritten(block).text)
    }

    // declarations/08-equivalence-rules R11
    fun `test an unknown escape keeps the literal as written`() {
        assertNull(JavaEquivalenceRules.StringEscapes.canonical("\"\\q\"", '"'))
        assertNull(JavaEquivalenceRules.StringEscapes.canonical("\"\\u00\"", '"'))
        val token = Token("STRING_LITERAL", "\"\\q\"", io.github.agadek.smartdiff.engine.declarations.model.TokenKind.OTHER)
        assertEquals(listOf(token), JavaEquivalenceRules.StringEscapes.rewrite(listOf(token)))
    }
}
