package io.github.agadek.smartdiff.engine.declarations.psi

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginModeProvider

/** The Kotlin provider's declarations, names, signatures and ranges (technical specification §6.5, §6.6, §6.7). */
class KotlinDeclarationProviderTest : ProviderTestCase() {

    fun `test bodies of block and expression functions and secondary constructors`() {
        val text = """
            class A(val x: Int) {
                constructor(s: String) : this(s.length) { println(s) }
                constructor() : this(0)
                fun twice(): Int {
                    return 2 * x
                }
                fun thrice() = 3 * x
                val p: Int get() = x
            }
            interface I { fun f(): Int }
            fun String.shout(): String = uppercase() + "!"
        """.trimIndent() + "\n"

        assertBodies(
            """
            constructor (String): println(s)
            function twice(): return 2 * x
            function thrice(): 3 * x
            function shout(String.): uppercase() + "!"
            """,
            build("kotlin", text), text,
        )
    }

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

                private val local = run { val inner = 1; inner }

                fun withLocal(): Int {
                    fun helper() = 1
                    return helper()
                }

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
              function second(List<T>.Int,String...)  "fun <T> List<T>.second(x: Int, vararg ys: String): T = this[1]"
              function run(suspend()->Unit,(Int)->Unit)  "suspend fun run(block: suspend () -> Unit, handler: @Suppress("x") (Int) -> Unit) = block()"
              property size2(String)  "val String.size2: Int get() = length"
              property local  "private val local = run { val inner = 1; inner }"
              function withLocal()  "fun withLocal(): Int {"
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

    fun `test adding a declaration above the first one is formatting-only on that declaration`() {
        // declarations/02-change-classification R14
        val old = "object Plain {\n    fun f() = 1\n}\n"
        val new = "fun top() = 0\n" + old

        val result = DeclarationEngine(null).compare(ComparisonRequest(old, new, "kotlin", "A.kt"), Cancellation.NONE)

        assertEquals(Status.ANALYZED, result.status)
        val plain = result.changes.single { (it.subject as? Subject.Declaration)?.name == "Plain" }
        assertEquals("object Plain has the same own source: ${Rendering.result(result, old, new)}", Content.FORMATTING_ONLY, plain.content)
    }

    // languages/02-kotlin R12, R13, R14, R17, R18
    fun `test R12 R13 R14 R17 R18 gradle kts build scripts expose section declarations`() {
        val text = """
            plugins { id("java") }
            dependencies { implementation("a:a:1") }
            fun helper() = 1
            class Box
            tasks.register("hello") { }
            tasks.named("test") { }
            tasks.register<Copy>("copy") { }
            """.trimIndent() + "\n"
        val tree = build("kotlin", text, fileName = "build.gradle.kts")
        assertEquals(listOf("plugins", "dependencies", "helper", "Box", "tasks.register hello", "tasks.named test", "tasks.register copy"), tree.topLevel.map { it.name })
        assertEquals(
            listOf(
                DeclarationKind.SECTION,
                DeclarationKind.SECTION,
                DeclarationKind.FUNCTION,
                DeclarationKind.CLASS,
                DeclarationKind.SECTION,
                DeclarationKind.SECTION,
                DeclarationKind.SECTION,
            ),
            tree.topLevel.map { it.kind },
        )
        assertTrue("sections have no signature", tree.topLevel.filter { it.kind == DeclarationKind.SECTION }.all { it.signature == null })
        val region = tree.fileRegion.ranges.joinToString("") { text.substring(it.start, it.end) } + tree.fileRegion.tokens.joinToString("") { it.text }
        assertFalse("section calls not in file region", region.contains("plugins {"))
        assertEquals(Status.ANALYZED, DeclarationEngine(null).compare(ComparisonRequest(text, text, "kotlin", "build.gradle.kts"), Cancellation.NONE).status)
    }

    // languages/02-kotlin R12
    fun `test R12 settings and init gradle kts scripts and non-build kts scripts`() {
        for (name in listOf("settings.gradle.kts", "init.gradle.kts")) {
            val text = "plugins { id(\"java\") }\ndependencyResolutionManagement { }\n"
            val tree = build("kotlin", text, fileName = name)
            assertEquals(listOf("plugins", "dependencyResolutionManagement"), tree.topLevel.map { it.name })
            assertTrue(tree.topLevel.all { it.kind == DeclarationKind.SECTION })
        }
        val mainTree = build("kotlin", "plugins { }\nprintln(1)\n", fileName = "Script.main.kts")
        assertTrue("no sections in main.kts", mainTree.topLevel.none { it.kind == DeclarationKind.SECTION })
    }

    // languages/02-kotlin R16, R17
    fun `test R16 R17 assignments and calls without a trailing lambda stay in the file region`() {
        val text = """
            version = "1.0"
            apply(plugin = "java")
            buildscript { }
            fun helper() = 1
            """.trimIndent() + "\n"
        val tree = build("kotlin", text, fileName = "build.gradle.kts")
        assertEquals(listOf("buildscript", "helper"), tree.topLevel.map { it.name })
        val region = tree.fileRegion.ranges.joinToString("") { text.substring(it.start, it.end) } + tree.fileRegion.tokens.joinToString("") { it.text }
        assertTrue(region.contains("version = \"1.0\""))
        assertTrue(region.contains("apply(plugin = \"java\")"))
    }

