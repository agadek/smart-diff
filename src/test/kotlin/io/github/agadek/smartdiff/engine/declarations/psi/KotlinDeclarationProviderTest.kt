package io.github.agadek.smartdiff.engine.declarations.psi

import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginModeProvider

/** The Kotlin provider's declarations, names, signatures and ranges (technical specification §6.5, §6.6, §6.7). */
class KotlinDeclarationProviderTest : ProviderTestCase() {

    fun `test Kotlin plugin runs in K2 mode`() {
        assertTrue("tests must run with the Kotlin plugin in K2 mode", KotlinPluginModeProvider.isK2Mode())
    }

    fun `test declarations of every kind, in source order`() {
        val text = """
            package demo

            import kotlin.math.max

            /** Shapes. */
            @Suppress("unused")
            class Shapes(val a: Int, var b: String) {
                init { println(a) }

                constructor(a: Int) : this(a, "")

                fun <T> List<T>.second(x: Int, vararg ys: String): T = this[1]

                suspend fun run(block: suspend () -> Unit, handler: @Suppress("x") (Int) -> Unit) = block()

                val String.size2: Int get() = length

                private val local = run { val inner = 1; fun helper() = inner; helper() }

                val listener = object : Runnable { override fun run() {} }

                enum class Kind { CIRCLE, SQUARE { override fun toString() = "square" } }

                companion object { const val K = 1 }

                object Named

                fun interface Action { fun act() }

                annotation class Marker

                data class Point(val x: Int, val y: Int)
            }

            typealias Name = String

            fun top(): Int = max(1, 2)

            val topLevel = 1
        """.trimIndent() + "\n"

        val tree = build("kotlin", text)

        assertTree(
            """
            class Shapes  "/** Shapes. */"
              initializer  "init { println(a) }"
              constructor(Int)  "constructor(a: Int) : this(a, "")"
              function second(List<T>.Int,String)  "fun <T> List<T>.second(x: Int, vararg ys: String): T = this[1]"
              function run(suspend()->Unit,(Int)->Unit)  "suspend fun run(block: suspend () -> Unit, handler: @Suppress("x") (Int) -> Unit) = block()"
              property size2(String)  "val String.size2: Int get() = length"
              property local  "private val local = run { val inner = 1; fun helper() = inner; helper() }"
              property listener  "val listener = object : Runnable { override fun run() {} }"
              enum Kind  "enum class Kind { CIRCLE, SQUARE { override fun toString() = "square" } }"
                enum constant CIRCLE  "CIRCLE,"
                enum constant SQUARE  "SQUARE { override fun toString() = "square" }"
              object Companion  "companion object { const val K = 1 }"
                property K  "const val K = 1"
              object Named  "object Named"
              interface Action  "fun interface Action { fun act() }"
                function act()  "fun act()"
              annotation type Marker  "annotation class Marker"
              class Point  "data class Point(val x: Int, val y: Int)"
            type alias Name  "typealias Name = String"
            function top()  "fun top(): Int = max(1, 2)"
            property topLevel  "val topLevel = 1"
            """,
            tree, text,
        )
        assertEquals(WhitespacePolicy.LINE_BREAKS_SIGNIFICANT, tree.whitespacePolicy)
    }

    fun `test tokens come from the Kotlin lexer`() {
        val text = "/** Doc. */\nclass A {\n    // note\n    val x = \"s\$x\" /* block */\n}\n"

        val tree = build("kotlin", text)

        val tokens = tree.fileRegion.tokens + tree.all.flatMap { it.ownTokens }
        assertEquals(listOf("/* block */", "/** Doc. */", "// note"), tokens.filter { it.kind == TokenKind.COMMENT }.map { it.text }.sorted())
        assertTrue(tokens.filter { it.kind == TokenKind.WHITESPACE }.all { it.text.isBlank() })
    }

    fun `test a parse error marks the innermost declaration and keeps the others`() {
        val text = "class Broken {\n    fun ok() {}\n    fun bad(x: Int {\n    }\n    val after = 1\n}\n"

        val tree = build("kotlin", text)

        val broken = tree.topLevel.first { it.name == "Broken" }
        val ok = broken.children.first { it.name == "ok" }
        assertFalse("ok() has no parse error", ok.hasParseError)
        assertTrue("some declaration in Broken has the parse error: ${Rendering.tree(tree, text)}", tree.all.any { it.hasParseError })
        assertFalse("the parse error is not on ok()", ok.hasParseError)
    }

    fun `test a script's declarations are top level and its statements are file region`() {
        val text = "val x = 1\n\nprintln(x)\n\nfun f() = x\n"

        val tree = build("kotlin", text, fileName = "build.gradle.kts")

        assertEquals(listOf("x", "f"), tree.topLevel.map { it.name })
        assertFalse(tree.fileRegion.hasParseError)
        assertTrue(tree.fileRegion.tokens.any { it.text == "println" })
    }

    fun `test empty and blank files have no declarations`() {
        for (text in listOf("", "\n\n   \n")) {
            val tree = build("kotlin", text)
            assertEmpty(tree.topLevel)
            assertFalse(tree.fileRegion.hasParseError)
        }
    }
}
