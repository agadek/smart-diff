package io.github.agadek.smartdiff.engine.declarations.psi.python

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The Python numeric literal rule on the Python lexer's tokens (declarations/11 R9). */
class PythonNumericLiteralRuleTest : BasePlatformTestCase() {

    private val rule = PythonEquivalenceRules.NumericLiterals
    private fun same(a: String, b: String) = assertTrue("$a ~ $b", sameUnder(rule, "x = $a\n", "x = $b\n"))
    private fun differ(a: String, b: String) = assertFalse("$a !~ $b", sameUnder(rule, "x = $a\n", "x = $b\n"))

    // declarations/11-javascript-python-go-equivalence-rules R9
    fun `test the lexer names the literal types`() {
        val types = pythonTokens("x = 1 + 1.0 + 1j\n").map { it.type }.toSet()
        listOf("Py:INTEGER_LITERAL", "Py:FLOAT_LITERAL", "Py:IMAGINARY_LITERAL").forEach { assertTrue("$it in $types", it in types) }
    }

    // declarations/11-javascript-python-go-equivalence-rules R9
    fun `test integers in every base, case and with underscores`() {
        same("0x1F", "31")
        same("0X1f", "31")
        same("0o37", "31")
        same("0O37", "31")
        same("0b1_1111", "31")
        same("0B11111", "31")
        same("1_000_000", "1000000")
        same("000", "0")
        differ("31", "32")
    }

    // declarations/11-javascript-python-go-equivalence-rules R9
    fun `test floats by exact value`() {
        same("1e3", "1000.0")
        same("1E3", "1_000.0")
        same(".5", "0.5")
        same("5.", "5.0")
        same("1.50", "1.5")
        differ("0.1", "0.10000000000000001")
    }

    // declarations/11-javascript-python-go-equivalence-rules R9
    fun `test imaginary literals`() {
        same("1j", "1.0J")
        same("1e3j", "1000j")
        differ("1j", "2j")
    }

    // declarations/11-javascript-python-go-equivalence-rules R9
    fun `test the kinds stay apart`() {
        differ("1", "1.0")
        differ("1.0", "1j")
        differ("1", "1j")
    }

    // declarations/11-javascript-python-go-equivalence-rules R9
    fun `test a Python 2 octal keeps its text`() {
        assertNull(PythonEquivalenceRules.NumericLiterals.integer("017"))
    }
}