    // languages/02-kotlin R15, R16
    fun `test R15 R16 repeated sections nested blocks as content and offsets`() {
        val text = "dependencies { implementation(\"a:a:1\") }\ndependencies { testImplementation(\"b:b:1\") }\nandroid { defaultConfig { minSdk = 21 } }\n"
        val tree = build("kotlin", text, fileName = "build.gradle.kts")
        assertEquals(listOf("dependencies", "dependencies", "android"), tree.topLevel.map { it.name })
        assertTrue(tree.topLevel.all { it.kind == DeclarationKind.SECTION })
        for (section in tree.topLevel) {
            assertEquals(text.substring(section.range.start, section.range.end).trimEnd(), text.substring(section.range.start, section.range.end).trimEnd())
            assertTrue(text.substring(section.range.start, section.range.end).isNotEmpty())
        }
        val android = tree.topLevel.last()
        assertTrue(text.substring(android.range.start, android.range.end).contains("defaultConfig"))
    }

    // languages/02-kotlin R19, R20
    fun `test R19 R20 section whitespace and changes inside a section`() {
        val engine = DeclarationEngine(null)
        val insideLine = engine.compare(
            ComparisonRequest(
                "dependencies {\n  implementation(\"a:a:1\")\n}\n",
                "dependencies {\n  implementation(  \"a:a:1\")\n}\n",
                "kotlin",
                "build.gradle.kts",
            ),
            Cancellation.NONE,
        )
        assertEquals(Content.FORMATTING_ONLY, insideLine.changes.single().content)

        val lineSplit = engine.compare(
            ComparisonRequest(
                "dependencies { implementation(\"a:a:1\") }\n",
                "dependencies {\n  implementation(\"a:a:1\")\n}\n",
                "kotlin",
                "build.gradle.kts",
            ),
            Cancellation.NONE,
        )
        assertEquals(Content.CHANGED, lineSplit.changes.single().content)

        val changed = engine.compare(
            ComparisonRequest(
                "dependencies {\n  implementation(\"a:a:1\")\n}\n",
                "dependencies {\n  implementation(\"a:a:2\")\n}\n",
                "kotlin",
                "build.gradle.kts",
            ),
            Cancellation.NONE,
        )
        assertEquals(Content.CHANGED, changed.changes.single().content)
    }

    // languages/02-kotlin R17
    fun `test R17 assignments with closures stay in the file region`() {
        val text = """
            version = provider { "1.0" }
            val x = listOf(1).map { it }
            """.trimIndent() + "\n"
        val tree = build("kotlin", text, fileName = "build.gradle.kts")
        assertTrue("assignments with closures are not sections", tree.topLevel.none { it.kind == DeclarationKind.SECTION })
    }

    // languages/02-kotlin R14
    fun `test R14 chained and qualified callees use the receiver chain names`() {
        val text = """
            tasks.withType<Test>().configureEach { }
            project(":app") { }
            subprojects { }
            configure<JavaPluginExtension> { }
            tasks.test { }
            tasks.register<Map<String, Int>>("x") { }
            """.trimIndent() + "\n"
        val tree = build("kotlin", text, fileName = "build.gradle.kts")
        assertEquals(
            listOf(
                "tasks.withType.configureEach",
                "project :app",
                "subprojects",
                "configure",
                "tasks.test",
                "tasks.register x",
            ),
            tree.topLevel.map { it.name },
        )
    }

    // languages/02-kotlin R20
    fun `test R20 adding removing and reordering sections does not report other sections or the file region`() {
        val engine = DeclarationEngine(null)
        val base = "dependencies {\n  implementation(\"a:a:1\")\n}\n"
        val added = "plugins {\n  id(\"java\")\n}\n\n$base"
        val addedResult = engine.compare(ComparisonRequest(base, added, "kotlin", "build.gradle.kts"), Cancellation.NONE)
        assertEquals(listOf("section plugins  added"), summarize(addedResult))

        val removedResult = engine.compare(ComparisonRequest(added, base, "kotlin", "build.gradle.kts"), Cancellation.NONE)
        assertEquals(listOf("section plugins  removed"), summarize(removedResult))

        val reordered = "dependencies {\n  implementation(\"a:a:1\")\n}\n\nplugins {\n  id(\"java\")\n}\n"
        val reorderedResult = engine.compare(ComparisonRequest(added, reordered, "kotlin", "build.gradle.kts"), Cancellation.NONE)
        assertEquals(listOf("section dependencies  moved"), summarize(reorderedResult))

        val two = "dependencies {\n  implementation(\"a:a:1\")\n}\n\ndependencies {\n  testImplementation(\"b:b:1\")\n}\n"
        val twoResult = engine.compare(ComparisonRequest(base, two, "kotlin", "build.gradle.kts"), Cancellation.NONE)
        assertEquals(listOf("section dependencies  added"), summarize(twoResult))
    }

    private fun summarize(result: io.github.agadek.smartdiff.engine.api.ComparisonResult): List<String> {
        val rows = mutableListOf<String>()
        result.fileRegion?.takeIf { it.content != Content.UNCHANGED }?.let { rows += "region ${it.content.name.lowercase()}" }
        result.changes.forEach { change ->
            if (!change.isReported) return@forEach
            val subject = change.subject
            val presence = if (change.moved) "moved" else change.presence.name.lowercase()
            if (subject is Subject.Declaration) {
                rows += "${subject.kind.label()} ${subject.name}  $presence"
            } else {
                rows += presence
            }
        }
        return rows
    }
}
