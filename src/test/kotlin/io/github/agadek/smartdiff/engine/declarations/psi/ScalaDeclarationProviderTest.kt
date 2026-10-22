package io.github.agadek.smartdiff.engine.declarations.psi

import io.github.agadek.smartdiff.engine.declarations.model.TokenKind

/** The Scala provider's first step: top-level classes and objects through the Scala plugin's PSI and lexer. */
class ScalaDeclarationProviderTest : ProviderTestCase() {

    fun `test top-level classes and objects, in source order`() {
        val text = """
            package demo

            import scala.math.max

            class Shapes(val a: Int) {
              def area(x: Int): Int = max(a, x)
            }

            object Shapes {
              val Default = new Shapes(1)
            }

            case class Point(x: Int, y: Int)
        """.trimIndent() + "\n"

        val tree = build("Scala", text)

        assertTree(
            """
            class Shapes  "class Shapes(val a: Int) {"
            object Shapes  "object Shapes {"
            class Point  "case class Point(x: Int, y: Int)"
            """,
            tree, text,
        )
    }

    fun `test tokens come from the Scala lexer`() {
        val text = "class A {\n  // note\n  val x = \"s\" /* block */\n}\n"

        val tree = build("Scala", text)

        val tokens = tree.fileRegion.tokens + tree.all.flatMap { it.ownTokens }
        assertEquals(listOf("/* block */", "// note"), tokens.filter { it.kind == TokenKind.COMMENT }.map { it.text }.sorted())
        assertTrue(tokens.filter { it.kind == TokenKind.WHITESPACE }.all { it.text.isBlank() })
    }
}
