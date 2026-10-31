package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy
import io.github.agadek.smartdiff.ui.OutlineModel

/**
 * The Scala provider's declarations, names, signatures and ranges (technical specification §6.8, §6.6, §6.7), in the
 * dialect chosen from both sides (§6.2, spike S4).
 */
class ScalaDeclarationProviderTest : ProviderTestCase() {

    fun `test bodies of Scala 2 block and expression defs`() {
        val text = """
            class A(x: Int) {
              def this(s: String) = this(s.length)
              def twice: Int = {
                2 * x
              }
              def thrice() = 3 * x
              def none: Int
              val p = x
            }
        """.trimIndent() + "\n"

        assertBodies(
            """
            constructor (String): this(s.length)
            function twice: 2 * x
            function thrice(): 3 * x
            """,
            build("Scala", text), text,
        )
    }

    fun `test a braceless Scala 3 body is the indented region`() {
        val text = """
            class A(x: Int):
              def twice: Int =
                val y = 2
                y * x
              def thrice() = 3 * x
        """.trimIndent() + "\n"

        assertBodies(
            """
            function twice: val y = 2
                y * x
            function thrice(): 3 * x
            """,
            build("Scala", text), text,
        )
    }

    fun `test Scala 2 declarations of every kind, in source order`() {
        val text = """
            package demo

            import scala.math.max

            /** Shapes. */
            @deprecated("use Shape", "1.0")
            abstract class Shapes[T: Ordering](val a: Int)(implicit o: Ordering[T]) extends Base with Serializable {
              def this(s: String) = this(s.length)(null)

              def area
              def perimeter()
              def fit[A](x: A, ys: Int*)(z: => Int)(implicit ord: Ordering[A]): Int = { def local = 1; local }

              val first, second = 0
              val (p, q) = (1, 2)
              var count: Int = _
              lazy val cached = max(a, 1)
              val size: Int
              val width, height: Int

              type Key = String
              type Value <: AnyRef

              println("statement")

              val task = new Runnable { def run(): Unit = () }

              def legacy(): Int = { val enum = 1; enum }

              case class Point(x: Int, y: Int)

              object Point { def origin = Point(0, 0) }
            }

            trait Shape { def area: Double }

            case object Unit

            package object util { val Version = 1 }
        """.trimIndent() + "\n"

        assertEquals("`enum` as a name is Scala 2 only", "Scala", dialect(text, text))
        val tree = build("Scala", text)

        assertTree(
            """
            class Shapes  "/** Shapes. */"
              constructor(String)  "def this(s: String) = this(s.length)(null)"
              function area  "def area"
              function perimeter()  "def perimeter()"
              function fit(A,Int*;=>Int;implicit Ordering[A])  "def fit[A](x: A, ys: Int*)(z: => Int)(implicit ord: Ordering[A]): Int = { def local = 1; local }"
              property first  "val first"
              property second  "second = 0"
              property (p, q)  "val (p, q) = (1, 2)"
              property count  "var count: Int = _"
              property cached  "lazy val cached = max(a, 1)"
              property size  "val size: Int"
              property width  "val width"
              property height  "height: Int"
              type alias Key  "type Key = String"
              type alias Value  "type Value <: AnyRef"
              property task  "val task = new Runnable { def run(): Unit = () }"
              function legacy()  "def legacy(): Int = { val enum = 1; enum }"
              class Point  "case class Point(x: Int, y: Int)"
              object Point  "object Point { def origin = Point(0, 0) }"
                function origin  "def origin = Point(0, 0)"
            trait Shape  "trait Shape { def area: Double }"
              function area  "def area: Double"
            object Unit  "case object Unit"
            object util  "package object util { val Version = 1 }"
              property Version  "val Version = 1"
            """,
            tree, text,
        )
        assertFalse(tree.fileRegion.hasParseError || tree.all.any { it.hasParseError })
        assertEquals(WhitespacePolicy.LINE_BREAKS_SIGNIFICANT, tree.whitespacePolicy)
    }

