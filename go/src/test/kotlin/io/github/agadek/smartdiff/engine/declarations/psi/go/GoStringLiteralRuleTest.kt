package io.github.agadek.smartdiff.engine.declarations.psi.go

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The Go string and rune literal rule on the Go lexer's tokens (declarations/11 R15). */
class GoStringLiteralRuleTest : BasePlatformTestCase() {

    private val rule = GoEquivalenceRules.StringLiterals
    private fun same(a: String, b: String) = assertTrue("$a ~ $b", sameUnder(rule, "package p\nconst x = $a\n", "package p\nconst x = $b\n"))
    private fun differ(a: String, b: String) = assertFalse("$a !~ $b", sameUnder(rule, "package p\nconst x = $a\n", "package p\nconst x = $b\n"))

    // declarations/11-javascript-python-go-equivalence-rules R15
    fun `test the lexer names the literal types`() {
        val types = goTokens("package p\nvar x = \"a\" + `b` + 'c'\n").map { it.type }.toSet()
        listOf("string", "raw_string", "char").forEach { assertTrue("$it in $types", it in types) }
    }

    // declarations/11-javascript-python-go-equivalence-rules R15
    fun `test interpreted and raw strings by their bytes`() {
        same("\"\\x41\"", "\"A\"")
        same("\"\\101\"", "\"A\"")
        same("`A`", "\"A\"")
        same("\"\\u00e9\"", "\"é\"")
        same("\"\\xc3\\xa9\"", "\"é\"")
        same("\"\\U0001F600\"", "\"\uD83D\uDE00\"")
        same("\"\\a\\b\\f\\n\\r\\t\\v\\\\\\\"\"", "\"\\x07\\x08\\x0c\\x0a\\x0d\\x09\\x0b\\x5c\\x22\"")
        same("`a\\n`", "\"a\\\\n\"")
        differ("\"\\xff\"", "\"ÿ\"")
        differ("\"a\"", "\"b\"")
    }

    // declarations/11-javascript-python-go-equivalence-rules R15
    fun `test a raw string drops carriage returns`() {
        assertEquals("a\nb", String(GoEquivalenceRules.StringLiterals.raw("`a\r\nb`")!!))
    }

    // declarations/11-javascript-python-go-equivalence-rules R15
    fun `test runes by the rune`() {
        same("'\\x61'", "'a'")
        same("'\\141'", "'a'")
        same("'\\u0061'", "'a'")
        same("'\\U00000061'", "'a'")
        same("'\\xff'", "'ÿ'")
        same("'\\''", "'\\x27'")
        differ("'a'", "'b'")
        differ("'a'", "\"a\"")
    }

    // declarations/11-javascript-python-go-equivalence-rules R15
    fun `test what keeps its text`() {
        assertNull("a quote that is not this literal's", GoEquivalenceRules.StringLiterals.interpreted("\"\\'\""))
        assertNull("a surrogate", GoEquivalenceRules.StringLiterals.interpreted("\"\\uD800\""))
        assertNull("an unknown escape", GoEquivalenceRules.StringLiterals.interpreted("\"\\q\""))
        assertNull("a short octal", GoEquivalenceRules.StringLiterals.interpreted("\"\\1\""))
        assertNull("a double quote in a rune", GoEquivalenceRules.StringLiterals.rune("'\\\"'"))
    }

    // declarations/11-javascript-python-go-equivalence-rules R15
    fun `test a struct tag is a string literal`() {
        assertTrue(sameUnder(rule, "package p\ntype T struct {\n\tA int `json:\"a\"`\n}\n", "package p\ntype T struct {\n\tA int \"json:\\\"a\\\"\"\n}\n"))
    }
}
