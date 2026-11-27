package io.github.agadek.smartdiff.engine.declarations.psi.go

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The Go redundant parentheses rule on the Go lexer's tokens (declarations/11 R17, R18). */
class GoRedundantParenthesesRuleTest : BasePlatformTestCase() {

    private val rule = GoEquivalenceRules.RedundantParentheses
    private fun body(code: String) = "package p\nfunc f() {\n\t$code\n}\n"
    private fun same(a: String, b: String) = assertTrue("$a ~ $b", sameUnder(rule, body(a), body(b)))
    private fun differ(a: String, b: String) = assertFalse("$a !~ $b", sameUnder(rule, body(a), body(b)))

    // declarations/11-javascript-python-go-equivalence-rules R17
    fun `test the lexer names the tokens of the table`() {
        val code = GoEquivalenceRules.RedundantParentheses.ASSIGNMENTS.joinToString("\n\t") { "a $it 1" }
        val types = goTokens(body(code)).map { it.type }.toSet()
        (GoEquivalenceRules.RedundantParentheses.ASSIGNMENTS + setOf("(", ")", "{", GoEquivalenceRules.NEW_LINE))
            .forEach { assertTrue("$it in $types", it in types) }
        val more = goTokens(body("return; g(a, b)")).map { it.type }.toSet()
        setOf("return", ";", ",").forEach { assertTrue("$it in $more", it in more) }
    }

    // declarations/11-javascript-python-go-equivalence-rules R17
    fun `test each row of the table`() {
        same("return (a + b)", "return a + b")
        same("return (x), nil", "return x, nil")
        same("x = (a + b)", "x = a + b")
        same("x := (a + b)", "x := a + b")
        same("x &^= (a)", "x &^= a")
        same("x <<= (a)", "x <<= a")
        same("g((a + b))", "g(a + b)")
        same("g(a, (b + c))", "g(a, b + c)")
        same("x := ((a))", "x := a")
        same("x := (a); y := 1", "x := a; y := 1")
        same("if x := (a); x > 0 {}", "if x := a; x > 0 {}")
        same("x := (<-ch)", "x := <-ch")
        same("x := (*p)", "x := *p")
    }

    // declarations/11-javascript-python-go-equivalence-rules R18
    fun `test what keeps its parentheses`() {
        differ("x := (a, b)", "x := a, b")
        differ("x := (T{1})", "x := T{1}")
        differ("x := (*T)(y)", "x := *T(y)")
        differ("x := (a) * b", "x := a * b")
        differ("x := (a).b", "x := a.b")
        differ("x := (a)[0]", "x := a[0]")
    }
}
