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

/** The C++ declaration table (engines/05 R11 to R16, R20). */
class CppTreeTest {
    private val trees = TreeSitterTrees(CppLanguage)

    @After
    fun close() = trees.close()

    private val sample = """
        #include <vector>

        namespace shop {
        namespace {
        int hidden;
        }

        /// The cart.
        class Cart : public Base {
        public:
            Cart();
            explicit Cart(int n = 3) : n_(n) {}
            ~Cart() override;
            virtual double price(const std::vector<int>& items, int) const = 0;
            Cart& operator=(const Cart&) = default;
            operator bool() const { return true; }
            static int count;
            template <typename T> T get(T fallback) { auto f = [](int x) { return x; }; return fallback; }
            using Item = int;
            friend class Helper;
        #ifdef DEBUG
            void dump() const;
        #endif
        private:
            int n_ = 0, m_;
            struct Inner { int z; };
        };

        double Cart::price(const std::vector<int>& items, int) const { return 0; }
        Cart::Cart() {}
        Cart::~Cart() {}

        template <typename T>
        struct Box { T value; };

        template <typename T> T twice(T v) { return v + v; }
        using Basket = std::vector<Cart>;
        extern "C" {
        int c_api(void);
        }
        enum class Mode : int { On, Off };
        }

        namespace a::b { void deep(); }

    """.trimIndent()

    // engines/05 R11, R12, R13, R14, R16
    @Test
    fun `C++ declarations, members and signatures`() {
        assertEquals(
            listOf(
                "NAMESPACE shop",
                "  NAMESPACE (anonymous)",
                "    VARIABLE hidden",
                "  CLASS Cart",
                "    CONSTRUCTOR Cart()",
                "    CONSTRUCTOR Cart(int)",
                "    DESTRUCTOR ~Cart()",
                "    METHOD price(const std::vector<int>&, int)",
                "    METHOD operator=(const Cart&)",
                "    METHOD operator bool()",
                "    FIELD count",
                "    METHOD get(T)",
                "    TYPE_ALIAS Item",
                "    METHOD dump()",
                "    FIELD n_",
                "    FIELD m_",
                "    STRUCT Inner",
                "      FIELD z",
                "  FUNCTION Cart::price(const std::vector<int>&, int)",
                "  CONSTRUCTOR Cart::Cart()",
                "  DESTRUCTOR Cart::~Cart()",
                "  STRUCT Box",
                "    FIELD value",
                "  FUNCTION twice(T)",
                "  TYPE_ALIAS Basket",
                "  FUNCTION c_api()",
                "  ENUM Mode",
                "    ENUM_CONSTANT On",
                "    ENUM_CONSTANT Off",
                "NAMESPACE a::b",
                "  FUNCTION deep()",
            ),
            trees.tree(sample).outline(),
        )
    }

    // engines/05 R11, R15: a template's range includes its parameters, a class's its doc comment and `;`
    @Test
    fun `ranges include templates, doc comments and the semicolon after a class`() {
        val tree = trees.tree(sample)
        val box = tree.all().single { it.name == "Box" }
        assertEquals("template <typename T>\nstruct Box { T value; };", sample.substring(box.range.start, box.range.end))
        val cart = tree.all().single { it.name == "Cart" && it.kind == DeclarationKind.CLASS }
        val cartText = sample.substring(cart.range.start, cart.range.end)
        assertTrue(cartText.startsWith("/// The cart.\nclass Cart") && cartText.endsWith("};"))
    }

    // engines/05 R12: a method defined outside its class is a declaration where it is written
    @Test
    fun `a qualified definition is named Type name in its namespace`() {
        val shop = trees.tree(sample).topLevel.single { it.name == "shop" }
        val price = shop.children.single { it.name == "Cart::price" }
        assertEquals(DeclarationKind.FUNCTION, price.kind)
        assertTrue(price.body != null)
    }

    // engines/05 R20
    @Test
    fun `a parse error marks the declaration that holds it`() {
        val text = "int good() { return 1; }\n\nint bad( { return 2; }\n"
        val tree = trees.tree(text)
        assertFalse(tree.topLevel.first { it.name == "good" }.hasParseError)
        assertTrue(tree.all().any { it.hasParseError } || tree.fileRegion.hasParseError)
    }

    // engines/02 R1
    @Test
    fun `every character is covered once`() = assertCoveredOnce(trees.tree(sample), sample)
}
