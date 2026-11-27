package io.github.agadek.smartdiff.engine.declarations.psi.go

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The Go numeric literal rule on the Go lexer's tokens (declarations/11 R14). */
class GoNumericLiteralRuleTest : BasePlatformTestCase() {

    private val rule = GoEquivalenceRules.NumericLiterals
    private fun same(a: String, b: String) = assertTrue("$a ~ $b", sameUnder(rule, "package p\nconst x = $a\n", "package p\nconst x = $b\n"))
    private fun differ(a: String, b: String) = assertFalse("$a !~ $b", sameUnder(rule, "package p\nconst x = $a\n", "package p\nconst x = $b\n"))

    // declarations/11-javascript-python-go-equivalence-rules R14
    fun `test the lexer names the literal types`() {
        val types = goTokens("package p\nvar x = 1 + 0x1 + 0o1 + 01 + 0b1 + 1.0 + 1i + 1.0i + 0x1p0i + 0o1i + 0b1i + 'a'\n").map { it.type }.toSet()
        listOf("int", "hex", "oct", "bin", "float", "decimali", "floati", "char").forEach { assertTrue("$it in $types", it in types) }
    }

    // declarations/11-javascript-python-go-equivalence-rules R14
    fun `test integers in every base`() {
        same("0755", "493")
        same("0o755", "493")
        same("0O755", "493")
        same("0x1F", "31")
        same("0X1f", "31")
        same("0b1_1111", "31")
        same("1_000", "1000")
        differ("31", "32")
    }

    // declarations/11-javascript-python-go-equivalence-rules R14
    fun `test floats, hexadecimal ones included`() {
        same("0x1p-2", "0.25")
        same("0X1P-2", ".25")
        same("1e3", "1000.0")
        same("1_000.5", "1000.50")
        differ("0.1", "0.10000000000000001")
    }

    // declarations/11-javascript-python-go-equivalence-rules R14
    fun `test imaginary literals`() {
        same("0123i", "123i")
        same("1.0i", "1i")
        same("0x1p-2i", "0.25i")
        same("0o17i", "15i")
        same("0b11i", "3i")
        differ("1i", "2i")
    }

    // declarations/11-javascript-python-go-equivalence-rules R14
    fun `test the kinds stay apart`() {
        differ("1", "1.0")
        differ("1i", "1")
        differ("1i", "1.0")
        differ("'a'", "97")
    }
}
