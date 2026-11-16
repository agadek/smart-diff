package io.github.agadek.smartdiff.engine.declarations.psi

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject

/** The engine's comment-only fact on real providers (outline/11). Classification itself stays changed (declarations/02 R6). */
class CommentOnlyEngineTest : ProviderTestCase() {

    // outline/11-comment-only-changes R3
    fun `test a reworded Javadoc and a removed line comment are comment-only in Java`() {
        val old = """
            class A {
                /** Adds. */
                int add(int a, int b) {
                    return a + b; // sum
                }
                int sub(int a, int b) {
                    return a - b;
                }
            }
        """.trimIndent() + "\n"
        val new = """
            class A {
                /** Adds two numbers. */
                int add(int a, int b) {
                    return a + b;
                }
                int sub(int a, int b) {
                    // keep the order
                    return b - a;
                }
            }
        """.trimIndent() + "\n"
        val result = compare(old, new, "JAVA", "A.java")
        assertCommentOnly(result, "add", old, new)
        val sub = declaration(result, "sub")
        assertEquals(Content.CHANGED, sub.content)
        assertFalse("sub changed code too", sub.commentOnly)
    }

    // outline/11-comment-only-changes R3
    fun `test a reworded KDoc is comment-only in Kotlin`() {
        val old = "/** Old words. */\nfun f(x: Int) = x + 1\n\nfun g() = 2\n"
        val new = "/** New words. */\nfun f(x: Int) = x + 1\n\nfun g() = 2\n"
        assertCommentOnly(compare(old, new, "kotlin", "A.kt"), "f", old, new)
    }

    // outline/11-comment-only-changes R3, R4
    fun `test a line comment is comment-only and a docstring is not in Python`() {
        val old = "def f(x):\n    # old note\n    return x\n\ndef g():\n    \"\"\"Old.\"\"\"\n    return 1\n"
        val new = "def f(x):\n    # new note\n    return x\n\ndef g():\n    \"\"\"New.\"\"\"\n    return 1\n"
        val result = compare(old, new, "Python", "a.py")
        assertCommentOnly(result, "f", old, new)
        val g = declaration(result, "g")
        assertEquals(Content.CHANGED, g.content)
        assertFalse("a docstring is a string", g.commentOnly)
    }

    // outline/11-comment-only-changes R7
    fun `test the file region is comment-only under the same rule`() {
        val old = "// Copyright old\npackage p;\n\nclass A {}\n"
        val new = "// Copyright new\npackage p;\n\nclass A {}\n"
        val result = compare(old, new, "JAVA", "A.java")
        val region = result.fileRegion
        assertNotNull("a file region: ${Rendering.result(result, old, new)}", region)
        assertEquals(Content.CHANGED, region!!.content)
        assertTrue("the header comment changed only", region.commentOnly)
    }

    private fun compare(old: String, new: String, language: String, file: String): ComparisonResult {
        val result = DeclarationEngine(null).compare(ComparisonRequest(old, new, language, file), Cancellation.NONE)
        assertEquals(Status.ANALYZED, result.status)
        return result
    }

    private fun declaration(result: ComparisonResult, name: String): ChangeNode {
        fun find(nodes: List<ChangeNode>): ChangeNode? =
            nodes.firstNotNullOfOrNull { node -> node.takeIf { (it.subject as? Subject.Declaration)?.name == name } ?: find(node.children) }
        return find(result.changes) ?: error("no $name in ${result.changes}")
    }

    private fun assertCommentOnly(result: ComparisonResult, name: String, old: String, new: String) {
        val node = declaration(result, name)
        assertEquals("$name stays changed: ${Rendering.result(result, old, new)}", Content.CHANGED, node.content)
        assertTrue("$name is comment-only: ${Rendering.result(result, old, new)}", node.commentOnly)
    }
}
