package io.github.agadek.smartdiff.engine.declarations.psi.kotlin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.declarations.psi.tokenTexts

/** The Kotlin redundant parentheses rule on the Kotlin lexer's tokens (declarations/09 R7, R8). */
class KotlinRedundantParenthesesRuleTest : BasePlatformTestCase() {

    private val rule = KotlinEquivalenceRules.RedundantParentheses
    private fun assertRemoved(code: String, expected: String) = assertEquals(code, expected, rewrittenTexts(rule, code))
    private fun assertKept(code: String) = assertEquals(code, tokenTexts(kotlinTokens(code)), rewrittenTexts(rule, code))

    // declarations/09-kotlin-scala-equivalence-rules R7
    fun `test the lexer names the token types the table uses`() {
        val types = kotlinTokens("return (a); throw (e); x = (a); x += (a); x -= (a); x *= (a); x /= (a); x %= (a); f((a), b) { a -> c }; [x] else")
            .map { it.type }.toSet()
        listOf(
            "return", "throw", "EQ", "PLUSEQ", "MINUSEQ", "MULTEQ", "DIVEQ", "PERCEQ", "LPAR", "RPAR", "COMMA", "SEMICOLON",
            "ARROW", "LBRACE", "RBRACE", "LBRACKET", "RBRACKET", "else", "IDENTIFIER",
        ).forEach { assertTrue("$it in $types", it in types) }
    }

    // declarations/09-kotlin-scala-equivalence-rules R7
    fun `test return and throw`() {
        assertRemoved("return (a + b);", "return a + b ;")
        assertRemoved("throw (IllegalStateException())", "throw IllegalStateException ( )")
        assertRemoved("{ return (a) }", "{ return a }")
    }

    // declarations/09-kotlin-scala-equivalence-rules R7
    fun `test assignments and compound assignments`() {
        assertRemoved("x = (a * b)", "x = a * b")
        assertRemoved("val x = (a * b)", "val x = a * b")
        assertRemoved("fun f() = (a + b)", "fun f ( ) = a + b")
        listOf("+=", "-=", "*=", "/=", "%=").forEach { assertRemoved("x $it (a)", "x $it a") }
        assertRemoved("f(x = (1), y = (2))", "f ( x = 1 , y = 2 )")
    }

    // declarations/09-kotlin-scala-equivalence-rules R7
    fun `test each statement end`() {
        assertRemoved("x = (a); y", "x = a ; y")
        assertRemoved("{ x = (a) }", "{ x = a }")
        assertRemoved("if (c) x = (a) else y", "if ( c ) x = a else y")
        assertRemoved("x = (a)\nfoo()", "x = a foo ( )")
        listOf("val", "var", "fun", "class", "object", "interface", "return", "throw", "if", "when", "for", "while", "do", "try", "this", "super")
            .forEach { assertRemoved("x = (a)\n$it", "x = a $it") }
    }

    // declarations/09-kotlin-scala-equivalence-rules R8
    fun `test a continuing token on the next line keeps the pair`() {
        listOf(".b", "?.b", "::b", "?: b", "&& b", "|| b", "as B", "as? B", "{ it }", "(b)", "+ b", "- b", "[0]")
            .forEach { assertKept("x = (a)\n$it") }
    }

    // declarations/09-kotlin-scala-equivalence-rules R7
    fun `test a line break inside an argument list never ends a statement`() {
        assertKept("f(x = (a)\nfoo)")
        assertRemoved("f(x = (a)\n, b)", "f ( x = a , b )")
    }

    // declarations/09-kotlin-scala-equivalence-rules R7
    fun `test a whole argument, a pair inside another and nested pairs`() {
        assertRemoved("foo((a + b), (c))", "foo ( a + b , c )")
        assertRemoved("if ((x)) {}", "if ( x ) { }")
        assertRemoved("return ((a))", "return a")
        assertRemoved("x = (((a)))", "x = a")
    }

    // declarations/09-kotlin-scala-equivalence-rules R8
    fun `test an operand, a call and an if header keep their parentheses`() {
        assertKept("x = (a + b) * c")
        assertKept("x = c * (a + b)")
        assertKept("return (a) - b")
        assertKept("foo(a)")
        assertKept("if (x) y")
        assertKept("when (x) { else -> y }")
        assertKept("x = -(a)")
    }

    // declarations/09-kotlin-scala-equivalence-rules R8
    fun `test destructuring, lambda parameters and function types keep their parentheses`() {
        assertKept("val (a, b) = p")
        assertKept("for ((x) in xs) {}")
        assertKept("xs.map { (x) -> x }")
        assertKept("xs.map { a, (b), c -> b }")
        assertKept("val f: (Int) -> Unit = g")
        assertKept("fun f(): (Int) = g")
        assertKept("val f = ((Int) -> Unit)::class")
    }

    // declarations/09-kotlin-scala-equivalence-rules R8
    fun `test a labelled return and a top-level comma inside`() {
        assertKept("return@f (x)")
        assertKept("foo((a, b))")
        assertRemoved("x = ({ a -> a })", "x = { a -> a }")
    }

    // declarations/09-kotlin-scala-equivalence-rules R7
    fun `test comments and whitespace around the pair are skipped`() {
        assertRemoved("return /* v */ ( a ) /* end */ ;", "return /* v */ a /* end */ ;")
    }
}
