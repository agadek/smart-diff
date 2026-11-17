package io.github.agadek.smartdiff.engine.declarations.psi.java

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.declarations.model.Token

/** The Java numeric literal rule on the Java lexer's tokens (declarations/08 R10). */
class JavaNumericLiteralRuleTest : BasePlatformTestCase() {

    private fun rewritten(literal: String): Token =
        JavaEquivalenceRules.NumericLiterals.rewrite(javaTokens("x = $literal;")).first { it.text != "x" && it.type.endsWith("LITERAL") }

    private fun assertSame(a: String, b: String) = assertEquals("$a and $b", rewritten(a), rewritten(b))
    private fun assertDifferent(a: String, b: String) = assertFalse("$a and $b", rewritten(a) == rewritten(b))

    // declarations/08-equivalence-rules R10
    fun `test the lexer names the literal types the rule reads`() {
        assertEquals("INTEGER_LITERAL", rewritten("31").type)
        assertEquals("LONG_LITERAL", rewritten("31L").type)
        assertEquals("FLOAT_LITERAL", rewritten("1.0f").type)
        assertEquals("DOUBLE_LITERAL", rewritten("1.0").type)
    }

    // declarations/08-equivalence-rules R10
    fun `test every base of an int is the same value`() {
        listOf("0x1F", "0X1f", "037", "0b1_1111", "0B11111", "3_1").forEach { assertSame(it, "31") }
        assertEquals("31", rewritten("0x1F").text)
    }

    // declarations/08-equivalence-rules R10
    fun `test a long keeps its suffix in upper case`() {
        assertSame("31l", "31L")
        assertSame("0x1FL", "31L")
        assertEquals("31L", rewritten("0x1fl").text)
    }

    // declarations/08-equivalence-rules R10
    fun `test int against long and float against double stay different`() {
        assertDifferent("31", "31L")
        assertDifferent("1.0", "1.0f")
        assertDifferent("1f", "1d")
    }

    // declarations/08-equivalence-rules R10
    fun `test exponents, suffixes and underscores of floating point`() {
        listOf("1e3", "1000.0", "1000d", "1_000.000D", "1E+3", "10e2").forEach { assertSame(it, "1000.0") }
        assertSame("1.5e-1f", "0.15F")
        assertSame(".5", "0.5")
        assertSame("1.", "1.0")
        assertEquals("1000D", rewritten("1e3").text)
    }

    // declarations/08-equivalence-rules R10
    fun `test hex floats are their exact value`() {
        assertSame("0x1p0", "1.0")
        assertSame("0x1.8p1", "3.0")
        assertSame("0X10P-4f", "1.0f")
        assertSame("0x.8p0", "0.5")
    }

    // declarations/08-equivalence-rules R10
    fun `test hex and octal ints are read in two's complement`() {
        assertSame("0xFFFFFFFF", "037777777777")
        assertEquals("-1", rewritten("0xFFFFFFFF").text)
        assertEquals("-1L", rewritten("0xFFFFFFFFFFFFFFFFL").text)
        assertDifferent("0xFFFFFFFF", "0xFFFFFFFFL")
        assertDifferent("0x80000000", "2147483648")
    }

    // declarations/08-equivalence-rules R10
    fun `test values that differ stay different`() {
        assertDifferent("0x1F", "30")
        assertDifferent("010", "10")
        assertDifferent("1.0", "1.0000000000000000000001")
    }

    // declarations/08-equivalence-rules R10
    fun `test a literal that does not parse keeps its text`() {
        assertNull(JavaEquivalenceRules.NumericLiterals.integer("0x"))
        assertNull(JavaEquivalenceRules.NumericLiterals.integer("0x1_0000_0000"))
        assertNull(JavaEquivalenceRules.NumericLiterals.floating("0x1.0"))
        val token = Token("INTEGER_LITERAL", "0x", io.github.agadek.smartdiff.engine.declarations.model.TokenKind.OTHER)
        assertEquals(listOf(token), JavaEquivalenceRules.NumericLiterals.rewrite(listOf(token)))
    }
}
