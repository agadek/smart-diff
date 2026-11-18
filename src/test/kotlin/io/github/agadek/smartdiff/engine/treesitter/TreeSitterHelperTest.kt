package io.github.agadek.smartdiff.engine.treesitter

import io.github.agadek.smartdiff.engine.api.Cancellation
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Test

/** The helper process of ADR 0016 and its protocol (engines/05-tree-sitter-details §8), with the helper the build made. */
class TreeSitterHelperTest {
    private val executable: Path = Path.of(
        System.getProperty(TreeSitterHelpers.HELPER_PROPERTY) ?: error("run through Gradle, which builds the helper"),
    )
    private val opened = mutableListOf<AutoCloseable>()

    @After
    fun closeHelpers() = opened.forEach(AutoCloseable::close)

    private fun helper() = TreeSitterHelper(executable).also(opened::add)

    // engines/05 R22, R26: this platform's helper is built and runs
    @Test
    fun `every grammar names its symbols and fields`() {
        val helper = helper()
        val expected = mapOf(
            TreeSitterGrammar.SWIFT to "function_declaration",
            TreeSitterGrammar.C_SHARP to "method_declaration",
            TreeSitterGrammar.C to "function_definition",
            TreeSitterGrammar.CPP to "namespace_definition",
        )
        for ((grammar, symbol) in expected) {
            val names = helper.names(grammar)
            assertTrue("$grammar has $symbol", symbol in names.symbols)
            assertEquals("field id 0 has no name", "", names.fields.first())
            assertTrue("$grammar has the name field", "name" in names.fields)
        }
    }

    // engines/05 R2, R8: a Swift sample parses into a tree that covers the text
    @Test
    fun `a Swift file parses with offsets in the text's chars`() {
        val text = "// ünïcödé 😀\nfinal class Cart {\n    func add(_ item: Int) {}\n}\n"
        val helper = helper()
        val names = helper.names(TreeSitterGrammar.SWIFT)
        val tree = helper.parse(TreeSitterGrammar.SWIFT, text, Cancellation.NONE)
        assertEquals("the root covers the text", text.length, tree.end(0))
        assertFalse((0 until tree.size).any { tree.isError(it) || tree.isMissing(it) })
        val type = (0 until tree.size).single { names.symbols[tree.symbol(it)] == "class_declaration" }
        assertEquals("final class Cart {\n    func add(_ item: Int) {}\n}", text.substring(tree.start(type), tree.end(type)))
        val function = (0 until tree.size).single { names.symbols[tree.symbol(it)] == "function_declaration" }
        val name = (0 until tree.size).single { tree.parent(it) == function && names.fields[tree.field(it)] == "name" }
        assertEquals("add", text.substring(tree.start(name), tree.end(name)))
    }

    // engines/05 R9, R10, R11: the other grammars parse their samples without errors
    @Test
    fun `C#, C and C++ samples parse without errors`() {
        val samples = mapOf(
            TreeSitterGrammar.C_SHARP to "namespace Shop;\npublic record Cart(int Id) { public void Add(int x) {} }\n",
            TreeSitterGrammar.C to "#define MAX 3\ntypedef struct { int x; } Point;\nint add(int a, int b) { return a + b; }\n",
            TreeSitterGrammar.CPP to "namespace shop {\nclass Cart { int count() const; };\nint Cart::count() const { return 0; }\n}\n",
        )
        val helper = helper()
        for ((grammar, text) in samples) {
            val tree = helper.parse(grammar, text, Cancellation.NONE)
            assertEquals(text.length, tree.end(0))
            assertFalse("$grammar has no error", (0 until tree.size).any { tree.isError(it) || tree.isMissing(it) })
        }
    }

    // engines/05 R20: source the grammar cannot parse is marked, not dropped
    @Test
    fun `a parse error is marked in the tree`() {
        val text = "func broken( {\n"
        val tree = helper().parse(TreeSitterGrammar.SWIFT, text, Cancellation.NONE)
        assertTrue((0 until tree.size).any { tree.isError(it) || tree.isMissing(it) })
    }

    // engines/05 R24: a killed helper fails the comparison, and the next comparison starts a new helper
    @Test
    fun `a helper that dies fails one comparison and is started again`() {
        val helpers = TreeSitterHelpers { executable }.also { opened += AutoCloseable(it::shutdown) }
        helpers.use(Cancellation.NONE) { it.parse(TreeSitterGrammar.C, "int x;", Cancellation.NONE) }
        try {
            helpers.use(Cancellation.NONE) { helper ->
                helper.close()
                helper.parse(TreeSitterGrammar.C, "int x;", Cancellation.NONE)
            }
            fail("a dead helper should fail")
        } catch (expected: TreeSitterHelperException) {
        }
        val tree = helpers.use(Cancellation.NONE) { it.parse(TreeSitterGrammar.C, "int y;", Cancellation.NONE) }
        assertEquals(6, tree.end(0))
        assertNull(helpers.disabled)
    }

    // engines/05 R25: a cancelled comparison stops the helper, and the next comparison works
    @Test
    fun `a cancelled parse stops the helper without counting as a failure`() {
        val helpers = TreeSitterHelpers { executable }.also { opened += AutoCloseable(it::shutdown) }
        val big = "func f() { let x = 1 }\n".repeat(100_000)
        repeat(TreeSitterHelpers.MAX_FAILURES + 1) {
            try {
                helpers.use(Cancellation.NONE) { it.parse(TreeSitterGrammar.SWIFT, big, Cancellation { throw CancellationException() }) }
                fail("the parse should be cancelled")
            } catch (expected: CancellationException) {
            }
        }
        assertNull("cancellations never turn the helper off", helpers.disabled)
        assertEquals(6, helpers.use(Cancellation.NONE) { it.parse(TreeSitterGrammar.C, "int x;", Cancellation.NONE) }.end(0))
    }

    // engines/05 R23: no helper for this platform turns Tree-sitter off for the session
    @Test
    fun `a missing helper turns Tree-sitter off`() {
        val helpers = TreeSitterHelpers { null }
        try {
            helpers.use(Cancellation.NONE) { it.names(TreeSitterGrammar.SWIFT) }
            fail("no helper should fail")
        } catch (expected: TreeSitterHelperException) {
        }
        assertNotNull(helpers.disabled)
    }

    // engines/05 R23, R24: a helper that answers garbage fails, and after three failures in a row Tree-sitter is off
    @Test
    fun `a helper that keeps answering garbage is turned off after three failures`() {
        assumeFalse(System.getProperty("os.name").lowercase().startsWith("windows"))
        val script = Files.createTempFile("garbage-helper", ".sh")
        Files.writeString(script, "#!/bin/sh\nhead -c 5 > /dev/null\nprintf 'xxxxxxxx'\n")
        script.toFile().setExecutable(true)
        val helpers = TreeSitterHelpers { script }
        repeat(TreeSitterHelpers.MAX_FAILURES) {
            assertNull(helpers.disabled)
            try {
                helpers.use(Cancellation.NONE) { it.parse(TreeSitterGrammar.C, "", Cancellation.NONE) }
                fail("garbage should fail")
            } catch (expected: TreeSitterHelperException) {
            }
        }
        assertNotNull(helpers.disabled)
        Files.delete(script)
    }
}
