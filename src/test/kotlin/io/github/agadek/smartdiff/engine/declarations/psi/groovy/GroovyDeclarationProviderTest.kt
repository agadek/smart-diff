package io.github.agadek.smartdiff.engine.declarations.psi.groovy

import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import org.jetbrains.plugins.groovy.GroovyLanguage

/** The Groovy declaration provider (languages/08-groovy). */
class GroovyDeclarationProviderTest : BasePlatformTestCase() {

    // languages/08-groovy R1, R2, R3, R4
    fun `test R1 R2 R3 R4 types and members are declarations with the right kinds`() {
        val file = groovy(
            """
            class C {
              C(int x) {}
              def m(String s) {}
              private int f
              def p
              static { }
              { }
            }
            interface I {}
            trait T {}
            enum E { A, B }
            @interface Ann {}
            """.trimIndent(),
        )
        val provider = GroovyDeclarationProvider()
        val tree = provider.build(file, Cancellation.NONE)
        val c = tree.topLevel.first { it.name == "C" }
        assertEquals(DeclarationKind.CLASS, c.kind)
        assertEquals(listOf(DeclarationKind.CONSTRUCTOR, DeclarationKind.METHOD, DeclarationKind.FIELD, DeclarationKind.PROPERTY, DeclarationKind.INITIALIZER, DeclarationKind.INITIALIZER), c.children.map { it.kind })
        assertEquals(listOf("C", "m", "f", "p", "static", ""), c.children.map { it.name })
        assertEquals(DeclarationKind.INTERFACE, tree.topLevel.first { it.name == "I" }.kind)
        assertEquals(DeclarationKind.TRAIT, tree.topLevel.first { it.name == "T" }.kind)
        val e = tree.topLevel.first { it.name == "E" }
        assertEquals(DeclarationKind.ENUM, e.kind)
        assertEquals(listOf(DeclarationKind.ENUM_CONSTANT, DeclarationKind.ENUM_CONSTANT), e.children.map { it.kind })
        assertEquals(DeclarationKind.ANNOTATION_TYPE, tree.topLevel.first { it.name == "Ann" }.kind)
    }

    // languages/08-groovy R2, R6
    fun `test R2 named nested and inner type declarations are members and anonymous classes stay content`() {
        val file = groovy(
            """
            class Outer {
              static class Nested {}
              class Inner {}
              enum Kind { A }
              interface Box {}
              def m() {
                def a = new Runnable() { void run() {} }
              }
            }
            """.trimIndent(),
        )
        val tree = GroovyDeclarationProvider().build(file, Cancellation.NONE)
        val outer = tree.topLevel.single()
        assertEquals(DeclarationKind.CLASS, outer.kind)
        assertEquals(
            listOf(DeclarationKind.CLASS, DeclarationKind.CLASS, DeclarationKind.ENUM, DeclarationKind.INTERFACE, DeclarationKind.METHOD),
            outer.children.map { it.kind },
        )
        assertEquals(listOf("Nested", "Inner", "Kind", "Box", "m"), outer.children.map { it.name })
        val method = outer.children.last()
        assertTrue("anonymous class inside method is not a declaration", method.children.isEmpty())
    }

    // languages/08-groovy R5
    fun `test R5 script top-level methods and classes are declarations but statements and script variables are not`() {
        val file = groovy(
            """
            def helper() { 1 }
            class Box {}
            def x = 1
            println x
            """.trimIndent(),
            "Script.groovy",
        )
        val tree = GroovyDeclarationProvider().build(file, Cancellation.NONE)
        assertEquals(listOf("helper", "Box"), tree.topLevel.map { it.name })
        assertTrue("statements stay in the file region", tree.fileRegion.ranges.isNotEmpty() || tree.fileRegion.tokens.isNotEmpty())
    }

    // languages/08-groovy R6
    fun `test R6 closures anonymous classes local variables and local classes are not declarations`() {
        val file = groovy(
            """
            class A {
              def m() {
                def x = 1
                def c = { y -> y }
                def a = new Runnable() { void run() {} }
                class Local {}
              }
            }
            """.trimIndent(),
        )
        val tree = GroovyDeclarationProvider().build(file, Cancellation.NONE)
        val m = tree.topLevel.single().children.single()
        assertEquals(DeclarationKind.METHOD, m.kind)
        assertTrue("no nested declarations inside the method", m.children.isEmpty())
    }

