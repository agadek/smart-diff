package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

/** Matching rules M1 to M5 and the order within a container (technical specification §7), on hand-built trees. */
class DeclarationMatcherTest {

    @Test
    fun `M1 matches the same kind, path, name and signature`() {
        val old = tree { cls("A") { method("foo", "int"); field("x") } }
        val new = tree { cls("A") { method("foo", "int"); field("x") } }

        val matching = match(old, new)

        assertEquals(3, matching.matches.size)
        matching.matches.forEach {
            assertEquals(MatchRule.IDENTITY, it.rule)
            assertFalse(it.moved)
        }
        assertSame(new.named("foo"), matching.ofOld(old.named("foo"))!!.new)
        assertNothingLeft(matching)
    }

    @Test
    fun `M2 matches a declaration moved to another container`() {
        val old = tree { cls("A") { method("foo", "int") }; cls("B") }
        val new = tree { cls("A"); cls("B") { method("foo", "int") } }

        val foo = match(old, new).ofOld(old.named("foo"))!!

        assertEquals(MatchRule.PATH_CHANGED, foo.rule)
        assertSame(new.named("foo"), foo.new)
        assertTrue(foo.containerChanged)
        assertTrue(foo.moved)
    }

    @Test
    fun `M2 matches a declaration moved into a nested container`() {
        val old = tree { cls("A") { method("foo") } }
        val new = tree { cls("A") { cls("Inner") { method("foo") } } }

        val matching = match(old, new)

        assertTrue(matching.ofOld(old.named("foo"))!!.containerChanged)
        assertEquals(listOf(new.named("Inner")), matching.added)
    }

    @Test
    fun `members of a moved container are not moved themselves`() {
        val old = tree { cls("A") { cls("Inner") { method("foo"); method("bar") } }; cls("B") }
        val new = tree { cls("A"); cls("B") { cls("Inner") { method("foo"); method("bar") } } }

        val matching = match(old, new)

        assertTrue(matching.ofOld(old.named("Inner"))!!.moved)
        listOf("foo", "bar").forEach { name ->
            val member = matching.ofOld(old.named(name))!!
            assertEquals(MatchRule.PATH_CHANGED, member.rule)
            assertFalse("$name moved", member.moved)
        }
    }

    @Test
    fun `M3 matches a declaration whose signature changed`() {
        val old = tree { cls("A") { method("foo", "int") } }
        val new = tree { cls("A") { method("foo", "long") } }

        val foo = match(old, new).ofOld(old.named("foo"))!!

        assertEquals(MatchRule.SIGNATURE_CHANGED, foo.rule)
        assertFalse(foo.moved)
    }

    @Test
    fun `overloads match by signature before M3 pairs the rest`() {
        val old = tree { cls("A") { method("foo", "int"); method("foo", "String") } }
        val new = tree { cls("A") { method("foo", "String"); method("foo", "long") } }

        val matching = match(old, new)

        val string = matching.ofOld(old.all.single { it.signature == "String" })!!
        assertEquals(MatchRule.IDENTITY, string.rule)
        val changed = matching.ofOld(old.all.single { it.signature == "int" })!!
        assertEquals(MatchRule.SIGNATURE_CHANGED, changed.rule)
        assertEquals("long", changed.new.signature)
    }

    @Test
    fun `M4 marks same-named declarations that do not pair up once each as uncertain`() {
        val old = tree { cls("A") { method("foo", "int"); method("foo", "String") } }
        val new = tree { cls("A") { method("foo", "long"); method("foo", "char"); method("foo", "byte") } }

        val matching = match(old, new)

        val oldFoos = old.all.filter { it.name == "foo" }
        val newFoos = new.all.filter { it.name == "foo" }
        assertEquals(oldFoos, matching.uncertainOld.map { it.declaration })
        assertEquals(newFoos, matching.uncertainNew.map { it.declaration })
        matching.uncertainOld.forEach { assertEquals(newFoos, it.candidates) }
        matching.uncertainNew.forEach { assertEquals(oldFoos, it.candidates) }
        assertTrue(matching.removed.isEmpty())
        assertTrue(matching.added.isEmpty())
    }

    @Test
    fun `M1 pairs declarations of a repeated key by content`() {
        val old = tree { cls("A") { initializer("{a();}"); initializer("{b();}"); initializer("{c();}") } }
        val new = tree { cls("A") { initializer("{a();}"); initializer("{b();}"); initializer("{c();}") } }

        val matching = match(old, new)

        old.all.drop(1).zip(new.all.drop(1)).forEach { (o, n) ->
            assertSame(n, matching.ofOld(o)!!.new)
            assertEquals(MatchRule.IDENTITY, matching.ofOld(o)!!.rule)
        }
        assertNothingLeft(matching)
    }