    fun `test Scala 3 declarations of every kind, in source order`() {
        val text = """
            package demo

            enum Color derives CanEqual:
              case Red, Green
              case Blue extends Color
              case Mix(a: Int)
              def hex: String = toString

            trait Shape:
              def area: Double

            given Ordering[Int] = Ordering.Int

            given listOrdering[T](using ord: Ordering[T]): Ordering[List[T]] with
              def compare(a: List[T], b: List[T]) = 0

            given Ordering[String] with { def compare(a: String, b: String) = 0 }

            extension [T](xs: List[T])(using Ordering[T])
              def second: T = xs(1)
              def largest(n: Int): List[T] = xs.sorted.take(n)

            extension (s: String) def shout: String = s.toUpperCase

            def top(x: Int)(using Ordering[Int]): Int = x

            val topLevel = 1

            object Shapes:
              opaque type Id = Int
              def apply(): Unit = ()
        """.trimIndent() + "\n"

        assertEquals("Scala 3", dialect(text, text))
        val tree = build("Scala", text)

        assertTree(
            """
            enum Color  "enum Color derives CanEqual:"
              enum constant Red  "case Red"
              enum constant Green  "Green"
              enum constant Blue  "case Blue extends Color"
              enum constant Mix  "case Mix(a: Int)"
              function hex  "def hex: String = toString"
            trait Shape  "trait Shape:"
              function area  "def area: Double"
            given Ordering[Int]  "given Ordering[Int] = Ordering.Int"
            given listOrdering(using Ordering[T])  "given listOrdering[T](using ord: Ordering[T]): Ordering[List[T]] with"
              function compare(List[T],List[T])  "def compare(a: List[T], b: List[T]) = 0"
            given Ordering[String]  "given Ordering[String] with { def compare(a: String, b: String) = 0 }"
              function compare(String,String)  "def compare(a: String, b: String) = 0"
            extension List[T](using Ordering[T])  "extension [T](xs: List[T])(using Ordering[T])"
              function second  "def second: T = xs(1)"
              function largest(Int)  "def largest(n: Int): List[T] = xs.sorted.take(n)"
            extension String  "extension (s: String) def shout: String = s.toUpperCase"
              function shout  "def shout: String = s.toUpperCase"
            function top(Int;using Ordering[Int])  "def top(x: Int)(using Ordering[Int]): Int = x"
            property topLevel  "val topLevel = 1"
            object Shapes  "object Shapes:"
              type alias Id  "opaque type Id = Int"
              function apply()  "def apply(): Unit = ()"
            """,
            tree, text,
        )
        assertFalse(tree.fileRegion.hasParseError || tree.all.any { it.hasParseError })
        assertEquals(WhitespacePolicy.INDENTATION_SIGNIFICANT, tree.whitespacePolicy)
    }

    fun `test definitions in package clauses, braced or not, are top level`() {
        val text = "package a\npackage b {\n  class Y\n  package c {\n    object Z\n  }\n}\npackage d:\n  def f = 1\n"

        val tree = build("Scala", text)

        assertTree(
            """
            class Y  "class Y"
            object Z  "object Z"
            function f  "def f = 1"
            """,
            tree, text,
        )
    }

    fun `test signatures keep clauses, using and implicit, varargs and by-name types`() {
        val text = """
            object S {
              def none = 1
              def empty() = 1
              def clauses(a: Int, b: String)()(c: Long) = 1
              def contextual[A: Ordering](x: A)(using Ordering[A], Numeric[A]) = 1
              def legacy(x: Int)(implicit  ord :  Ordering[Int], ev: Int  <:<  Int) = 1
              def rest(xs: Map[String,  List[Int]]*, f: (Int, Int) => Int) = 1
              def lazily(x: =>  Int, @deprecated("x", "1") y: Int = 2) = 1
            }
        """.trimIndent() + "\n"

        assertEquals("Scala 3", dialect(text, text))
        val tree = build("Scala", text)

        val functions = tree.topLevel.single().children
        assertEquals(
            listOf(
                null, "", "Int,String;;Long", "A;using Ordering[A],Numeric[A]", "Int;implicit Ordering[Int],Int<:<Int",
                "Map[String,List[Int]]*,(Int,Int)=>Int", "=>Int,Int",
            ),
            functions.map { it.signature },
        )
        assertEquals(
            listOf(
                null, "", "Int,String;;Long", "A;using Ordering[A],Numeric[A]", "Int;implicit Ordering[Int],Int <:< Int",
                "Map[String,List[Int]]*,(Int,Int) => Int", "=> Int,Int",
            ),
            functions.map { it.displaySignature },
        )
    }