    // languages/08-groovy R7
    fun `test R7 signatures list parameter types as written and untyped parameters are def`() {
        val file = groovy(
            """
            class A {
              def f(x, String y, List<Integer> z) {}
              def g(x) {}
              def g(x, y) {}
            }
            """.trimIndent(),
        )
        val tree = GroovyDeclarationProvider().build(file, Cancellation.NONE)
        val methods = tree.topLevel.single().children
        assertEquals("def,String,List<Integer>", methods[0].signature)
        assertEquals("def", methods[1].signature)
        assertEquals("def,def", methods[2].signature)
    }

    // languages/08-groovy R8
    fun `test R8 reformat inside a line is formatting-only and joining or splitting lines is a change`() {
        val engine = DeclarationEngine(project)
        val insideLine = engine.compare(
            ComparisonRequest(
                "class A {\n  def f() { return 1 }\n}\n",
                "class A {\n  def f() {   return  1 }\n}\n",
                "Groovy",
                "A.groovy",
            ),
            Cancellation.NONE,
        )
        val method1 = insideLine.changes.single().children.single()
        assertEquals(Content.FORMATTING_ONLY, method1.content)

        val lineBreak = engine.compare(
            ComparisonRequest(
                "class A {\n  def f() {\n    return 1\n  }\n}\n",
                "class A {\n  def f() { return 1 }\n}\n",
                "Groovy",
                "A.groovy",
            ),
            Cancellation.NONE,
        )
        val method2 = lineBreak.changes.single().children.single()
        assertEquals(Content.CHANGED, method2.content)
    }

    // languages/08-groovy R9
    fun `test R9 errors in Groovydoc are not parse errors`() {
        val file = groovy(
            """
            class A {
              /** broken [[ link */
              def f() { 1 }
            }
            """.trimIndent(),
        )
        val tree = GroovyDeclarationProvider().build(file, Cancellation.NONE)
        val method = tree.topLevel.single().children.single()
        assertEquals("f", method.name)
        assertFalse("Groovydoc errors do not mark the method", method.hasParseError)
    }

    // languages/08-groovy R10, R11, R12, R13, R16, R17
    fun `test R10 R11 R12 R13 R16 R17 gradle build scripts expose methods classes and section declarations`() {
        val text = """
            plugins { id 'java' }
            def helper() { 1 }
            class LocalTask {}
            dependencies { }
            """.trimIndent() + "\n"
        val file = groovy(text, "build.gradle")
        val tree = GroovyDeclarationProvider().build(file, Cancellation.NONE)
        assertEquals(listOf("plugins", "helper", "LocalTask", "dependencies"), tree.topLevel.map { it.name })
        assertEquals(listOf(DeclarationKind.SECTION, DeclarationKind.METHOD, DeclarationKind.CLASS, DeclarationKind.SECTION), tree.topLevel.map { it.kind })
        assertTrue("sections have no signature", tree.topLevel.filter { it.kind == DeclarationKind.SECTION }.all { it.signature == null })
        assertEquals(Status.ANALYZED, DeclarationEngine(project).compare(ComparisonRequest(text, text, "Groovy", "build.gradle"), Cancellation.NONE).status)
    }

    // languages/08-groovy R11
    fun `test R11 plain groovy and gvy scripts get no sections`() {
        for (name in listOf("Script.groovy", "Script.gvy")) {
            val text = "plugins { id 'java' }\ndef helper() { 1 }\n"
            val tree = GroovyDeclarationProvider().build(groovy(text, name), Cancellation.NONE)
            assertEquals(listOf("helper"), tree.topLevel.map { it.name })
            assertTrue("no sections in $name", tree.topLevel.none { it.kind == DeclarationKind.SECTION })
        }
    }

    // languages/08-groovy R11
    fun `test R11 settings and init gradle scripts get sections`() {
        for (name in listOf("settings.gradle", "init.gradle")) {
            val text = "plugins { id 'java' }\ndependencyResolutionManagement { }\n"
            val tree = GroovyDeclarationProvider().build(groovy(text, name), Cancellation.NONE)
            assertEquals(listOf("plugins", "dependencyResolutionManagement"), tree.topLevel.map { it.name })
            assertTrue(tree.topLevel.all { it.kind == DeclarationKind.SECTION })
        }
    }

    // languages/08-groovy R13
    fun `test R13 section names follow the naming rule`() {
        val text = """
            plugins { }
            dependencies { }
            android { }
            allprojects { }
            buildscript { }
            tasks.register('hello') { }
            tasks.named('test') { }
            task hello { }
            tasks.register<Copy>("copy") { }
            task clean(type: Delete) { }
            """.trimIndent() + "\n"
        val tree = GroovyDeclarationProvider().build(groovy(text, "build.gradle"), Cancellation.NONE)
        assertEquals(
            listOf(
                "plugins",
                "dependencies",
                "android",
                "allprojects",
                "buildscript",
                "tasks.register hello",
                "tasks.named test",
                "task hello",
                "tasks.register copy",
                "task clean",
            ),
            tree.topLevel.map { it.name },
        )
        assertTrue(tree.topLevel.all { it.kind == DeclarationKind.SECTION && it.signature == null })
    }

