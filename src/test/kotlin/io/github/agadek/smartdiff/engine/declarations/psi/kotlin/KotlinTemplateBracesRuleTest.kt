package io.github.agadek.smartdiff.engine.declarations.psi.kotlin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.declarations.psi.tokenTexts

/** The Kotlin template braces rule on the Kotlin lexer's tokens (declarations/09 R6). */
class KotlinTemplateBracesRuleTest : BasePlatformTestCase() {

    private val rule = KotlinEquivalenceRules.TemplateBraces
    private fun same(a: String, b: String) = assertTrue("$a ~ $b", sameUnder(rule, "val x = $a", "val x = $b"))
    private fun differ(a: String, b: String) = assertFalse("$a !~ $b", sameUnder(rule, "val x = $a", "val x = $b"))
    private fun kept(code: String) = assertEquals(code, rewrittenTexts(rule, "val x = $code"), tokenTexts(kotlinTokens("val x = $code")))

    private val d = "$"

    // declarations/09-kotlin-scala-equivalence-rules R6
    fun `test a name and this`() {
        same("\"$d{name}\"", "\"${d}name\"")
        same("\"$d{x}!\"", "\"${d}x!\"")
        same("\"$d{this}\"", "\"${d}this\"")
        same("\"a $d{x} b\"", "\"a $d" + "x b\"")
    }

    // declarations/09-kotlin-scala-equivalence-rules R6
    fun `test whitespace inside the braces`() {
        same("\"$d{ x }!\"", "\"${d}x!\"")
    }

    // declarations/09-kotlin-scala-equivalence-rules R6
    fun `test a following letter, digit or underscore keeps the braces`() {
        kept("\"$d{x}y\"")
        kept("\"$d{x}1\"")
        kept("\"$d{x}_\"")
        differ("\"$d{x}y\"", "\"${d}xy\"")
    }

    // declarations/09-kotlin-scala-equivalence-rules R6
    fun `test punctuation and an escape after the braces`() {
        same("\"$d{x}.y\"", "\"${d}x.y\"")
        same("\"$d{x}\\n\"", "\"${d}x\\n\"")
    }

    // declarations/09-kotlin-scala-equivalence-rules R6
    fun `test expressions keep their braces`() {
        kept("\"$d{x.y}\"")
        kept("\"$d{f()}\"")
        kept("\"$d{`a b`}\"")
        kept("\"$d{this@A}\"")
        kept("\"$d{a + b}\"")
        differ("\"$d{x.y}\"", "\"${d}x.y\"")
    }
}