    fun `test a def without a clause and one with an empty clause differ in signature`() {
        val old = "object A {\n  def f = 1\n}\n"
        val new = "object A {\n  def f() = 1\n}\n"

        val result = compare(old, new)

        val f = result.changes.single().children.single()
        assertTrue("def f to def f() is a signature change: ${Rendering.result(result, old, new)}", f.signatureChanged)
    }

    fun `test anonymous givens are named by their type, normalized`() {
        val text = "given Ordering[List[ Int ]] = ???\ngiven (using x: Int): String = \"\"\ngiven Ordering[Long] with {}\n"

        val tree = build("Scala", text)

        assertEquals(listOf("Ordering[List[Int]]", "String", "Ordering[Long]"), tree.topLevel.map { it.name })
        assertEquals(listOf(null, "using Int", null), tree.topLevel.map { it.signature })
    }

    fun `test a pattern definition is one property, named by its bindings`() {
        val text = "object A {\n  val (a, (b, _)) = (1, (2, 3))\n  val Some(x) = Option(1)\n  val _ = println()\n}\n"

        val tree = build("Scala", text)

        assertEquals(listOf("(a, b)", "x"), tree.topLevel.single().children.map { it.name })
    }

    fun `test cases and properties written together are separate declarations`() {
        val text = "enum E:\n  case A, B, C\n\nobject O:\n  val a, b = 0\n  var c, d: Int\n"

        val tree = build("Scala", text)

        assertTree(
            """
            enum E  "enum E:"
              enum constant A  "case A"
              enum constant B  "B"
              enum constant C  "C"
            object O  "object O:"
              property a  "val a"
              property b  "b = 0"
              property c  "var c"
              property d  "d: Int"
            """,
            tree, text,
        )
    }

    fun `test a parse error marks the member it is in and keeps the others`() {
        val text = "class Broken {\n  def ok() = 1\n  def bad(x: Int = { 1\n  val after = 1\n}\n"

        val tree = build("Scala", text)

        val broken = tree.topLevel.single()
        assertFalse("ok() has no parse error: ${Rendering.tree(tree, text)}", broken.children.first { it.name == "ok" }.hasParseError)
        assertTrue("bad has the parse error: ${Rendering.tree(tree, text)}", broken.children.first { it.name == "bad" }.hasParseError)
    }

    fun `test Scaladoc markup errors are not parse errors`() {
        val text = "object A {\n  /** Turns F[G[A]] into G[F[A]]. */\n  def sequence = 1\n  def other = 2\n}\n"

        val tree = build("Scala", text)

        assertFalse(tree.fileRegion.hasParseError || tree.all.any { it.hasParseError })
    }

    fun `test a declaration with Scaladoc markup errors is analyzed, changed or not`() {
        val old = "object A {\n  /** Turns F[G[A]] into G[F[A]]. */\n  def sequence = 1\n  def other = 2\n}\n"

        val bodyChanged = compare(old, old.replace("sequence = 1", "sequence = 3"))
        val docChanged = compare(old, old.replace("Turns", "Swaps"))
        val otherChanged = compare(old, old.replace("other = 2", "other = 3"))

        for (result in listOf(bodyChanged, docChanged, otherChanged)) assertEquals(Status.ANALYZED, result.status)
        assertEquals(Content.CHANGED, bodyChanged.changes.single().children.single { it.isReported }.content)
        assertEquals("sequence", (docChanged.changes.single().children.single { it.isReported }.subject as Subject.Declaration).name)
        assertEquals(Content.CHANGED, docChanged.changes.single().children.single { it.isReported }.content)
        assertEquals("other", (otherChanged.changes.single().children.single { it.isReported }.subject as Subject.Declaration).name)
    }

