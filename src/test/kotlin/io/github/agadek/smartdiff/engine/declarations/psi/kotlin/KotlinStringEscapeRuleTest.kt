package io.github.agadek.smartdiff.engine.declarations.psi.kotlin

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The Kotlin string escape rule on the Kotlin lexer's tokens (declarations/09 R5). */
class KotlinStringEscapeRuleTest : BasePlatformTestCase() {

    private val rule = KotlinEquivalenceRules.StringEscapes
    private fun same(a: String, b: String) = assertTrue("$a ~ $b", sameUnder(rule, "val x = $a", "val x = $b"))
    private fun differ(a: String, b: String) = assertFalse("$a !~ $b", sameUnder(rule, "val x = $a", "val x = $b"))

    // declarations/09-kotlin-scala-equivalence-rules R5
    fun `test unicode escapes`() {
        same("\"\\u0041\"", "\"A\"")
        same("\"a\\u00e9b\"", "\"aéb\"")
        same("\"\\u000A\"", "\"\\n\"")
    }

    // declarations/09-kotlin-scala-equivalence-rules R5
    fun `test every simple escape`() {
        same("\"\\t\"", "\"\\u0009\"")
        same("\"\\b\"", "\"\\u0008\"")
        same("\"\\r\"", "\"\\u000d\"")
        same("\"\\'\"", "\"'\"")
        same("\"\\\"\"", "\"\\u0022\"")
        same("\"\\\\\"", "\"\\u005C\"")
    }

    // declarations/09-kotlin-scala-equivalence-rules R5
    fun `test an escaped dollar is a literal dollar but never a template`() {
        same("\"\\$\"", "\"$\"")
        same("\"a\\$ b\"", "\"a$ b\"")
        differ("\"\\${'$'}x\"", "\"${'$'}x\"")
        differ("\"\\${'$'}{x}\"", "\"${'$'}{x}\"")
    }

    // declarations/09-kotlin-scala-equivalence-rules R5
    fun `test parts around a template`() {
        same("\"\\u0041${'$'}x\\u0020B\"", "\"A${'$'}x B\"")
        differ("\"A${'$'}x\"", "\"A${'$'}y\"")
    }

    // declarations/09-kotlin-scala-equivalence-rules R5
    fun `test quotes in strings and characters`() {
        same("'\\''", "'\\u0027'")
        same("'\"'", "'\\\"'")
        same("'\\u0041'", "'A'")
        same("'\\t'", "'\\u0009'")
        differ("'A'", "'B'")
    }

    // declarations/09-kotlin-scala-equivalence-rules R5
    fun `test a raw string stays as written`() {
        differ("\"\"\"\\u0041\"\"\"", "\"\"\"A\"\"\"")
    }

    // declarations/09-kotlin-scala-equivalence-rules R5
    fun `test an unknown escape keeps the run`() {
        differ("\"\\q\"", "\"q\"")
        assertEquals("val x = \" \\q \"", rewrittenTexts(rule, "val x = \"\\q\""))
    }
}
