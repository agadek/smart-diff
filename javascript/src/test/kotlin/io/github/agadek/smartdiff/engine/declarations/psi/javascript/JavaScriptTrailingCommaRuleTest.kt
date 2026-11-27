package io.github.agadek.smartdiff.engine.declarations.psi.javascript

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The JavaScript trailing comma rule on the JavaScript and TypeScript lexers' tokens (declarations/11 R6, R20). */
class JavaScriptTrailingCommaRuleTest : BasePlatformTestCase() {

    private val rule = JavaScriptEquivalenceRules.TrailingCommas
    private fun same(a: String, b: String, dialects: List<Dialect> = Dialect.entries) =
        dialects.forEach { assertTrue("$a ~ $b in $it", sameUnder(rule, a, b, it)) }
    private fun differ(a: String, b: String, dialects: List<Dialect> = Dialect.entries) =
        dialects.forEach { assertFalse("$a !~ $b in $it", sameUnder(rule, a, b, it)) }

    // declarations/11-javascript-python-go-equivalence-rules R6
    fun `test arrays, objects, patterns, calls, parameters and imports`() {
        same("x = [1, 2,];", "x = [1, 2];")
        same("x = {a: 1, b,};", "x = {a: 1, b};")
        same("const [a, b,] = c;", "const [a, b] = c;")
        same("const {a, b,} = c;", "const {a, b} = c;")
        same("f(a, b,);", "f(a, b);")
        same("function f(a, b,) {}", "function f(a, b) {}")
        same("import {a, b,} from 'm';", "import {a, b} from 'm';")
        same("export {a, b,};", "export {a, b};")
        same("x = [\n  1,\n  2,\n];", "x = [\n  1,\n  2\n];")
    }

    // declarations/11-javascript-python-go-equivalence-rules R6, R20
    fun `test TypeScript enums, interfaces and type literals`() {
        val ts = listOf(Dialect.TS, Dialect.TSX)
        same("enum E { A, B, }", "enum E { A, B }", ts)
        same("interface I { a: string, b: number, }", "interface I { a: string, b: number }", ts)
        same("type T = { a: string, };", "type T = { a: string };", ts)
    }

    // declarations/11-javascript-python-go-equivalence-rules R6
    fun `test an array hole keeps its comma`() {
        differ("x = [1,,];", "x = [1,];")
        differ("x = [,];", "x = [];")
        same("x = [1,,2,];", "x = [1,,2];")
    }

    // declarations/11-javascript-python-go-equivalence-rules R6, R20
    fun `test a type parameter list keeps its comma`() {
        differ("const f = <T,>(x: T) => x;", "const f = <T>(x: T) => x;", listOf(Dialect.TS, Dialect.TSX))
    }
}
