package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy.INDENTATION_SIGNIFICANT
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy.INSIGNIFICANT
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy.LINE_BREAKS_SIGNIFICANT
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy.SEPARATION_SIGNIFICANT
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy.SIGNIFICANT
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The comment-only rule of [ContentClassifier.isCommentOnly] (outline/11). */
class CommentOnlyClassifierTest {

    // outline/11-comment-only-changes R3
    @Test
    fun `a reworded comment is comment-only under every policy`() {
        WhitespacePolicy.entries.forEach {
            assertTrue("$it", commentOnly("/** Adds. */\nint add() { return a; }", "/** Adds two numbers. */\nint add() { return a; }", it))
        }
    }

    // outline/11-comment-only-changes R3
    @Test
    fun `an added or removed line comment is comment-only under every policy`() {
        WhitespacePolicy.entries.forEach {
            assertTrue("$it added", commentOnly("{\n    a();\n}", "{\n    // first\n    a();\n}", it))
            assertTrue("$it removed", commentOnly("{\n    a(); // note\n}", "{\n    a();\n}", it))
        }
    }

    // outline/11-comment-only-changes R3
    @Test
    fun `a removed block comment between tokens is comment-only`() {
        assertTrue(commentOnly("f(a, /* old */ b)", "f(a, b)", INSIGNIFICANT))
        assertTrue(commentOnly("f(a, /* old */ b)", "f(a, b)", SEPARATION_SIGNIFICANT))
    }

    // outline/11-comment-only-changes R5
    @Test
    fun `a comment and formatting change together is comment-only`() {
        assertTrue(commentOnly("{ a(); // x\n}", "{\n  a();  // y\n}", INSIGNIFICANT))
        assertTrue(commentOnly("a  +  b // x", "a + b // y", SEPARATION_SIGNIFICANT))
    }

    // outline/11-comment-only-changes R3
    @Test
    fun `a comment change next to a code change is not comment-only`() {
        WhitespacePolicy.entries.forEach { assertFalse("$it", commentOnly("// x\nreturn a;", "// y\nreturn b;", it)) }
    }

    // outline/11-comment-only-changes R4
    @Test
    fun `a change inside a string is not comment-only`() {
        WhitespacePolicy.entries.forEach { assertFalse("$it", commentOnly("// x\nf(\"a\");", "// y\nf(\"b\");", it)) }
    }

    // outline/11-comment-only-changes R4
    @Test
    fun `a signature change or a parse error is never comment-only`() {
        assertFalse(commentOnly("// x\nf();", "// y\nf();", INSIGNIFICANT, signatureChanged = true))
        assertFalse(commentOnly("// x\nf();", "// y\nf();", INSIGNIFICANT, parseError = true))
    }

    // outline/11-comment-only-changes R3
    @Test
    fun `identical sources and sources without comments are never comment-only`() {
        assertFalse(commentOnly("// x\nf();", "// x\nf();", INSIGNIFICANT))
        assertFalse(commentOnly("f( );", "f();", INSIGNIFICANT))
    }

    // outline/11-comment-only-changes R3
    @Test
    fun `whitespace the policy counts keeps the declaration changed`() {
        assertFalse(commentOnly("// x\na b", "// y\na\nb", LINE_BREAKS_SIGNIFICANT))
        assertFalse(commentOnly("// x\nif a:\n    b", "// y\nif a:\n  b", INDENTATION_SIGNIFICANT))
        assertFalse(commentOnly("// x\na  b", "// y\na b", SIGNIFICANT))
    }

    // outline/11-comment-only-changes R3
    @Test
    fun `a removed comment keeps the indentation of the next line under significant indentation`() {
        assertTrue(commentOnly("if a:\n    // check\n    b", "if a:\n    b", INDENTATION_SIGNIFICANT))
    }

    // outline/11-comment-only-changes R3
    @Test
    fun `a removed multi-line block comment still separates the lines around it`() {
        assertTrue(commentOnly("a\n/* one\ntwo */\nb", "a\nb", LINE_BREAKS_SIGNIFICANT))
    }

    private fun commentOnly(
        old: String,
        new: String,
        policy: WhitespacePolicy,
        parseError: Boolean = false,
        signatureChanged: Boolean = false,
    ) = ContentClassifier.isCommentOnly(tokens(old), tokens(new), policy, parseError, signatureChanged)
}