    // languages/08-groovy R16
    fun `test R16 assignments and calls without a closure stay in the file region`() {
        val text = """
            version = '1.0'
            group = 'com.example'
            apply plugin: 'java'
            buildscript { }
            def helper() { 1 }
            class Box {}
            """.trimIndent() + "\n"
        val tree = GroovyDeclarationProvider().build(groovy(text, "build.gradle"), Cancellation.NONE)
        assertEquals(listOf("buildscript", "helper", "Box"), tree.topLevel.map { it.name })
        assertEquals(listOf(DeclarationKind.SECTION, DeclarationKind.METHOD, DeclarationKind.CLASS), tree.topLevel.map { it.kind })
        val region = tree.fileRegion.ranges.joinToString("") { text.substring(it.start, it.end) } + tree.fileRegion.tokens.joinToString("") { it.text }
        assertTrue("version assignment in file region", region.contains("version = '1.0'"))
        assertTrue("group assignment in file region", region.contains("group = 'com.example'"))
        assertTrue("apply call in file region", region.contains("apply plugin: 'java'"))
    }

    // languages/08-groovy R14, R15
    fun `test R14 R15 repeated sections and nested blocks as content`() {
        val text = """
            dependencies { implementation 'a:a:1' }
            dependencies { testImplementation 'b:b:1' }
            android { defaultConfig { minSdk 21 } }
            """.trimIndent() + "\n"
        val tree = GroovyDeclarationProvider().build(groovy(text, "build.gradle"), Cancellation.NONE)
        assertEquals(listOf("dependencies", "dependencies", "android"), tree.topLevel.map { it.name })
        assertTrue(tree.topLevel.all { it.kind == DeclarationKind.SECTION })
        assertTrue("nested blocks are not top-level sections", tree.topLevel.none { it.name == "defaultConfig" })
        val android = tree.topLevel.last()
        val androidText = text.substring(android.range.start, android.range.end)
        assertTrue("nested block is content of section", androidText.contains("defaultConfig"))
    }

    // languages/08-groovy R12, R17
    fun `test R12 R17 section ranges cover the whole statement and offsets equal the request text`() {
        val text = "plugins {\n  id 'java'\n}\n\ndependencies {\n  implementation 'a:a:1'\n}\n"
        val tree = GroovyDeclarationProvider().build(groovy(text, "build.gradle"), Cancellation.NONE)
        assertEquals(listOf("plugins", "dependencies"), tree.topLevel.map { it.name })
        for (section in tree.topLevel) {
            assertEquals(text.substring(section.range.start, section.range.end), when (section.name) {
                "plugins" -> "plugins {\n  id 'java'\n}"
                "dependencies" -> "dependencies {\n  implementation 'a:a:1'\n}"
                else -> error("unexpected ${section.name}")
            })
            assertNull(section.signature)
            assertNull(section.body)
        }
    }

    // languages/08-groovy R18, R19
    fun `test R18 R19 section whitespace and changes inside a section`() {
        val engine = DeclarationEngine(project)
        val insideLine = engine.compare(
            ComparisonRequest(
                "dependencies {\n  implementation 'a:a:1'\n}\n",
                "dependencies {\n  implementation  'a:a:1'\n}\n",
                "Groovy",
                "build.gradle",
            ),
            Cancellation.NONE,
        )
        assertEquals(Content.FORMATTING_ONLY, insideLine.changes.single().content)

        val lineSplit = engine.compare(
            ComparisonRequest(
                "dependencies { implementation 'a:a:1' }\n",
                "dependencies {\n  implementation 'a:a:1'\n}\n",
                "Groovy",
                "build.gradle",
            ),
            Cancellation.NONE,
        )
        assertEquals(Content.CHANGED, lineSplit.changes.single().content)

        val changed = engine.compare(
            ComparisonRequest(
                "dependencies {\n  implementation 'a:a:1'\n}\n",
                "dependencies {\n  implementation 'a:a:2'\n}\n",
                "Groovy",
                "build.gradle",
            ),
            Cancellation.NONE,
        )
        assertEquals("changed", changed.changes.single().content.name.lowercase().let { "changed" }.let { "changed" })
        assertEquals(Content.CHANGED, changed.changes.single().content)
    }

    private fun groovy(text: String, name: String = "A.groovy") =
        PsiFileFactory.getInstance(project).createFileFromText(name, GroovyLanguage, text, false, false)
}
