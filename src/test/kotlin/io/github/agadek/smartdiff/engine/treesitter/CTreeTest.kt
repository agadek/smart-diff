package io.github.agadek.smartdiff.engine.treesitter

import io.github.agadek.smartdiff.engine.treesitter.TreeSitterTrees.Companion.all
import io.github.agadek.smartdiff.engine.treesitter.TreeSitterTrees.Companion.assertCoveredOnce
import io.github.agadek.smartdiff.engine.treesitter.TreeSitterTrees.Companion.contentTokens
import io.github.agadek.smartdiff.engine.treesitter.TreeSitterTrees.Companion.outline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The C declaration table and its preprocessor rules (engines/05 R10, R13 to R18). */
class CTreeTest {
    private val trees = TreeSitterTrees(CLanguage)

    @After
    fun close() = trees.close()

    private val sample = """
        #include <stdio.h>
        #define MAX 10
        #define SQ(x) ((x) * (x))

        /** Adds two numbers. */
        static int add(int a, int b) { int local = 1; return a + b + local; }
        int proto(const char *name, int);
        void none(void);
        struct point { int x, y; char *name; };
        union value { int i; float f; };
        enum color { RED, GREEN = 2 };
        typedef struct { int w; } size2;
        typedef unsigned long ulong;
        int counter = 0, *ptr;
        #ifdef DEBUG
        int level(void) { return 1; }
        #else
        int level(void) { return 0; }
        #endif
        int (*callback)(int);
        int sum(int count, ...);

    """.trimIndent()

    // engines/05 R10, R13, R14, R16
    @Test
    fun `C declarations, members and signatures`() {
        assertEquals(
            listOf(
                "MACRO MAX",
                "MACRO SQ",
                "FUNCTION add(int, int)",
                "FUNCTION proto(const char*, int)",
                "FUNCTION none()",
                "STRUCT point",
                "  FIELD x",
                "  FIELD y",
                "  FIELD name",
                "UNION value",
                "  FIELD i",
                "  FIELD f",
                "ENUM color",
                "  ENUM_CONSTANT RED",
                "  ENUM_CONSTANT GREEN",
                "TYPE_ALIAS size2",
                "  FIELD w",
                "TYPE_ALIAS ulong",
                "VARIABLE counter",
                "VARIABLE ptr",
                "FUNCTION level()",
                "FUNCTION level()",
                "VARIABLE callback",
                "FUNCTION sum(int, ...)",
            ),
            trees.tree(sample).outline(),
        )
    }

    // engines/05 R15: the doc comment, and the `;` after a struct
    @Test
    fun `a range includes the doc comment above and the semicolon after a struct`() {
        val tree = trees.tree(sample)
        val add = tree.topLevel.single { it.name == "add" }
        assertTrue(sample.substring(add.range.start, add.range.end).startsWith("/** Adds two numbers. */\nstatic int add"))
        val point = tree.topLevel.single { it.name == "point" }
        assertTrue(sample.substring(point.range.start, point.range.end).endsWith("};"))
    }

    // engines/05 R17, R18: spacing is formatting, a directive's line break is not
    @Test
    fun `a line break inside a directive is content and spaces are not`() {
        fun macro(text: String) = contentTokens(trees.tree(text).all().single { it.name == "AREA" })
        val oneLine = macro("#define AREA(w, h) ((w) * (h))\nint x;\n")
        assertEquals(oneLine, macro("#define AREA(w, h)   ((w)  *  (h))\nint x;\n"))
        assertNotEquals(oneLine, macro("#define AREA(w, h) ((w) * \\\n    (h))\nint x;\n"))
        fun function(text: String) = contentTokens(trees.tree(text).all().single { it.name == "f" })
        assertEquals(function("int f(int a) { return a; }\n"), function("int f(int a)\n{\n    return a;\n}\n"))
    }

    // engines/02 R1
    @Test
    fun `every character is covered once`() = assertCoveredOnce(trees.tree(sample), sample)
}
