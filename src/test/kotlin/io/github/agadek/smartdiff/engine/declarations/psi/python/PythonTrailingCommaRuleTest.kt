package io.github.agadek.smartdiff.engine.declarations.psi.python

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The Python trailing comma rule on the Python lexer's tokens (declarations/11 R11). */
class PythonTrailingCommaRuleTest : BasePlatformTestCase() {

    private val rule = PythonEquivalenceRules.TrailingCommas
    private fun same(a: String, b: String) = assertTrue("$a ~ $b", sameUnder(rule, a + "\n", b + "\n"))
    private fun differ(a: String, b: String) = assertFalse("$a !~ $b", sameUnder(rule, a + "\n", b + "\n"))

    // declarations/11-javascript-python-go-equivalence-rules R11
    fun `test calls, parameters, bases and imports`() {
        same("f(a, b,)", "f(a, b)")
        same("f(a,)", "f(a)")
        same("g()(a,)", "g()(a)")
        same("def f(a, b,): pass", "def f(a, b): pass")
        same("class C(A, B,): pass", "class C(A, B): pass")
        same("from m import (a, b,)", "from m import (a, b)")
    }

    // declarations/11-javascript-python-go-equivalence-rules R11
    fun `test list, dict and set displays`() {
        same("x = [1, 2,]", "x = [1, 2]")
        same("x = [1,]", "x = [1]")
        same("x = {1: 2,}", "x = {1: 2}")
        same("x = {1,}", "x = {1}")
    }

    // declarations/11-javascript-python-go-equivalence-rules R11
    fun `test the comma that makes a tuple stays`() {
        differ("x = (x,)", "x = (x)")
        differ("return x,", "return x")
        differ("a[1,]", "a[1]")
        differ("t: tuple[int,]", "t: tuple[int]")
        differ("match (x,):\n    case _: pass", "match (x):\n    case _: pass")
    }

    // declarations/11-javascript-python-go-equivalence-rules R11
    fun `test a tuple or a subscript loses the comma when another stays`() {
        same("x = (a, b,)", "x = (a, b)")
        same("a[1, 2,]", "a[1, 2]")
    }

    // declarations/11-javascript-python-go-equivalence-rules R11
    fun `test an f-string replacement field keeps its comma`() {
        differ("x = f'{a,}'", "x = f'{a}'")
    }

    // declarations/11-javascript-python-go-equivalence-rules R11
    fun `test a comma never goes after an opening bracket or another comma`() {
        listOf("f(,)", "x = [,]", "f(a,,)").forEach { code ->
            assertEquals(code, pythonTokens(code), rule.rewrite(pythonTokens(code)))
        }
    }
}
