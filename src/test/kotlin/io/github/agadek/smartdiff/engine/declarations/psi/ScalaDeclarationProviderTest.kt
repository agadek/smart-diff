package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy

/**
 * The Scala provider's first step: top-level classes and objects through the Scala plugin's PSI and lexer, in the
 * dialect chosen from both sides (technical specification §6.2, spike S4).
 */
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

    fun `test Scala 3 syntax is parsed as Scala 3, without errors`() {
        val text = SCALA_3 + "class After\n"

        assertEquals("Scala 3", dialect(text, text))
        val tree = build("Scala", text)

        assertFalse(tree.fileRegion.hasParseError || tree.all.any { it.hasParseError })
        assertEquals(listOf("A", "After"), tree.topLevel.map { it.name }.filter { it != "Color" })
        assertEquals(WhitespacePolicy.INDENTATION_SIGNIFICANT, tree.whitespacePolicy)
    }

    fun `test Scala 2 code that Scala 3 also parses goes to Scala 3`() {
        val procedureSyntax = "object B { def f() { println(1) } }\n"

        assertEquals("Scala 3", dialect(procedureSyntax, procedureSyntax))
    }

    fun `test code Scala 3 dropped is parsed as Scala 2`() {
        assertEquals("Scala", dialect(SCALA_2_ONLY, SCALA_2_ONLY))
        val tree = build("Scala", SCALA_2_ONLY)

        assertFalse(tree.fileRegion.hasParseError || tree.all.any { it.hasParseError })
        assertEquals(WhitespacePolicy.LINE_BREAKS_SIGNIFICANT, tree.whitespacePolicy)
    }

    fun `test both sides are parsed in the dialect with fewer errors on both`() {
        val plain = "object Plain { def f() = 1 }\n"

        assertEquals("Scala", dialect(SCALA_2_ONLY, plain))
        assertEquals("Scala", dialect(plain, SCALA_2_ONLY))
        assertEquals("Scala 3", dialect(SCALA_3, plain))
    }

    fun `test a Scala 3 indentation change is never formatting-only`() {
        val old = "object A:\n  def f =\n    println(1)\n    println(2)\n"
        val new = "object A:\n  def f =\n    println(1)\n  println(2)\n"

        val result = compare(old, new)

        assertEquals(Status.ANALYZED, result.status)
        assertEquals(Content.CHANGED, result.changes.single().content)
    }

    fun `test spaces within a Scala 3 line are formatting-only`() {
        val old = "object A:\n  def f = g(1, 2)\n"
        val new = "object A:\n  def f  =  g(1,2)\n"

        val result = compare(old, new)

        assertEquals(Status.ANALYZED, result.status)
        assertEquals(Content.FORMATTING_ONLY, result.changes.single().content)
    }

    fun `test a Scala 2 indentation change is formatting-only`() {
        val new = SCALA_2_ONLY.replace("  val", "    val")

        val result = compare(SCALA_2_ONLY, new)

        assertEquals(Status.ANALYZED, result.status)
        assertEquals(Content.FORMATTING_ONLY, result.changes.single().content)
    }

    private fun dialect(old: String, new: String): String? = ReadAction.compute(ThrowableComputable<String?, RuntimeException> {
        provider("Scala").dialect(ProjectManager.getInstance().defaultProject, old, new, null)?.id
    })

    private fun compare(old: String, new: String) =
        DeclarationEngine(null).compare(ComparisonRequest(old, new, "Scala", "A.scala"), Cancellation.NONE)

    private companion object {
        /** Scala 3 only: a given, an enum, an extension method and an object with an indented body. */
        const val SCALA_3 = "given Ordering[Int] = Ordering.Int\nenum Color { case Red, Green }\n" +
            "extension (s: String) def shout: String = s.toUpperCase\nobject A:\n  def f = 1\n"

        /** Scala 2 only: `enum` is a keyword in Scala 3. */
        const val SCALA_2_ONLY = "object Legacy {\n  val enum = 1\n}\n"
    }
}
