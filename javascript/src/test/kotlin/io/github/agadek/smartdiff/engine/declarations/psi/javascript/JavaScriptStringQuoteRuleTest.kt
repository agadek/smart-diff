package io.github.agadek.smartdiff.engine.declarations.psi.javascript

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The JavaScript string quote and escape rule on the JavaScript and TypeScript lexers' tokens (declarations/11 R5, R20). */
class JavaScriptStringQuoteRuleTest : BasePlatformTestCase() {

    private val rule = JavaScriptEquivalenceRules.StringQuotes
    private fun same(a: String, b: String) = Dialect.entries.forEach { assertTrue("$a ~ $b in $it", sameUnder(rule, "x = $a;", "x = $b;", it)) }
    private fun differ(a: String, b: String) = Dialect.entries.forEach { assertFalse("$a !~ $b in $it", sameUnder(rule, "x = $a;", "x = $b;", it)) }

    // declarations/11-javascript-python-go-equivalence-rules R5, R20
    fun `test the lexers name the string types`() {
        Dialect.entries.forEach { dialect ->
            val types = jsTokens("x = 'a' + `b`;", dialect).map { it.type }.toSet()
            listOf("JS:STRING_LITERAL", "JS:BACKQUOTE", "JS:STRING_TEMPLATE_PART").forEach { assertTrue("$it in $types ($dialect)", it in types) }
        }
    }

    // declarations/11-javascript-python-go-equivalence-rules R5
    fun `test quotes and escapes`() {
        same("'a'", "\"a\"")
        same("\"\\x41\"", "\"A\"")
        same("\"\\u0041\"", "\"A\"")
        same("\"\\u{41}\"", "\"A\"")
        same("'\\u{1F600}'", "'\uD83D\uDE00'")
        same("'\\''", "\"'\"")
        same("\"\\\"\"", "'\"'")
        same("'\\t\\b\\f\\v\\0'", "'\\x09\\x08\\x0C\\x0B\\x00'")
        same("'\\n\\r\\\\'", "'\\x0A\\x0D\\x5C'")
        differ("'a'", "'b'")
    }

    // declarations/11-javascript-python-go-equivalence-rules R5
    fun `test what keeps its text`() {
        assertNull("a line continuation", rule.canonical("'a\\\nb'"))
        assertNull("legacy octal", rule.canonical("'\\1'"))
        assertNull("legacy octal after zero", rule.canonical("'\\01'"))
        assertNull("an identity escape", rule.canonical("'\\d'"))
        assertNull("\\8", rule.canonical("'\\8'"))
        differ("'\\101'", "'A'")
        differ("'\\d'", "'d'")
    }

    // declarations/11-javascript-python-go-equivalence-rules R5
    fun `test a directive keeps its meaning`() {
        assertTrue(sameUnder(rule, "'use strict';\nf();", "\"use strict\";\nf();"))
        assertFalse(sameUnder(rule, "'use\\x20strict';\nf();", "'use strict';\nf();"))
        assertTrue(sameUnder(rule, "function f() {\n  'use strict'\n  return 1\n}", "function f() {\n  \"use strict\"\n  return 1\n}"))
        assertFalse(sameUnder(rule, "function f() {\n  'use\\x20strict'\n}", "function f() {\n  'use strict'\n}"))
        assertFalse(sameUnder(rule, "'a\"b';", "\"a\\\"b\";"))
        // A second directive after the first, ended by ASI with no `;`, keeps its meaning too.
        assertFalse(sameUnder(rule, "'use client'\n'use\\x20strict'\nf()", "'use client'\n'use strict'\nf()"))
        assertTrue(sameUnder(rule, "'use client'\n'use strict'\nf()", "\"use client\"\n\"use strict\"\nf()"))
    }

    // declarations/11-javascript-python-go-equivalence-rules R5
    fun `test template literals are out of scope`() {
        differ("`a`", "'a'")
        differ("`\\x41`", "`A`")
    }

    // declarations/11-javascript-python-go-equivalence-rules R5, R20
    fun `test a JSX attribute value has no escapes`() {
        listOf(Dialect.JS, Dialect.TSX).forEach { dialect ->
            assertFalse(sameUnder(rule, "x = <a b=\"\\x41\"/>;", "x = <a b=\"A\"/>;", dialect))
            assertTrue(sameUnder(rule, "x = <a b={'A'}/>;", "x = <a b={\"A\"}/>;", dialect))
        }
    }

    // declarations/11-javascript-python-go-equivalence-rules R5
    fun `test a string key and an identifier key are out of scope`() {
        differ("{a: 1}", "{'a': 1}")
    }
}
