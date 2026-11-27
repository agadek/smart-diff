package io.github.agadek.smartdiff.engine.declarations.psi.go

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The Go trailing comma rule on the Go lexer's tokens (declarations/11 R16). */
class GoTrailingCommaRuleTest : BasePlatformTestCase() {

    private val rule = GoEquivalenceRules.TrailingCommas
    private fun same(a: String, b: String) = assertTrue("$a ~ $b", sameUnder(rule, "package p\n$a\n", "package p\n$b\n"))
    private fun differ(a: String, b: String) = assertFalse("$a !~ $b", sameUnder(rule, "package p\n$a\n", "package p\n$b\n"))

    // declarations/11-javascript-python-go-equivalence-rules R16
    fun `test calls, parameters, results and composite literals`() {
        same("var x = f(a, b,)", "var x = f(a, b)")
        same("func f(a int, b int,) {}", "func f(a int, b int) {}")
        same("func f() (int, error,) { return 0, nil }", "func f() (int, error) { return 0, nil }")
        same("var x = []int{1, 2,}", "var x = []int{1, 2}")
        same("var x = T{A: 1,}", "var x = T{A: 1}")
    }

    // declarations/11-javascript-python-go-equivalence-rules R16
    fun `test a line break before the closing bracket counts`() {
        differ("var x = []int{1, 2,\n}", "var x = []int{1, 2}")
        same("var x = []int{\n\t1,\n\t2,\n}", "var x = []int{\n\t1,\n\t2,\n}")
    }

    // declarations/11-javascript-python-go-equivalence-rules R16
    fun `test a comma before a bracket keeps its place`() {
        differ("type T[P *C,] int", "type T[P *C] int")
        differ("var x = a[1,]", "var x = a[1]")
    }
}
