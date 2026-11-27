package io.github.agadek.smartdiff.engine.declarations.psi.python

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The Python redundant parentheses rule on the Python lexer's tokens (declarations/11 R12, R13). */
class PythonRedundantParenthesesRuleTest : BasePlatformTestCase() {

    private val rule = PythonEquivalenceRules.RedundantParentheses
    private fun same(a: String, b: String) = assertTrue("$a ~ $b", sameUnder(rule, a + "\n", b + "\n"))
    private fun differ(a: String, b: String) = assertFalse("$a !~ $b", sameUnder(rule, a + "\n", b + "\n"))

    // declarations/11-javascript-python-go-equivalence-rules R12
    fun `test the lexer names the assignment operators`() {
        val code = "a = 1\na += 1\na -= 1\na *= 1\na /= 1\na //= 1\na %= 1\na **= 1\na @= 1\na &= 1\na |= 1\na ^= 1\na <<= 1\na >>= 1\n"
        val types = pythonTokens(code).map { it.type }.toSet()
        PythonEquivalenceRules.RedundantParentheses.ASSIGNMENTS.forEach { assertTrue("$it in $types", it in types) }
        val more = pythonTokens("(a := 1)\nyield x\nfor x in y: pass\nf(*a, **b)\nx = 1; y = 2\n").map { it.type }.toSet()
        listOf("Py:COLONEQ", "Py:YIELD_KEYWORD", "Py:FOR_KEYWORD", "Py:MULT", "Py:EXP", "Py:SEMICOLON", "Py:STATEMENT_BREAK")
            .forEach { assertTrue("$it in $more", it in more) }
        assertTrue("Py:RETURN_KEYWORD" in pythonTokens("def f():\n    return 1\n").map { it.type })
    }

    // declarations/11-javascript-python-go-equivalence-rules R12
    fun `test each row of the table`() {
        same("def f():\n    return (a + b)", "def f():\n    return a + b")
        same("x = (a + b)", "x = a + b")
        same("x += (a + b)", "x += a + b")
        same("x: int = (a + b)", "x: int = a + b")
        same("f((a + b))", "f(a + b)")
        same("f(a, (b + c))", "f(a, b + c)")
        same("f(x=(a))", "f(x=a)")
        same("x = (a), b", "x = a, b")
        same("x = ((a))", "x = a")
        same("x = (a); y = 1", "x = a; y = 1")
        same("x = (a)  # note", "x = a  # note")
    }

    // declarations/11-javascript-python-go-equivalence-rules R13
    fun `test what keeps its parentheses`() {
        differ("x = (a, b)", "x = a, b")
        differ("f((x for x in y))", "f(x for x in y)")
        differ("x = (yield)", "x = yield")
        differ("x = (y := 1)", "x = y := 1")
        differ("x = (a) * b", "x = a * b")
        differ("x = (a)[0]", "x = a[0]")
        differ("x = (a).b", "x = a.b")
        differ("x = (a) if b else c", "x = a if b else c")
    }

    // declarations/11-javascript-python-go-equivalence-rules R13
    fun `test content that spans lines keeps its parentheses`() {
        differ("x = (a +\n     b)", "x = a + b")
        differ("x = (a) \\\n    + b", "x = a \\\n    + b")
    }

    // declarations/11-javascript-python-go-equivalence-rules R12
    fun `test a sole argument and a first argument`() {
        same("print((a), end='')", "print(a, end='')")
        differ("f((*a))", "f(*a)")
        differ("f((**a))", "f(**a)")
    }
}
