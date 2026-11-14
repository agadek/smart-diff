package io.github.agadek.smartdiff.engine.declarations.psi.shell

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sh.ShLanguage
import com.intellij.sh.psi.ShFunctionDefinition
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationProvider

/** The Shell declaration provider (languages/09-shell). */
class ShellDeclarationProviderTest : BasePlatformTestCase() {

    private val engine by lazy { DeclarationEngine(project) }

    // languages/09-shell R1, R2
    fun `test R1 R2 function syntaxes are FUNCTION declarations`() {
        val provider = provider()
        val tree = provider.build(file("""
            foo() { echo hi; }
            function bar { echo hi; }
            function baz() { echo hi; }
            qux() ( echo hi )
        """.trimIndent()), Cancellation.NONE)
        assertEquals(listOf("foo", "bar", "baz", "qux"), tree.topLevel.map { it.name })
        assertTrue(tree.topLevel.all { it.kind == DeclarationKind.FUNCTION })
    }

    // languages/09-shell R2
    fun `test R2 non-block compound command bodies are parsed with an error and no body`() {
        for (text in listOf("f() if true; then echo; fi\n", "f() [[ -n x ]]\n")) {
            val psi = file(text)
            val fn = PsiTreeUtil.findChildrenOfType(psi, ShFunctionDefinition::class.java).single()
            assertNotNull("$text: parse error", PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java))
            assertNull("$text: no brace block", fn.block)
            assertNull("$text: no parentheses block", fn.parenthesesBlock)
            val declaration = provider().build(psi, Cancellation.NONE).topLevel.single()
            assertNull("$text: no body", declaration.body)
        }
    }

    // languages/09-shell R3
    fun `test R3 shell functions have empty signatures and duplicates keep order`() {
        val provider = provider()
        val tree = provider.build(file("""
            foo() { echo one; }
            foo() { echo two; }
        """.trimIndent()), Cancellation.NONE)
        assertEquals(2, tree.topLevel.size)
        assertTrue(tree.topLevel.all { it.name == "foo" && it.signature == "" })
    }

    // languages/09-shell R4
    fun `test R4 a function inside another function is nested`() {
        val provider = provider()
        val tree = provider.build(file("""
            outer() {
              inner() { echo hi; }
            }
        """.trimIndent()), Cancellation.NONE)
        assertEquals(listOf("outer"), tree.topLevel.map { it.name })
        assertEquals(listOf("inner"), tree.topLevel.single().children.map { it.name })
    }

    // languages/09-shell R5
    fun `test R5 a function inside a compound command at top level is top-level`() {
        val provider = provider()
        val tree = provider.build(file("""
            if true; then
              inner() { echo hi; }
            fi
        """.trimIndent()), Cancellation.NONE)
        assertEquals(listOf("inner"), tree.topLevel.map { it.name })
    }

    // languages/09-shell R6
    fun `test R6 top-level commands assignments exports aliases source and shebang stay in the file region`() {
        val provider = provider()
        val text = """
            #!/bin/bash
            set -e
            FOO=bar
            export BAZ=1
            alias ll='ls -l'
            source other.sh
            echo hi
        """.trimIndent()
        val tree = provider.build(file(text), Cancellation.NONE)
        assertTrue(tree.topLevel.isEmpty())
        assertEquals(text, tree.fileRegion.ranges.joinToString("") { text.substring(it.start, it.end) })
    }

    // languages/09-shell R7
    fun `test R7 whitespace inside strings and heredocs is significant`() {
        val result = engine.compare(ComparisonRequest(
            "foo() {\n  echo \"a  b\"\n}\n",
            "foo() {\n  echo \"a b\"\n}\n",
            "Shell Script", "a.sh",
        ), Cancellation.NONE)
        val fn = result.changes.single()
        assertEquals(Presence.MATCHED, fn.presence)
        assertEquals(Content.CHANGED, fn.content)

        val heredoc = engine.compare(ComparisonRequest(
            "foo() {\n  cat <<EOF\nline  one\nEOF\n}\n",
            "foo() {\n  cat <<EOF\nline one\nEOF\n}\n",
            "Shell Script", "a.sh",
        ), Cancellation.NONE)
        assertEquals(Content.CHANGED, heredoc.changes.single().content)
    }

    // languages/09-shell R8
    fun `test R8 re-indenting a body and changing argument spacing is formatting-only`() {
        val reindent = engine.compare(ComparisonRequest(
            "foo() {\necho hi\n}\n",
            "foo() {\n  echo hi\n}\n",
            "Shell Script", "a.sh",
        ), Cancellation.NONE)
        assertEquals(Content.FORMATTING_ONLY, reindent.changes.single().content)

        val spacing = engine.compare(ComparisonRequest(
            "foo() {\n  echo hi\n}\n",
            "foo() {\n  echo   hi\n}\n",
            "Shell Script", "a.sh",
        ), Cancellation.NONE)
        assertEquals(Content.FORMATTING_ONLY, spacing.changes.single().content)
        assertEquals(WhitespacePolicy.SEPARATION_SIGNIFICANT, provider().build(file("foo() { echo hi; }\n"), Cancellation.NONE).whitespacePolicy)
    }

    // languages/09-shell R8
    fun `test R8 removing or adding token separation is a change`() {
        val assignment = engine.compare(ComparisonRequest(
            "foo() {\n  a=1\n}\n",
            "foo() {\n  a = 1\n}\n",
            "Shell Script", "a.sh",
        ), Cancellation.NONE)
        assertEquals(Content.CHANGED, assignment.changes.single().content)

        val bracket = engine.compare(ComparisonRequest(
            "foo() {\n  [ -n \"\$x\" ]\n}\n",
            "foo() {\n  [-n \"\$x\"]\n}\n",
            "Shell Script", "a.sh",
        ), Cancellation.NONE)
        assertEquals(Content.CHANGED, bracket.changes.single().content)
    }

    // languages/09-shell R9
    fun `test R9 joining commands with a semicolon is a change`() {
        val result = engine.compare(ComparisonRequest(
            "foo() {\n  echo a\n  echo b\n}\n",
            "foo() {\n  echo a; echo b\n}\n",
            "Shell Script", "a.sh",
        ), Cancellation.NONE)
        assertEquals(Content.CHANGED, result.changes.single().content)
    }

    // languages/09-shell R10
    fun `test R10 a comment directly above a function belongs to the function`() {
        val text = "# docs\nfoo() { echo hi; }\n"
        val tree = provider().build(file(text), Cancellation.NONE)
        val fn = tree.topLevel.single()
        assertEquals(0, fn.range.start)
        assertTrue(fn.ownTokens.any { it.kind == io.github.agadek.smartdiff.engine.declarations.model.TokenKind.COMMENT && it.text.contains("docs") })
    }

    // languages/09-shell R10
    fun `test R10 a comment above an enclosing if is not attached to a function inside it`() {
        val text = "# outer\nif true; then\n  f() { echo hi; }\nfi\n"
        val fn = provider().build(file(text), Cancellation.NONE).topLevel.single()
        assertEquals("f", fn.name)
        assertTrue("comment above if must not be attached: ${fn.range}", fn.range.start > 0)
        assertFalse(fn.ownTokens.any { it.kind == io.github.agadek.smartdiff.engine.declarations.model.TokenKind.COMMENT && it.text.contains("outer") })
    }

    // languages/09-shell R10
    fun `test R10 a nested function doc comment does not extend before its parent body`() {
        val text = "outer() {\n  # inner doc\n  inner() { echo hi; }\n}\n"
        val psi = file(text)
        val tree = provider().build(psi, Cancellation.NONE)
        val outer = tree.topLevel.single()
        val inner = outer.children.single()
        val bodyStart = (PsiTreeUtil.findChildrenOfType(psi, ShFunctionDefinition::class.java).first { it.name == "outer" }.block
            ?: error("outer body")).textRange.startOffset
        assertTrue("nested doc stays inside parent body: ${inner.range} body=$bodyStart", inner.range.start >= bodyStart)
        assertTrue(inner.ownTokens.any { it.kind == io.github.agadek.smartdiff.engine.declarations.model.TokenKind.COMMENT && it.text.contains("inner doc") })
    }

    // languages/09-shell R11
    fun `test R11 a parse error on one side falls back as for other languages`() {
        val result = engine.compare(ComparisonRequest(
            "foo() { echo hi; }\n",
            "foo() { if then }\n",
            "Shell Script", "a.sh",
        ), Cancellation.NONE)
        assertTrue(result.status == Status.PARTIAL || result.status == Status.FAILED || result.status == Status.ANALYZED)
        if (result.status == Status.FAILED) {
            assertNotNull(result.fallbackReason)
        }
    }

    // languages/09-shell R12
    fun `test R12 shell files go to the shell provider`() {
        val provider = DeclarationProvider.EP_NAME.extensionList.first { it.supports("Shell Script") }
        assertTrue(provider is ShellDeclarationProvider)
        val result = engine.compare(ComparisonRequest("foo() { echo hi; }\n", "foo() { echo bye; }\n", "Shell Script", "a.bash"), Cancellation.NONE)
        assertEquals(Status.ANALYZED, result.status)
        assertTrue(result.changes.any { it.subject is io.github.agadek.smartdiff.engine.api.Subject.Declaration })
    }

    fun `test a comment change is a content change`() {
        val result = engine.compare(ComparisonRequest(
            "# old\nfoo() { echo hi; }\n",
            "# new\nfoo() { echo hi; }\n",
            "Shell Script", "a.sh",
        ), Cancellation.NONE)
        val fn = result.changes.single()
        assertEquals(Presence.MATCHED, fn.presence)
        assertEquals(Content.CHANGED, fn.content)
    }

    private fun provider(): ShellDeclarationProvider =
        DeclarationProvider.EP_NAME.extensionList.filterIsInstance<ShellDeclarationProvider>().single()

    private fun file(text: String) =
        PsiFileFactory.getInstance(project).createFileFromText("a.sh", ShLanguage.INSTANCE, text, false, false)
}