    fun `test a parse error in code still makes a changed declaration unanalyzed`() {
        val old = "object A {\n  /** Turns F[G[A]] into G[F[A]]. */\n  def sequence = (1\n  def other = 2\n}\n"
        val new = old.replace("(1", "(2")

        val result = compare(old, new)

        assertEquals(Status.PARTIAL, result.status)
        assertEquals(Content.UNANALYZED, result.changes.single().children.single { it.isReported }.content)
    }

    fun `test a declaration with a parse error is unchanged when its source is the same on both sides`() {
        val old = "object A {\n  def broken = (1\n  def other = 2\n}\n"
        val new = old.replace("other = 2", "other = 3")
        assertTrue("broken has a parse error", build("Scala", old).all.single { it.name == "broken" }.hasParseError)

        val result = compare(old, new)

        assertEquals(Rendering.result(result, old, new), Status.ANALYZED, result.status)
        assertEquals("other", (result.changes.single().children.single { it.isReported }.subject as Subject.Declaration).name)
    }

    fun `test local definitions, anonymous classes and statements are part of their container`() {
        val text = "class A {\n  def f = {\n    def g = 1\n    val h = 2\n    g + h\n  }\n  val r = new Runnable { def run(): Unit = () }\n  println(1)\n  import scala.util.Try\n}\n"

        val tree = build("Scala", text)

        assertEquals(listOf("f", "r"), tree.topLevel.single().children.map { it.name })
    }

    fun `test a class and its companion object share a name but not a kind`() {
        val result = compare("class P\nobject P\n", "class P(x: Int)\nobject P\n")

        assertEquals(Status.ANALYZED, result.status)
        assertEquals("class P", Rendering.subject(result.changes.single { it.content != Content.UNCHANGED }.subject))
    }

    fun `test changing a class into a trait is one declaration that changed kind`() {
        val result = compare("class Shape\n", "trait Shape\n")

        val shape = result.changes.single()
        assertEquals("trait Shape", Rendering.subject(shape.subject))
        assertEquals(DeclarationKind.CLASS, shape.kindChangedFrom)
        assertTrue(shape.signatureChanged)
        assertEquals("class → trait Shape", OutlineModel.title(shape))
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
        assertEquals(listOf("Ordering[Int]", "Color", "String", "A", "After"), tree.topLevel.map { it.name })
        assertEquals(WhitespacePolicy.INDENTATION_SIGNIFICANT, tree.whitespacePolicy)
    }

    fun `test Scala 2 code that Scala 3 also parses goes to Scala 2`() {
        val procedureSyntax = "object B { def f() { println(1) } }\n"

        assertEquals("Scala", dialect(procedureSyntax, procedureSyntax))
    }

    fun `test cats-style Scala 2 code takes Scala 2, so re-indenting it is formatting-only`() {
        val old = """
            package cats

            import cats.syntax.all._

            /** A type class, as in cats. */
            trait Show[A] extends Serializable { self =>
              def show(a: A): String

              def contramap[B](f: B => A): Show[B] = new Show[B] {
                def show(b: B): String = self.show(f(b))
              }
            }

            object Show {
              def apply[A](implicit instance: Show[A]): Show[A] = instance

              implicit val catsShowForInt: Show[Int] = new Show[Int] {
                def show(a: Int): String = a.toString
              }

              implicit def catsShowForList[A: Show]: Show[List[A]] = (xs: List[A]) => xs.map(Show[A].show).mkString("[", ", ", "]")
            }
        """.trimIndent() + "\n"
        val new = old.replace("  def apply[A]", "    def apply[A]")

        assertEquals("Scala", dialect(old, new))
        val result = compare(old, new)
        assertEquals(Status.ANALYZED, result.status)
        assertEquals(Content.FORMATTING_ONLY, result.changes.single { it.isReported }.children.single { it.isReported }.content)
    }

