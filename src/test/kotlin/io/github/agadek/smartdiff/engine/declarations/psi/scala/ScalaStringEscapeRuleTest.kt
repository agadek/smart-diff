package io.github.agadek.smartdiff.engine.declarations.psi.scala

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The Scala string escape rule on the Scala lexer's tokens (declarations/09 R10). */
class ScalaStringEscapeRuleTest : BasePlatformTestCase() {

    private val rule = ScalaEquivalenceRules.StringEscapes
    private fun same(a: String, b: String) = assertTrue("$a ~ $b", sameUnder(rule, "val x = $a", "val x = $b"))
    private fun differ(a: String, b: String) = assertFalse("$a !~ $b", sameUnder(rule, "val x = $a", "val x = $b"))

    // declarations/09-kotlin-scala-equivalence-rules R10
    fun `test the lexer names the literal types`() {
        val types = scalaTokens("\"a\" 'c'").map { it.type }.toSet()
        listOf("string content", "Character").forEach { assertTrue("$it in $types", it in types) }
    }

    // declarations/09-kotlin-scala-equivalence-rules R10
    fun `test unicode escapes with one or more u`() {
        same("\"\\u0041\"", "\"A\"")
        same("\"\\uuu0041\"", "\"A\"")
        same("'\\t'", "'\\u0009'")
    }

    // declarations/09-kotlin-scala-equivalence-rules R10
    fun `test every simple escape`() {
        same("\"\\b\"", "\"\\u0008\"")
        same("\"\\f\"", "\"\\u000C\"")
        same("\"\\'\"", "\"'\"")
        same("'\\\"'", "'\"'")
        same("\"\\t\"", "\"\\u0009\"")
    }

    // declarations/09-kotlin-scala-equivalence-rules R10
    fun `test a unicode escape of an excluded character keeps the literal`() {
        differ("\"\\u0022\"", "\"\\\"\"")
        differ("'\\u0027'", "'\\''")
        differ("\"\\u005C\\u005C\"", "\"\\\\\"")
        differ("\"\\u000A\"", "\"\\n\"")
        differ("\"\\u000D\"", "\"\\r\"")
    }

    // declarations/09-kotlin-scala-equivalence-rules R10
    fun `test an octal escape keeps the literal`() {
        differ("\"\\101\"", "\"A\"")
    }

    // declarations/09-kotlin-scala-equivalence-rules R10
    fun `test triple-quoted and interpolated strings stay as written`() {
        differ("\"\"\"\\u0041\"\"\"", "\"\"\"A\"\"\"")
        differ("s\"\\u0041\"", "s\"A\"")
        differ("f\"\\u0041\"", "f\"A\"")
        differ("raw\"\\u0041\"", "raw\"A\"")
    }
}
