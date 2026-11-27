package io.github.agadek.smartdiff.engine.declarations.psi.javascript

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The JavaScript numeric literal rule on the JavaScript and TypeScript lexers' tokens (declarations/11 R4, R20). */
class JavaScriptNumericLiteralRuleTest : BasePlatformTestCase() {

    private val rule = JavaScriptEquivalenceRules.NumericLiterals
    private fun same(a: String, b: String) = Dialect.entries.forEach { assertTrue("$a ~ $b in $it", sameUnder(rule, "x = $a;", "x = $b;", it)) }
    private fun differ(a: String, b: String) = Dialect.entries.forEach { assertFalse("$a !~ $b in $it", sameUnder(rule, "x = $a;", "x = $b;", it)) }

    // declarations/11-javascript-python-go-equivalence-rules R4, R20
    fun `test the lexers name the literal type`() {
        Dialect.entries.forEach { dialect ->
            assertEquals(listOf("JS:NUMERIC_LITERAL"), jsTokens("0x1F", dialect).map { it.type })
            assertEquals(listOf("JS:NUMERIC_LITERAL"), jsTokens("31n", dialect).map { it.type })
        }
    }

    // declarations/11-javascript-python-go-equivalence-rules R4
    fun `test every Number form is its exact value`() {
        listOf("31.0", "0x1F", "0X1f", "0o37", "0O37", "0b1_1111", "0B11111", "3.1e1", "3.1E+1", "310e-1", "3_1").forEach { same(it, "31") }
        same(".5", "0.5")
        same("5.", "5")
        same("1_000_000", "1e6")
    }

    // declarations/11-javascript-python-go-equivalence-rules R4
    fun `test BigInt against Number and BigInt bases`() {
        differ("31", "31n")
        same("0x1Fn", "31n")
        same("0b11111n", "31n")
        same("1_0n", "10n")
    }

    // declarations/11-javascript-python-go-equivalence-rules R4
    fun `test legacy octal and legacy decimal keep their text`() {
        assertNull(rule.canonical("017"))
        assertNull(rule.canonical("08"))
        differ("017", "15")
        differ("08", "8")
    }

    // declarations/11-javascript-python-go-equivalence-rules R4
    fun `test exact values that round to the same double stay different`() {
        differ("9007199254740993", "9007199254740992")
        differ("0.1", "0.10000000000000001")
    }
}
