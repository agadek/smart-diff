package io.github.agadek.smartdiff.engine.treesitter

import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.treesitter.TreeSitterTrees.Companion.all
import io.github.agadek.smartdiff.engine.treesitter.TreeSitterTrees.Companion.assertCoveredOnce
import io.github.agadek.smartdiff.engine.treesitter.TreeSitterTrees.Companion.outline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The C# declaration table (engines/05 R9, R13, R15, R16, R20). */
class CSharpTreeTest {
    private val trees = TreeSitterTrees(CSharpLanguage)

    @After
    fun close() = trees.close()

    private val sample = """
        using System;

        namespace Shop {
            /// <summary>The cart.</summary>
            [Serializable]
            public class Cart<T> : IPriced where T : class {
                public int A = 1, B;
                public double Price { get { int local = 1; return local; } }
                public Cart(int n = 3) {}
                ~Cart() {}
                public static Cart<T> operator +(Cart<T> a, Cart<T> b) => a;
                public static implicit operator int(Cart<T> c) => 0;
                public int this[int i] => i;
                public event EventHandler Changed;
                public event EventHandler Other { add {} remove {} }
                public void Move(ref int from, out int to, params int[] rest) { void Inner() {} to = 0; }
                public void Fill(Dictionary<int,string> map) {}
            }

            public delegate void Handler(object sender, EventArgs e);
            interface IPriced { double Price { get; } }
            enum Size { Small, Large = 2 }
            struct Point {}
            record Person(string Name);
        }

        namespace Other;

        class Last {}

    """.trimIndent()

    // engines/05 R9, R13, R16
    @Test
    fun `C# declarations, members and signatures`() {
        assertEquals(
            listOf(
                "NAMESPACE Shop",
                "  CLASS Cart",
                "    FIELD A",
                "    FIELD B",
                "    PROPERTY Price",
                "    CONSTRUCTOR Cart(int)",
                "    DESTRUCTOR ~Cart",
                "    OPERATOR operator +(Cart<T>, Cart<T>)",
                "    OPERATOR implicit operator int(Cart<T>)",
                "    INDEXER this(int)",
                "    EVENT Changed",
                "    EVENT Other",
                "    METHOD Move(ref int, out int, params int[])",
                "    METHOD Fill(Dictionary<int, string>)",
                "  DELEGATE Handler(object, EventArgs)",
                "  INTERFACE IPriced",
                "    PROPERTY Price",
                "  ENUM Size",
                "    ENUM_CONSTANT Small",
                "    ENUM_CONSTANT Large",
                "  STRUCT Point",
                "  RECORD Person",
                "NAMESPACE Other",
                "  CLASS Last",
            ),
            trees.tree(sample).outline(),
        )
    }

    // engines/05 R9: a file-scoped namespace holds the declarations after it
    @Test
    fun `a file-scoped namespace runs to the end of the file`() {
        val other = trees.tree(sample).topLevel.single { it.name == "Other" }
        assertEquals("namespace Other;\n\nclass Last {}", sample.substring(other.range.start, other.range.end))
    }

    // engines/05 R15
    @Test
    fun `a range includes the doc comment above and the attributes`() {
        val cart = trees.tree(sample).all().single { it.name == "Cart" && it.kind == DeclarationKind.CLASS }
        assertTrue(sample.substring(cart.range.start, cart.range.end).startsWith("/// <summary>The cart.</summary>\n    [Serializable]\n    public class"))
    }

    // engines/05 R9: one field per variable, and the comma belongs to the class
    @Test
    fun `several variables in one field are separate declarations`() {
        val members = trees.tree(sample).all().single { it.name == "Cart" && it.kind == DeclarationKind.CLASS }.children
        assertEquals("public int A = 1", members.single { it.name == "A" }.let { sample.substring(it.range.start, it.range.end) })
        assertEquals("B;", members.single { it.name == "B" }.let { sample.substring(it.range.start, it.range.end) })
    }

    // engines/05 R13, R21: a local function is part of its method's body
    @Test
    fun `a method body is the inside of its braces`() {
        val move = trees.tree(sample).all().single { it.name == "Move" }
        assertTrue(move.children.isEmpty())
        val body = move.body ?: error("no body")
        assertEquals(" void Inner() {} to = 0; ", sample.substring(body.start, body.end))
    }

    // engines/05 R20
    @Test
    fun `a parse error marks the declaration that holds it`() {
        val text = "class A {\n    void Good() {}\n    void Bad( { }\n}\n"
        val tree = trees.tree(text)
        assertFalse(tree.all().first { it.name == "Good" }.hasParseError)
        assertTrue(tree.all().any { it.hasParseError } || tree.fileRegion.hasParseError)
    }

    // engines/02 R1
    @Test
    fun `every character is covered once`() = assertCoveredOnce(trees.tree(sample), sample)
}
