package io.github.agadek.smartdiff.engine.declarations.psi.scala

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.declarations.psi.tokenTexts

/** The Scala redundant parentheses rule on the Scala lexer's tokens (declarations/09 R11, R12). */
class ScalaRedundantParenthesesRuleTest : BasePlatformTestCase() {

    private val rule = ScalaEquivalenceRules.RedundantParentheses
    private fun assertRemoved(code: String, expected: String, scala3: Boolean = false) =
        assertEquals(code, expected, rewrittenTexts(rule, code, scala3))
    private fun assertKept(code: String, scala3: Boolean = false) =
        assertEquals(code, tokenTexts(scalaTokens(code, scala3)), rewrittenTexts(rule, code, scala3))

    // declarations/09-kotlin-scala-equivalence-rules R11
    fun `test the lexer names the token types the table uses`() {
        val types = scalaTokens("def f = { return (a); throw (e); x = (a); g((a), b); [T]; (xs: _*); y => z } else")
            .map { it.type }.toSet()
        listOf("return", "throw", "=", "(", ")", ",", ";", "{", "}", "[", "]", ":", "_", "=>", "else", "def")
            .forEach { assertTrue("$it in $types", it in types) }
    }

    // declarations/09-kotlin-scala-equivalence-rules R11
    fun `test each row`() {
        assertRemoved("return (a + b)", "return a + b")
        assertRemoved("throw (new E)", "throw new E")
        assertRemoved("def f = (a + b)", "def f = a + b")
        assertRemoved("val x = (a)", "val x = a")
        assertRemoved("g(x = (1), y = (2))", "g ( x = 1 , y = 2 )")
        assertRemoved("g((a + b), (c))", "g ( a + b , c )")
        assertRemoved("val x = ((a))", "val x = a")
    }

    // declarations/09-kotlin-scala-equivalence-rules R11
    fun `test each statement end`() {
        assertRemoved("val x = (a); y", "val x = a ; y")
        assertRemoved("{ val x = (a) }", "{ val x = a }")
        assertRemoved("if (c) x = (a) else y", "if ( c ) x = a else y")
        listOf("val", "var", "def", "type", "class", "object", "trait", "case", "import", "return", "throw", "if", "while", "for", "try")
            .forEach { assertRemoved("val x = (a)\n$it", "val x = a $it") }
    }

    // declarations/09-kotlin-scala-equivalence-rules R12
    fun `test a symbolic operator or an identifier on the next line keeps the pair`() {
        assertKept("val x = (a + b)\n* c", scala3 = true)
        assertKept("val x = (a + b)\nfoo")
        assertKept("val x = (a)\n.b")
        assertKept("val x = (a) match { case _ => 1 }")
    }

    // declarations/09-kotlin-scala-equivalence-rules R12
    fun `test tuples, ascriptions, lambdas, assignments and placeholders keep their parentheses`() {
        assertKept("val x = (a, b)")
        assertKept("val x = (a: Int)")
        assertKept("g((xs: _*))")
        assertKept("g(xs: _*)")
        assertKept("val f = (x => x)")
        assertKept("g((x = 1))")
        assertKept("val f = (_ + 1)")
        assertKept("g((_))")
    }

    // declarations/09-kotlin-scala-equivalence-rules R12
    fun `test operands, calls and if headers keep their parentheses`() {
        assertKept("val x = (a + b) * c")
        assertKept("g(a)")
        assertKept("if (a) b else c")
        assertKept("if (a) then b else c", scala3 = true)
        assertKept("x += (1)")
    }

    // declarations/09-kotlin-scala-equivalence-rules R11
    fun `test a named argument is removed only around its value`() {
        assertRemoved("g(x = (a + b))", "g ( x = a + b )")
    }
}