    @Test
    fun `M1 pairs the rest of a repeated key once the identical ones are paired`() {
        val old = tree { cls("A") { initializer("{a();}"); initializer("{b();}") } }
        val new = tree { cls("A") { initializer("{a();}"); initializer("{b(); c();}") } }

        val matching = match(old, new)

        assertSame(new.all[2], matching.ofOld(old.all[2])!!.new)
        assertNothingLeft(matching)
    }

    @Test
    fun `a repeated key that still does not pair up once each stays uncertain`() {
        val old = tree { cls("A") { method("of", "E", "{x();}"); method("of", "E", "{y();}"); method("of", "E", "{z();}") } }
        val new = tree { cls("A") { method("of", "E", "{x();}"); method("of", "E", "{y2();}"); method("of", "E", "{z2();}") } }

        val matching = match(old, new)

        assertSame(new.all[1], matching.ofOld(old.all[1])!!.new)
        assertEquals(old.all.drop(2), matching.uncertainOld.map { it.declaration })
        assertEquals(new.all.drop(2), matching.uncertainNew.map { it.declaration })
    }

    @Test
    fun `a name only one side still has is removed or added, not uncertain`() {
        val old = tree { cls("A") { method("foo", "int"); method("foo", "String") } }
        val new = tree { cls("A") }

        val matching = match(old, new)

        assertTrue(matching.uncertainOld.isEmpty())
        assertEquals(old.all.filter { it.name == "foo" }, matching.removed)
    }

    @Test
    fun `unmatched declarations are removed or added`() {
        val old = tree { cls("A") { method("gone") } }
        val new = tree { cls("A") { method("fresh") } }

        val matching = match(old, new)

        assertEquals(listOf(old.named("gone")), matching.removed)
        assertEquals(listOf(new.named("fresh")), matching.added)
    }

    @Test
    fun `a changed kind is removed and added`() {
        val old = tree { declaration(DeclarationKind.CLASS, "Shape") }
        val new = tree { declaration(DeclarationKind.INTERFACE, "Shape") }

        val matching = match(old, new)

        assertTrue(matching.matches.isEmpty())
        assertEquals(old.all, matching.removed)
        assertEquals(new.all, matching.added)
    }

    @Test
    fun `a renamed class with similar content is a rename candidate and its members stay in it`() {
        val old = tree { cls("Parser") { method("parse", "String", BODY); field("cache") } }
        val new = tree { cls("Reader") { method("parse", "String", BODY); field("cache") } }

        val matching = match(old, new)

        val renamed = matching.ofOld(old.named("Parser"))!!
        assertEquals(MatchRule.RENAME_CANDIDATE, renamed.rule)
        assertSame(new.named("Reader"), renamed.new)
        listOf("parse", "cache").forEach { name ->
            val member = matching.ofOld(old.named(name))!!
            assertEquals(MatchRule.PATH_CHANGED, member.rule)
            assertFalse(member.moved)
        }
        assertNothingLeft(matching)
    }

    @Test
    fun `M5 pairs a removed and an added declaration with similar content as a rename candidate`() {
        val old = tree { cls("A") { method("total", "int", BODY); method("other", "", "{ unrelated(); work(); }") } }
        val new = tree { cls("A") { method("sum", "int", BODY.replace("x", "y")); method("other", "", "{ unrelated(); work(); }") } }

        val sum = match(old, new).ofOld(old.named("total"))!!

        assertEquals(MatchRule.RENAME_CANDIDATE, sum.rule)
        assertSame(new.named("sum"), sum.new)
    }

    @Test
    fun `M5 masks the declaration's own name, so a recursive method still pairs`() {
        val old = tree { cls("A") { method("count", "Node", "{ return n == null ? 0 : 1 + count(n.next); }") } }
        val new = tree { cls("A") { method("size", "Node", "{ return n == null ? 0 : 1 + size(n.next); }") } }

        assertEquals(MatchRule.RENAME_CANDIDATE, match(old, new).ofOld(old.named("count"))!!.rule)
    }

    @Test
    fun `M5 leaves dissimilar, small, differently kinded and differently placed declarations alone`() {
        val old = tree {
            cls("A") { method("gone", "", "{ alpha(); beta(); gamma(); }"); method("empty"); field("count", "private int count = 0;") }
            cls("B") { method("elsewhere", "", BODY) }
        }
        val new = tree {
            cls("A") { method("fresh", "", "{ return delta + epsilon * zeta; }"); method("blank"); method("total", "", "private int total = 0;") }
            cls("B")
            cls("C") { method("moved", "", BODY) }
        }

        val matching = match(old, new)

        assertEquals(listOf("gone", "empty", "count", "elsewhere"), matching.removed.map { it.name })
        assertEquals(listOf("fresh", "blank", "total", "C", "moved"), matching.added.map { it.name })
    }

