package io.github.agadek.smartdiff.engine.treesitter

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.LanguageIds
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.treesitter.TreeSitterTrees.Companion.all
import io.github.agadek.smartdiff.engine.treesitter.TreeSitterTrees.Companion.assertCoveredOnce
import io.github.agadek.smartdiff.engine.treesitter.TreeSitterTrees.Companion.contentTokens
import io.github.agadek.smartdiff.engine.treesitter.TreeSitterTrees.Companion.outline
import java.io.File
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Macro recovery in the Tree-sitter engine (engines/06 R1 to R6, R18), with the helper the build made. */
class CMacroRecoveryTreeTest {
    private val engine = TreeSitterEngine(TreeSitterHelpers { Path.of(System.getProperty(TreeSitterHelpers.HELPER_PROPERTY)) }, enabled = { true })
    private val plain = TreeSitterTrees(CppLanguage)

    @After
    fun close() = plain.close()

    private fun trees(old: String, new: String = old, language: String = LanguageIds.TREE_SITTER_CPP, related: List<String> = emptyList()) =
        engine.trees(ComparisonRequest(old, new, language, relatedTexts = related), Cancellation.NONE)!!

    private fun fixture(path: String) = File("src/test/testData/treesitter/$path").readText()

    private fun DeclarationTree.hasParseError() = fileRegion.hasParseError || all().any { it.hasParseError }

    // engines/06 R1, R7, R9, R11, R13, R14
    @Test
    fun `a C++ header with macros is analyzed`() {
        val text = fixture("cpp/macro-recovery/old.cpp")
        assertTrue("without recovery the grammar fails", plain.tree(text).hasParseError())
        val (tree, _) = trees(text)
        assertFalse(tree.hasParseError())
        assertEquals(
            listOf(
                "MACRO FMT_ARGS_H_",
                "NAMESPACE detail",
                "  FUNCTION to_unsigned(T)",
                "  STRUCT node",
                "    CONSTRUCTOR node(int)",
                "    METHOD get()",
                "    FIELD value",
                "CLASS format_error",
                "FUNCTION report_error(const char*)",
            ),
            tree.outline(),
        )
        assertCoveredOnce(tree, text)
    }

    // engines/06 R1, R7, R8, R10, R12, R16, R17
    @Test
    fun `a C file with macros is analyzed`() {
        val text = fixture("c/macro-recovery/old.c")
        val (tree, _) = trees(text, language = LanguageIds.TREE_SITTER_C)
        assertFalse(tree.hasParseError())
        assertEquals(listOf("MACRO JV_H", "TYPE_ALIAS jv", "  FIELD kind", "FUNCTION jv_string_fmt(const char*, ...)", "FUNCTION jv_count(jv)"), tree.outline())
        assertCoveredOnce(tree, text)
    }

    // engines/06 R5, R6: an attribute macro is the declaration's own source
    @Test
    fun `an attribute macro is part of the declaration after it`() {
        val text = fixture("cpp/macro-recovery/old.cpp")
        val (tree, _) = trees(text)
        val report = tree.all().single { it.name == "report_error" }
        assertEquals(text.indexOf("FMT_API void report_error"), report.range.start)
        assertTrue(contentTokens(report).contains("FMT_API"))
        val error = tree.all().single { it.name == "format_error" }
        assertEquals(text.indexOf("/** An error. */"), error.range.start)
        assertTrue(contentTokens(error).contains("FMT_SO_VISIBILITY(\"default\")"))
        val newText = text.replace("FMT_API void report_error", "FMT_EXPORT void report_error")
        val (_, newTree) = trees(text, newText)
        assertNotEquals(contentTokens(report), contentTokens(newTree.all().single { it.name == "report_error" }))
    }

    // engines/06 R5: a branch that recovery reads as blank space is still the function's own source
    @Test
    fun `a later branch in a function body is still compared`() {
        val old = fixture("c/macro-recovery/old.c")
        val new = old.replace("if (number(x))", "if (is_number(x))")
        val (oldTree, newTree) = trees(old, new, LanguageIds.TREE_SITTER_C)
        val before = contentTokens(oldTree.all().single { it.name == "jv_count" })
        val after = contentTokens(newTree.all().single { it.name == "jv_count" })
        assertTrue(before.joinToString(" ").contains("if (number(x))"))
        assertTrue(after.joinToString(" ").contains("if (is_number(x))"))
    }

    // engines/06 R2: a comparison without parse errors is read as before
    @Test
    fun `a file without parse errors is not recovered`() {
        val text = "struct LEVELDB_EXPORT Options {\n  int size;\n};\n"
        val (tree, _) = trees(text)
        assertEquals(plain.tree(text).outline(), tree.outline())
        assertFalse("the misread name stays as the grammar reads it", "STRUCT Options" in tree.outline())
    }

    // engines/06 R3: a side without a parse error is read with recovery when the other side has one
    @Test
    fun `both sides use recovery or neither does`() {
        val clean = "struct LEVELDB_EXPORT Options {\n  int size;\n};\n"
        val broken = "FMT_BEGIN_NAMESPACE\n$clean"
        val (oldTree, newTree) = trees(clean, broken)
        assertEquals(listOf("STRUCT Options", "  FIELD size"), oldTree.outline())
        assertEquals(listOf("STRUCT Options", "  FIELD size"), newTree.outline())
        // In the merge window the third version decides too.
        val (base, left) = trees(clean, clean, related = listOf(broken))
        assertEquals(listOf("STRUCT Options", "  FIELD size"), base.outline())
        assertEquals(base.outline(), left.outline())
    }

    // engines/06 R18: the line break that ends a masked directive is content
    @Test
    fun `the line break after a masked include guard is content`() {
        val text = fixture("cpp/macro-recovery/old.cpp")
        val (tree, _) = trees(text)
        // The file region starts with the masked `#ifndef FMT_ARGS_H_`, its words and then its line break.
        val first = tree.fileRegion.tokens.filter { it.kind != TokenKind.WHITESPACE }.take(3)
        assertEquals(listOf("#ifndef", "FMT_ARGS_H_", "\n"), first.map { it.text })
        assertEquals(TreeSitterTokens.LINE_BREAK, first[2].type)
    }
}
