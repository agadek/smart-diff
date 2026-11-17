package io.github.agadek.smartdiff.engine.declarations.psi.scala

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The Scala numeric literal rule on the Scala lexer's tokens (declarations/09 R9). */
class ScalaNumericLiteralRuleTest : BasePlatformTestCase() {

    private val rule = ScalaEquivalenceRules.NumericLiterals
    private fun same(a: String, b: String) = assertTrue("$a ~ $b", sameUnder(rule, "val x = $a", "val x = $b"))
    private fun differ(a: String, b: String) = assertFalse("$a !~ $b", sameUnder(rule, "val x = $a", "val x = $b"))

    // declarations/09-kotlin-scala-equivalence-rules R9
    fun `test the lexer names the literal types`() {
        val types = scalaTokens("1 1L 0x1F 1.0 1f 1d").map { it.type }.toSet()
        listOf("integer", "long", "float", "double").forEach { assertTrue("$it in $types", it in types) }
    }

    // declarations/09-kotlin-scala-equivalence-rules R9
    fun `test hex and decimal, underscores and case`() {
        same("0x1F", "31")
        same("0X1f", "31")
        same("1_000_000", "1000000")
        same("0x7FFFFFFF", "2147483647")
        same("0x1Fl", "31L")
        same("31l", "31L")
    }

    // declarations/09-kotlin-scala-equivalence-rules R9
    fun `test a hex literal is its two's-complement bit pattern`() {
        same("0xFFFFFFFF", "-1".let { "0xffffffff" })
        assertEquals("-1", ScalaEquivalenceRules.NumericLiterals.integer("0xFFFFFFFF"))
        assertEquals("-1L", ScalaEquivalenceRules.NumericLiterals.integer("0xFFFFFFFFFFFFFFFFL"))
        assertEquals("4294967295L", ScalaEquivalenceRules.NumericLiterals.integer("0xFFFFFFFFL"))
        differ("0xFFFFFFFF", "4294967295L")
    }

    // declarations/09-kotlin-scala-equivalence-rules R9
    fun `test Int against Long`() {
        differ("31", "31L")
    }

    // declarations/09-kotlin-scala-equivalence-rules R9
    fun `test floating point with f, d and no suffix`() {
        same("1.0", "1d")
        same("1.0", "1e0")
        same("1.0D", "1.0")
        same("1.5f", "1.50F")
        same("1_000.0", "1e3")
        differ("1.0", "1.0f")
        differ("1.5", "1.25")
    }

    // declarations/09-kotlin-scala-equivalence-rules R9
    fun `test binary and leading-zero literals keep their text`() {
        assertNull(ScalaEquivalenceRules.NumericLiterals.integer("0b11111"))
        assertNull(ScalaEquivalenceRules.NumericLiterals.integer("017"))
        assertEquals("0", ScalaEquivalenceRules.NumericLiterals.integer("0"))
    }
}