    @Test
    fun `M5 leaves a rename unpaired when another declaration is about as similar`() {
        val old = tree { cls("A") { method("first", "", BODY); method("second", "", BODY) } }
        val new = tree { cls("A") { method("one", "", BODY) } }

        val matching = match(old, new)

        assertEquals(listOf("first", "second"), matching.removed.map { it.name })
        assertEquals(listOf("one"), matching.added.map { it.name })
    }

    @Test
    fun `M5 tells renames with the same content apart by their names`() {
        val old = tree { cls("A") { method("testShowOwner", "", BODY); method("testInitFindForm", "", BODY); method("testFirst", "", BODY) } }
        val new = tree { cls("A") { method("showOwner", "", BODY); method("initFindForm", "", BODY); method("one", "", BODY); method("two", "", BODY) } }

        val matching = match(old, new)

        assertSame(new.named("showOwner"), matching.ofOld(old.named("testShowOwner"))!!.new)
        assertSame(new.named("initFindForm"), matching.ofOld(old.named("testInitFindForm"))!!.new)
        assertEquals(listOf("testFirst"), matching.removed.map { it.name })
        assertEquals(listOf("one", "two"), matching.added.map { it.name })
    }

    @Test
    fun `the constructors of a renamed Java class follow it`() {
        val old = tree {
            cls("Parser", "{ private final String text; }") {
                declaration(DeclarationKind.CONSTRUCTOR, "Parser", "String", "Parser(String text) { this.text = text; }")
            }
        }
        val new = tree {
            cls("Reader", "{ private final String text; }") {
                declaration(DeclarationKind.CONSTRUCTOR, "Reader", "String", "Reader(String text) { this.text = text; }")
            }
        }

        val matching = match(old, new)

        assertEquals(MatchRule.RENAME_CANDIDATE, matching.ofOld(old.all[0])!!.rule)
        assertEquals(MatchRule.CONTAINER_RENAMED, matching.ofOld(old.all[1])!!.rule)
        assertNothingLeft(matching)
    }

    @Test
    fun `M3 pairs overloads whose signatures all changed with the most similar one`() {
        val old = tree {
            cls("A") {
                method("isAssignable", "Type,Map", "{ return isAssignable(type, toType, typeVarAssigns); }")
                method("isAssignable", "Type,Type,Map", "{ if (type == null) return true; return check(type, toType); }")
            }
        }
        val new = tree {
            cls("A") {
                method("isAssignable", "Type,Map,boolean", "{ return isAssignable(type, toType, typeVarAssigns, strict); }")
                method("isAssignable", "Type,Type,Map,boolean", "{ if (type == null) return true; return check(type, toType, strict); }")
            }
        }

        val matching = match(old, new)

        old.all.drop(1).zip(new.all.drop(1)).forEach { (o, n) ->
            assertSame(n, matching.ofOld(o)!!.new)
            assertEquals(MatchRule.SIGNATURE_CHANGED, matching.ofOld(o)!!.rule)
        }
        assertNothingLeft(matching)
    }

    @Test
    fun `reordered siblings outside the longest common subsequence are moved`() {
        val old = tree { cls("A") { method("a"); method("b"); method("c"); method("d") } }
        val new = tree { cls("A") { method("d"); method("a"); method("b"); method("c") } }

        val matching = match(old, new)

        assertEquals(listOf("d"), matching.matches.filter { it.moved }.map { it.old.name })
        assertTrue(matching.matches.none { it.containerChanged })
        assertTrue(matching.ofOld(old.named("d"))!!.orderChanged)
    }

    @Test
    fun `top-level declarations are ordered too`() {
        val old = tree { cls("A"); cls("B"); cls("C") }
        val new = tree { cls("B"); cls("C"); cls("A") }

        assertEquals(listOf("A"), match(old, new).matches.filter { it.moved }.map { it.old.name })
    }

    @Test
    fun `a swap keeps the earliest old-side declaration in place`() {
        val old = tree { cls("A") { method("first"); method("second") } }
        val new = tree { cls("A") { method("second"); method("first") } }

        val matching = match(old, new)

        assertFalse(matching.ofOld(old.named("first"))!!.moved)
        assertTrue(matching.ofOld(old.named("second"))!!.moved)
    }

    @Test
    fun `added and removed siblings do not move the others`() {
        val old = tree { cls("A") { method("a"); method("gone"); method("b") } }
        val new = tree { cls("A") { method("fresh"); method("a"); method("b") } }

        assertTrue(match(old, new).matches.none { it.moved })
    }