    fun `test any Scala 3 syntax takes Scala 3`() {
        val samples = listOf(
            "object A { def f(a: Boolean) = if a then 1 else 2 }\n",
            "object A { given Int = 1 }\n",
            "object A { def f(using x: Int) = x }\n",
            "object A:\n  def f = 1\n",
            "package a:\n  object A\n",
            "def top = 1\n",
            "object A { enum E { case X } }\n",
            "object A { extension (s: String) def shout = s }\n",
            "object A { export B.f }\n",
            "object A {\n  def f = 1\n}\nend A\n",
            "object A { inline def f = 1 }\n",
            "object A { opaque type Id = Int }\n",
            "object A { type F = [X] =>> List[X] }\n",
            "object A { val f: Int ?=> Int = 1 }\n",
            "object A { case class P(x: Int) derives CanEqual }\n",
        )
        for (sample in samples) assertEquals(sample, "Scala 3", dialect(sample, sample))
    }

    fun `test syntax Scala 2 accepts under -Xsource 3 does not take Scala 3`() {
        val samples = listOf(
            "object A { def f(x: Int)(implicit o: Ordering[Int]) = g(x)(using o) }\n",
            "object A { def f(c: Class[?]) = c }\n",
            "object A { @inline def f = 1 }\n",
        )
        for (sample in samples) assertEquals(sample, "Scala", dialect(sample, sample))
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

    fun `test all texts of a merge choose one dialect`() {
        val base = "object Plain { def f() = 1 }\n"

        // Alone, each side would read the base in another dialect (three-way merge plan, risk R3).
        assertEquals("Scala 3", dialect(base, SCALA_3))
        assertEquals("Scala", dialect(base, SCALA_2_ONLY))
        val both = dialect(base, SCALA_3, SCALA_2_ONLY)
        assertEquals(both, dialect(base, SCALA_2_ONLY, SCALA_3))
    }

    fun `test the related texts of a request choose the dialect of both runs of a merge`() {
        val base = "object Plain {\n  def f() = 1\n}\n"
        val engine = DeclarationEngine(null)
        fun baseTree(side: String, other: String?) =
            engine.trees(ComparisonRequest(base, side, "Scala", "A.scala", relatedTexts = listOfNotNull(other)), Cancellation.NONE)!!.first

        assertFalse(baseTree(SCALA_3, null).whitespacePolicy == baseTree(SCALA_2_ONLY, null).whitespacePolicy)
        assertEquals(baseTree(SCALA_3, SCALA_2_ONLY).whitespacePolicy, baseTree(SCALA_2_ONLY, SCALA_3).whitespacePolicy)
    }

    fun `test a Scala 3 indentation change is never formatting-only`() {
        val old = "object A:\n  def f =\n    println(1)\n    println(2)\n"
        val new = "object A:\n  def f =\n    println(1)\n  println(2)\n"

        val result = compare(old, new)

        assertEquals(Status.ANALYZED, result.status)
        assertEquals(Content.CHANGED, result.changes.single().children.single().content)
    }

    fun `test spaces within a Scala 3 line are formatting-only`() {
        val old = "object A:\n  def f = g(1, 2)\n"
        val new = "object A:\n  def f  =  g(1,2)\n"

        val result = compare(old, new)

        assertEquals(Status.ANALYZED, result.status)
        assertEquals(Content.FORMATTING_ONLY, result.changes.single().children.single().content)
    }

    fun `test a Scala 2 indentation change is formatting-only`() {
        val new = SCALA_2_ONLY.replace("  val", "    val")

        val result = compare(SCALA_2_ONLY, new)

        assertEquals(Status.ANALYZED, result.status)
        assertEquals(Content.FORMATTING_ONLY, result.changes.single().children.single().content)
    }

    private fun dialect(vararg texts: String): String? = ReadAction.compute(ThrowableComputable<String?, RuntimeException> {
        provider("Scala").dialect(ProjectManager.getInstance().defaultProject, texts.toList(), null)?.id
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
