package io.github.agadek.smartdiff.engine.declarations.psi.java

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The Java redundant parentheses rule on the Java lexer's tokens (declarations/08 R12). */
class JavaRedundantParenthesesRuleTest : BasePlatformTestCase() {

    private fun rewritten(code: String): String = texts(JavaEquivalenceRules.RedundantParentheses.rewrite(javaTokens(code)))
    private fun assertRemoved(code: String, expected: String) = assertEquals(code, expected, rewritten(code))
    private fun assertKept(code: String) = assertEquals(code, texts(javaTokens(code)), rewritten(code))

    // declarations/08-equivalence-rules R12
    fun `test the lexer names the token types the table uses`() {
        val types = javaTokens("return (a); throw (e); x = (a); x += (a); x -= (a); x *= (a); x /= (a); x %= (a); x &= (a); x |= (a); x ^= (a); x <<= (a); x >>= (a); x >>>= (a); f((a), b) -> c;")
            .map { it.type }.toSet()
        listOf(
            "RETURN_KEYWORD", "THROW_KEYWORD", "EQ", "PLUSEQ", "MINUSEQ", "ASTERISKEQ", "DIVEQ", "PERCEQ", "ANDEQ", "OREQ",
            "XOREQ", "LTLTEQ", "GT", "LPARENTH", "RPARENTH", "COMMA", "SEMICOLON", "ARROW",
        ).forEach { assertTrue("$it in $types", it in types) }
        // The lexer writes >=, >>= and >>>= as GT tokens followed directly by EQ, and the other comparisons as one token.
        assertEquals(listOf("GT", "GT", "EQ"), javaTokens(">>=").map { it.type })
        assertEquals(listOf("GT", "GT", "GT", "EQ"), javaTokens(">>>=").map { it.type })
        assertEquals(listOf("GT", "EQ"), javaTokens(">=").map { it.type })
        assertEquals(listOf("LE", "EQEQ", "NE"), javaTokens("<===!=").map { it.type })
    }

    // declarations/08-equivalence-rules R12
    fun `test the value of return and throw`() {
        assertRemoved("return (a + b);", "return a + b ;")
        assertRemoved("throw (new E());", "throw new E ( ) ;")
    }

    // declarations/08-equivalence-rules R12
    fun `test the right side of an assignment and an initializer`() {
        assertRemoved("x = (a * b);", "x = a * b ;")
        assertRemoved("int x = (1), y = (2);", "int x = 1 , y = 2 ;")
        assertRemoved("x >>>= (n);", "x > > > = n ;")
        assertRemoved("x >>= (n);", "x > > = n ;")
        assertRemoved("for (int i = (0); i < n; i++) {}", "for ( int i = 0 ; i < n ; i ++ ) { }")
    }

    // declarations/08-equivalence-rules R12
    fun `test a whole argument and a pair directly inside another`() {
        assertRemoved("foo((a + b), (c));", "foo ( a + b , c ) ;")
        assertRemoved("if ((x)) {}", "if ( x ) { }")
    }

    // declarations/08-equivalence-rules R12
    fun `test nested pairs are removed from the inside out`() {
        assertRemoved("return ((a));", "return a ;")
        assertRemoved("x = (((a)));", "x = a ;")
    }

    // declarations/08-equivalence-rules R12
    fun `test a cast keeps its parentheses`() {
        assertKept("x = (int) y;")
        assertKept("return (String) o;")
    }

    // declarations/08-equivalence-rules R12
    fun `test a call keeps its own parentheses`() {
        assertKept("foo(a);")
        assertKept("return foo(a);")
    }

    // declarations/08-equivalence-rules R12
    fun `test a lambda keeps its parameters`() {
        assertKept("x = (a) -> a;")
        assertKept("foo((a) -> a);")
        assertKept("x = (() -> 1);")
    }

    // declarations/08-equivalence-rules R12
    fun `test an operand of an operator keeps its parentheses`() {
        assertKept("x = (a + b) * c;")
        assertKept("return (a) - b;")
        assertKept("x = c * (a + b);")
        assertKept("x = (a).b;")
        assertKept("if (x) {}")
    }

    // declarations/08-equivalence-rules R12
    fun `test a comparison is never an assignment`() {
        assertKept("x = a >= (b == c);")
        assertKept("return a >= (b ? c : d);")
        assertKept("x = a <= (b ? c : d);")
        assertKept("x = a == (b ? c : d);")
        assertKept("x = a != (b ? c : d);")
    }

    // declarations/08-equivalence-rules R12
    fun `test a comma inside keeps the parentheses`() {
        assertKept("foo((a, b) -> c);")
    }

    // declarations/08-equivalence-rules R12
    fun `test comments and whitespace around the pair are skipped`() {
        assertRemoved("return /* v */ ( a ) /* end */ ;", "return /* v */ a /* end */ ;")
        assertRemoved("x =\n    (a)\n;", "x = a ;")
    }
}