    @Test
    fun `a declaration that moved to another container is not part of the order of either`() {
        val old = tree { cls("A") { method("a"); method("b") }; cls("B") { method("c") } }
        val new = tree { cls("A") { method("b") }; cls("B") { method("a"); method("c") } }

        val matching = match(old, new)

        assertFalse(matching.ofOld(old.named("b"))!!.moved)
        assertFalse(matching.ofOld(old.named("c"))!!.moved)
        val a = matching.ofOld(old.named("a"))!!
        assertTrue(a.containerChanged)
        assertFalse(a.orderChanged)
    }

    @Test
    fun `matching is deterministic`() {
        val build = {
            tree { cls("A") { method("foo", "int"); method("foo", "String"); method("bar") }; cls("B") { method("baz") } }
        }
        val describe = { m: Matching ->
            listOf(m.matches.map { it.toString() }, m.removed.map { it.toString() }, m.added.map { it.toString() })
        }
        val old = build()
        val new = tree { cls("B") { method("bar") }; cls("A") { method("foo", "long"); method("foo", "String") } }

        assertEquals(describe(match(old, new)), describe(match(build(), new)))
    }

    @Test
    fun `every declaration ends up in exactly one list`() {
        val old = tree { cls("A") { method("foo", "int"); method("foo", "String"); method("bar"); field("x") }; cls("B") }
        val new = tree { cls("A") { method("foo", "long"); method("foo", "char"); method("foo", "byte") }; cls("C") { method("bar") } }

        val matching = match(old, new)

        val oldSeen = matching.matches.map { it.old } + matching.uncertainOld.map { it.declaration } + matching.removed
        val newSeen = matching.matches.map { it.new } + matching.uncertainNew.map { it.declaration } + matching.added
        assertEquals(old.all.toSet(), oldSeen.toSet())
        assertEquals(old.all.size, oldSeen.size)
        assertEquals(new.all.toSet(), newSeen.toSet())
        assertEquals(new.all.size, newSeen.size)
    }

    @Test
    fun `empty trees match nothing`() {
        assertNothingLeft(match(tree(), tree()))
        assertNull(match(tree(), tree()).ofOld(tree { cls("A") }.named("A")))
    }

    @Test(expected = CancellationException::class)
    fun `cancellation propagates`() {
        DeclarationMatcher.match(tree { cls("A") }, tree { cls("A") }) { throw CancellationException() }
    }

    @Test
    fun `the earliest longest increasing subsequence`() {
        assertEquals(emptySet<Int>(), DeclarationMatcher.earliestLongestIncreasing(emptyList()))
        assertEquals(setOf(0, 1, 2), DeclarationMatcher.earliestLongestIncreasing(listOf(0, 1, 2)))
        assertEquals(setOf(0), DeclarationMatcher.earliestLongestIncreasing(listOf(2, 1, 0)))
        assertEquals(setOf(1, 2, 3), DeclarationMatcher.earliestLongestIncreasing(listOf(3, 0, 1, 2)))
        assertEquals(setOf(0, 2), DeclarationMatcher.earliestLongestIncreasing(listOf(1, 0, 2)))
        assertEquals(setOf(0, 1, 4), DeclarationMatcher.earliestLongestIncreasing(listOf(1, 3, 0, 2, 4)))
    }

    private fun match(old: DeclarationTree, new: DeclarationTree) = DeclarationMatcher.match(old, new, Cancellation.NONE)

    @Test
    fun `M3 pairs what remains of a repeated name once the rest of the group is paired`() {
        val old = tree {
            cls("Adapter") {
                method("toJson", "Writer,T?", "abstract fun toJson(writer: Writer, value: T?)")
                method("toJson", "T?", "{ val buffer = StringBuilder(); toJson(Writer(buffer), value); return buffer.toString() }")
            }
        }
        val new = tree {
            cls("Adapter") {
                method("toJson", "Writer,T", "abstract fun toJson(writer: Writer, value: T)")
                method("toJson", "T", "{ val buffer = StringBuilder(); toJson(Writer(buffer), value); return buffer.toString() }")
            }
        }

        val matching = match(old, new)

        old.all.drop(1).zip(new.all.drop(1)).forEach { (o, n) ->
            assertSame(n, matching.ofOld(o)!!.new)
            assertEquals(MatchRule.SIGNATURE_CHANGED, matching.ofOld(o)!!.rule)
        }
        assertNothingLeft(matching)
    }

    private companion object {
        /** A body long enough to be compared by content. */
        const val BODY = "{ int x = compute(a, b); return x * 2; }"
    }

    private fun assertNothingLeft(matching: Matching) {
        assertTrue(matching.uncertainOld.isEmpty() && matching.uncertainNew.isEmpty())
        assertTrue(matching.removed.isEmpty() && matching.added.isEmpty())
    }
}

/** The only declaration named [name]. */
fun DeclarationTree.named(name: String): Declaration = all.single { it.name == name }
