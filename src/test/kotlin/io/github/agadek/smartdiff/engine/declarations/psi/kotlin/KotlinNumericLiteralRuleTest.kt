package io.github.agadek.smartdiff.engine.declarations.psi.kotlin

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The Kotlin numeric literal rule on the Kotlin lexer's tokens (declarations/09 R4). */
class KotlinNumericLiteralRuleTest : BasePlatformTestCase() {

    private val rule = KotlinEquivalenceRules.NumericLiterals
    private fun same(a: String, b: String) = assertTrue("$a ~ $b", sameUnder(rule, "val x = $a", "val x = $b"))
    private fun differ(a: String, b: String) = assertFalse("$a !~ $b", sameUnder(rule, "val x = $a", "val x = $b"))

    // declarations/09-kotlin-scala-equivalence-rules R4
    fun `test the lexer names the literal types`() {
        val types = kotlinTokens("1 1L 0x1F 1.0 1f 1e3").map { it.type }.toSet()
        assertTrue(types.toString(), "INTEGER_LITERAL" in types && "FLOAT_CONSTANT" in types)
    }

    // declarations/09-kotlin-scala-equivalence-rules R4
    fun `test integers in another base, case or grouping`() {
        same("0x1F", "31")
        same("0X1f", "31")
        same("0b1_1111", "31")
        same("0B11111", "0x1f")
        same("1_000_000", "1000000")
        same("0xFFFF_FFFF", "4294967295")
    }

    // declarations/09-kotlin-scala-equivalence-rules R4
    fun `test every suffix and its case`() {
        same("0x1FL", "31L")
        same("31u", "31U")
        same("0x1Fu", "31U")
        same("31uL", "31UL")
        same("0xFFUL", "255uL")
    }

    // declarations/09-kotlin-scala-equivalence-rules R4
    fun `test Int against Long and unsigned`() {
        differ("31", "31L")
        differ("31", "31u")
        differ("31L", "31UL")
    }

    // declarations/09-kotlin-scala-equivalence-rules R4
    fun `test floating point`() {
        same("1e3", "1000.0")
        same("1_000.0", "1000.0")
        same("1.50", "1.5")
        same("1.0F", "1f")
        same("2.5E-1", "0.25")
        differ("1.0", "1.0f")
        differ("0.1f", "0.100000001f")
        differ("1000", "1000.0")
    }

    // declarations/09-kotlin-scala-equivalence-rules R4
    fun `test values that differ`() {
        differ("0x1F", "32")
        differ("1.5", "1.25")
    }
}
