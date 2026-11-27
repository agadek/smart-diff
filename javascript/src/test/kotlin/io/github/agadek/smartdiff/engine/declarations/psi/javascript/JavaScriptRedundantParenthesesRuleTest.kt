package io.github.agadek.smartdiff.engine.declarations.psi.javascript

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The JavaScript redundant parentheses rule on the JavaScript and TypeScript lexers' tokens (declarations/11 R7, R8, R20). */
class JavaScriptRedundantParenthesesRuleTest : BasePlatformTestCase() {

    private val rule = JavaScriptEquivalenceRules.RedundantParentheses
    private fun same(a: String, b: String) = Dialect.entries.forEach { assertTrue("$a ~ $b in $it", sameUnder(rule, a, b, it)) }
    private fun differ(a: String, b: String) = Dialect.entries.forEach { assertFalse("$a !~ $b in $it", sameUnder(rule, a, b, it)) }

    // declarations/11-javascript-python-go-equivalence-rules R7, R20
    fun `test the lexers name the tokens of the table`() {
        val ops = "=,+=,-=,*=,/=,%=,**=,<<=,>>=,>>>=,&=,|=,^=,&&=,||=,??=".split(',')
        Dialect.entries.forEach { dialect ->
            val types = jsTokens(ops.joinToString("\n") { "a $it 1;" }, dialect).map { it.type }.toSet()
            JavaScriptEquivalenceRules.RedundantParentheses.ASSIGNMENTS.forEach { assertTrue("$it in $types ($dialect)", it in types) }
            val keywords = jsTokens(
                "const let var function class return throw if for while do try switch break continue import export in",
                dialect,
            ).map { it.type }.toSet()
            (JavaScriptEquivalenceRules.RedundantParentheses.STARTS_STATEMENT + "JS:IN_KEYWORD")
                .forEach { assertTrue("$it in $keywords ($dialect)", it in keywords) }
        }
    }

    // declarations/11-javascript-python-go-equivalence-rules R7
    fun `test each row of the table`() {
        same("function f() { return (a + b); }", "function f() { return a + b; }")
        same("function f() { throw (new E()); }", "function f() { throw new E(); }")
        same("x = (a + b);", "x = a + b;")
        same("x ??= (a || b);", "x ??= a || b;")
        same("x **= (a);", "x **= a;")
        same("let x = (1), y = 2;", "let x = 1, y = 2;")
        same("f((a + b));", "f(a + b);")
        same("f(a, (b + c));", "f(a, b + c);")
        same("x = ((a));", "x = a;")
        same("if ((a)) {}", "if (a) {}")
    }

    // declarations/11-javascript-python-go-equivalence-rules R7
    fun `test each statement end`() {
        same("{ x = (a) }", "{ x = a }")
        same("x = (a)", "x = a")
        same("x = (a)\nconst y = 1", "x = a\nconst y = 1")
        same("x = (a)\nreturn y", "x = a\nreturn y")
        differ("x = (a)\ny()", "x = a\ny()")
        differ("x = (a)\n(b)", "x = a\n(b)")
        differ("x = (a)\n[1]", "x = a\n[1]")
    }

    // declarations/11-javascript-python-go-equivalence-rules R8
    fun `test a value on the line after return keeps its parentheses`() {
        differ("function f() { return (\n  x\n); }", "function f() { return x; }")
        differ("function f() { throw (\n  e\n); }", "function f() { throw e; }")
        same("function f() { return (x\n); }", "function f() { return x\n; }")
    }

    // declarations/11-javascript-python-go-equivalence-rules R8
    fun `test what keeps its parentheses`() {
        differ("x = (0, obj.f)();", "x = 0, obj.f();")
        differ("x = (a, b);", "x = a, b;")
        differ("for (let i = (a in b);;) {}", "for (let i = a in b;;) {}")
        differ("x = (a) * b;", "x = a * b;")
        differ("x = (a).b;", "x = a.b;")
        differ("x = (function () {})();", "x = function () {}();")
        differ("f = (a) => a;", "f = a => a;")
        differ("f = () => ({});", "f = () => {};")
        differ("x = typeof (a);", "x = typeof a;")
        differ("x = (a)?.b;", "x = a?.b;")
    }

    // declarations/11-javascript-python-go-equivalence-rules R7
    fun `test a template substitution is a bracket`() {
        same("x = `\${(a)}` + (b);", "x = `\${(a)}` + (b);")
        same("x = f(`\${a}`, (b));", "x = f(`\${a}`, b);")
    }
}
