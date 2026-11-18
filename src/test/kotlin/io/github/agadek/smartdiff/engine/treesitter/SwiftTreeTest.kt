package io.github.agadek.smartdiff.engine.treesitter

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Swift declaration table and the tree builder (engines/05 R8, R13 to R16, R20). */
class SwiftTreeTest {
    private val helper = TreeSitterHelper(Path.of(System.getProperty(TreeSitterHelpers.HELPER_PROPERTY)))

    @After
    fun close() = helper.close()

    private fun tree(text: String): DeclarationTree {
        val syntax = TreeSitterSyntax(helper.parse(TreeSitterGrammar.SWIFT, text, Cancellation.NONE), helper.names(TreeSitterGrammar.SWIFT), text)
        return TreeSitterTreeBuilder.build(syntax, SwiftLanguage, Cancellation.NONE)
    }

    private fun DeclarationTree.outline(): List<String> = buildList {
        fun visit(declaration: Declaration, depth: Int) {
            add("  ".repeat(depth) + "${declaration.kind} ${declaration.name}" + (declaration.signature?.let { "($it)" } ?: ""))
            declaration.children.forEach { visit(it, depth + 1) }
        }
        topLevel.forEach { visit(it, 0) }
    }

    private val sample = """
        import Foundation

        /// A priced thing.
        protocol Priced {
            var price: Double { get }
            func cost(quantity: Int) -> Double
        }

        /// The cart.
        @MainActor
        final class Cart<Item>: Priced {
            let a = 1, b = 2
            var price: Double { let local = 1; return 0 }
            init?(items: [Item] = []) {}
            subscript(index: Int) -> Item? { nil }
            func move(from a: Int, to b: Int) { func inner() {} }
            static func + (l: Cart, r: Cart) -> Cart { l }
            deinit {}
        }

        enum Size { case small, large(Int) }
        extension Cart where Item: Hashable {}
        actor Counter {}
        struct Point {}
        typealias Basket = Cart<String>
        func top(_ x: Int, _ f: @escaping (Int) -> Void) {}

    """.trimIndent()

    // engines/05 R8, R13, R16
    @Test
    fun `Swift declarations, members and signatures`() {
        assertEquals(
            listOf(
                "PROTOCOL Priced",
                "  PROPERTY price",
                "  METHOD cost(quantity: Int)",
                "CLASS Cart",
                "  PROPERTY a",
                "  PROPERTY b",
                "  PROPERTY price",
                "  CONSTRUCTOR init?(items: [Item])",
                "  SUBSCRIPT subscript(index: Int)",
                "  METHOD move(from: Int, to: Int)",
                "  METHOD +(l: Cart, r: Cart)",
                "  DESTRUCTOR deinit",
                "ENUM Size",
                "  ENUM_CONSTANT small",
                "  ENUM_CONSTANT large",
                "EXTENSION Cart",
                "ACTOR Counter",
                "STRUCT Point",
                "TYPE_ALIAS Basket",
                "FUNCTION top(_: Int, _: (Int) -> Void)",
            ),
            tree(sample).outline(),
        )
    }

    // engines/05 R15
    @Test
    fun `a range includes the doc comment above and the attributes`() {
        val cart = tree(sample).topLevel.single { it.name == "Cart" && it.kind == DeclarationKind.CLASS }
        assertTrue(sample.substring(cart.range.start, cart.range.end).startsWith("/// The cart.\n@MainActor\nfinal class Cart"))
        val priced = tree(sample).topLevel.single { it.name == "Priced" }
        assertTrue(sample.substring(priced.range.start, priced.range.end).startsWith("/// A priced thing."))
    }

    // engines/05 R8: `let a = 1, b = 2` is two declarations, and the comma belongs to the class
    @Test
    fun `several bindings in one property are separate declarations`() {
        val members = tree(sample).topLevel.single { it.name == "Cart" && it.kind == DeclarationKind.CLASS }.children
        val a = members.single { it.name == "a" }
        val b = members.single { it.name == "b" }
        assertEquals("let a = 1", sample.substring(a.range.start, a.range.end))
        assertEquals("b = 2", sample.substring(b.range.start, b.range.end))
    }

    // engines/05 R21: a function has a body for extraction detection, inside its braces
    @Test
    fun `a function body is the inside of its braces`() {
        val move = tree(sample).topLevel.single { it.name == "Cart" && it.kind == DeclarationKind.CLASS }.children.single { it.name == "move" }
        val body = move.body ?: error("no body")
        assertEquals(" func inner() {} ", sample.substring(body.start, body.end))
    }

    // engines/05 R20
    @Test
    fun `a parse error marks the declaration that holds it`() {
        val text = "func good() -> Int { 1 }\n\nfunc other( -> Int { 2 }\n"
        val tree = tree(text)
        assertFalse(tree.topLevel.first { it.name == "good" }.hasParseError)
        assertTrue(tree.topLevel.any { it.hasParseError } || tree.fileRegion.hasParseError)
    }

    // engines/02 R1: every character is in a declaration's own source or the file region
    @Test
    fun `every character is covered once`() {
        val tree = tree(sample)
        val covered = BooleanArray(sample.length)
        fun mark(range: TextRange) {
            for (i in range.start until range.end) {
                assertFalse("offset $i is covered twice", covered[i])
                covered[i] = true
            }
        }
        fun visit(declaration: Declaration) {
            val start = declaration.leadingWhitespace?.start ?: declaration.range.start
            val children = declaration.children.map { (it.leadingWhitespace?.start ?: it.range.start) to it.range.end }
            var at = start
            for ((from, to) in children) {
                mark(TextRange(at, from))
                at = to
            }
            mark(TextRange(at, declaration.range.end))
            declaration.children.forEach(::visit)
        }
        tree.topLevel.forEach(::visit)
        tree.fileRegion.ranges.forEach(::mark)
        assertTrue("every offset is covered", covered.all { it })
        assertEquals(DeclarationKind.CLASS, tree.topLevel[1].kind)
    }
}
