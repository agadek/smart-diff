package io.github.agadek.smartdiff.engine.declarations.psi.python

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The Python string quote and escape rule on the Python lexer's tokens (declarations/11 R10). */
class PythonStringQuoteRuleTest : BasePlatformTestCase() {

    private val rule = PythonEquivalenceRules.StringQuotes
    private fun same(a: String, b: String) = assertTrue("$a ~ $b", sameUnder(rule, "x = $a\n", "x = $b\n"))
    private fun differ(a: String, b: String) = assertFalse("$a !~ $b", sameUnder(rule, "x = $a\n", "x = $b\n"))

    // declarations/11-javascript-python-go-equivalence-rules R10
    fun `test the lexer names the string types`() {
        val types = pythonTokens("x = 'a' + '''b''' + f'c'\n").map { it.type }.toSet()
        listOf("Py:SINGLE_QUOTED_STRING", "Py:TRIPLE_QUOTED_STRING", "Py:FSTRING_START").forEach { assertTrue("$it in $types", it in types) }
    }

    // declarations/11-javascript-python-go-equivalence-rules R10
    fun `test quotes and the u prefix`() {
        same("'a'", "\"a\"")
        same("u\"a\"", "'a'")
        same("U'a'", "'a'")
        same("'\\''", "\"'\"")
        same("\"\\\"\"", "'\"'")
        differ("'a'", "'b'")
    }

    // declarations/11-javascript-python-go-equivalence-rules R10
    fun `test every escape`() {
        same("\"\\x41\"", "\"A\"")
        same("\"\\101\"", "\"A\"")
        same("'\\u0041'", "'A'")
        same("'\\U00000041'", "'A'")
        same("'\\U0001F600'", "'\uD83D\uDE00'")
        same("'\\t'", "'\\x09'")
        same("'\\a\\b\\f\\v'", "'\\x07\\x08\\x0c\\x0b'")
        same("'\\n\\r'", "'\\x0a\\x0d'")
        same("'\\\\'", "'\\x5c'")
        same("'\\0'", "'\\x00'")
    }

    // declarations/11-javascript-python-go-equivalence-rules R10
    fun `test bytes compare only with bytes, by byte value`() {
        same("b'a'", "b\"\\x61\"")
        same("B'\\141'", "b'a'")
        differ("b'a'", "'a'")
        assertNull("\\u is unknown in bytes", rule.canonical("b'\\u0041'"))
        assertNull("bytes are ASCII", rule.canonical("b'é'"))
    }

    // declarations/11-javascript-python-go-equivalence-rules R10
    fun `test what keeps its text`() {
        assertNull("raw", rule.canonical("r'\\x41'"))
        assertNull("raw bytes", rule.canonical("rb'a'"))
        assertNull("named", rule.canonical("'\\N{DASH}'"))
        assertNull("unknown", rule.canonical("'\\d'"))
        assertNull("too large an octal", rule.canonical("'\\777'"))
        assertNull("a line continuation", rule.canonical("'a\\\nb'"))
        differ("r'A'", "'A'")
        differ("'''A'''", "'A'")
        differ("f'A'", "'A'")
        differ("'\\d'", "'\\\\d'")
    }

    // declarations/11-javascript-python-go-equivalence-rules R10
    fun `test implicit concatenation is out of scope`() {
        differ("'a' 'b'", "'ab'")
    }
}
