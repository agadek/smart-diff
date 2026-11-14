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

    // languages/08-groovy R10
    fun `test R10 a gradle file gets the Groovy outline for methods and classes`() {
        val file = groovy(
            """
            plugins { id 'java' }
            def helper() { 1 }
            class LocalTask {}
            dependencies { }
            """.trimIndent(),
            "build.gradle",
        )
        val tree = GroovyDeclarationProvider().build(file, Cancellation.NONE)
        assertEquals(listOf("helper", "LocalTask"), tree.topLevel.map { it.name })
        assertEquals(Status.ANALYZED, DeclarationEngine(project).compare(ComparisonRequest(file.text, file.text, "Groovy", "build.gradle"), Cancellation.NONE).status)
    }

    private fun groovy(text: String, name: String = "A.groovy") =
        PsiFileFactory.getInstance(project).createFileFromText(name, GroovyLanguage, text, false, false)
}
