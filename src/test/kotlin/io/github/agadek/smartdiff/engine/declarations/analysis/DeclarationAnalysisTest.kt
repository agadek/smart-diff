package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.FileRegion
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The result tree and statuses built from matching and classification (technical specification §5, §6.7, §7, §8). */
class DeclarationAnalysisTest {

    @Test
    fun `identical trees report nothing`() {
        val build = { tree(fileRegion = "package a;\n") { cls("A") { method("foo", "int", "{ return 1; }") } } }

        val result = compare(build(), build())

        assertEquals(Status.ANALYZED, result.status)
        assertNull(result.fileRegion)
        assertFalse(result.hasReportedChanges)
        assertEquals("CLASS A\n  METHOD foo(int)\n", outline(result))
    }

    @Test
    fun `the result carries the engine identity`() {
        val result = compare(tree(), tree())

        assertEquals(ENGINE, result.engineId)
        assertEquals("1", result.engineVersion)
    }

    @Test
    fun `a matched declaration reports its content and both ranges`() {
        val old = tree { cls("A") { method("foo", body = "{ return 1; }") } }
        val new = tree { cls("A") { method("foo", body = "{ return 2; }") } }

        val result = compare(old, new)

        val foo = result.changes.single().children.single()
        assertEquals(Presence.MATCHED, foo.presence)
        assertEquals(Content.CHANGED, foo.content)
        assertEquals(old.named("foo").range, foo.oldRange)
        assertEquals(new.named("foo").range, foo.newRange)
        assertEquals("CLASS A\n  METHOD foo() CHANGED\n", outline(result))
        assertFalse(result.changes.single().isChangedItself)
    }

    @Test
    fun `formatting-only and unchanged siblings`() {
        val old = tree { cls("A") { method("a", body = "{ x(); }"); method("b", body = "{ y(); }") } }
        val new = tree { cls("A") { method("a", body = "{\n  x();\n}"); method("b", body = "{ y(); }") } }

        assertEquals("CLASS A\n  METHOD a() FORMATTING_ONLY\n  METHOD b()\n", outline(compare(old, new)))
    }

    @Test
    fun `a signature change is reported with the new signature`() {
        val old = tree { cls("A") { method("foo", "int") } }
        val new = tree { cls("A") { method("foo", "long") } }

        val foo = compare(old, new).changes.single().children.single()

        assertTrue(foo.signatureChanged)
        assertEquals(Content.CHANGED, foo.content)
        assertEquals("long", (foo.subject as Subject.Declaration).signature)
    }

    @Test
    fun `a parse error makes a matched declaration unanalyzed and the result partial`() {
        val old = tree { cls("A") { method("foo", "int") } }
        val new = tree { cls("A") { method("foo", "long", body = "{ return", parseError = true) } }

        val result = compare(old, new)

        val foo = result.changes.single().children.single()
        assertEquals(Content.UNANALYZED, foo.content)
        assertTrue("signature change is still reported", foo.signatureChanged)
        assertEquals(Status.PARTIAL, result.status)
        assertNotNull(result.fallbackReason)
    }

    @Test
    fun `a parse error in a child does not affect its container`() {
        val old = tree { cls("A") { method("foo") } }
        val new = tree { cls("A") { method("foo", body = "{", parseError = true) } }

        assertEquals("CLASS A\n  METHOD foo() UNANALYZED\n", outline(compare(old, new)))
    }

    @Test
    fun `added and removed declarations have one range`() {
        val old = tree { cls("A") { method("gone") } }
        val new = tree { cls("A") { method("fresh") } }

        val result = compare(old, new)

        val (gone, fresh) = result.changes.single().children
        assertEquals(Presence.ADDED, fresh.presence)
        assertNull(fresh.oldRange)
        assertEquals(new.named("fresh").range, fresh.newRange)
        assertEquals(Presence.REMOVED, gone.presence)
        assertEquals(old.named("gone").range, gone.oldRange)
        assertNull(gone.newRange)
    }

    @Test
    fun `a removed declaration follows the counterpart of its preceding old sibling`() {
        val old = tree { cls("A") { method("a"); method("gone"); method("b"); method("c") } }
        val new = tree { cls("A") { method("a"); method("fresh"); method("b"); method("c") } }

        assertEquals(
            "CLASS A\n  METHOD a()\n  METHOD gone() REMOVED\n  METHOD fresh() ADDED\n  METHOD b()\n  METHOD c()\n",
            outline(compare(old, new)),
        )
    }

    @Test
    fun `a removed first declaration comes first`() {
        val old = tree { cls("A"); cls("B") }
        val new = tree { cls("B") }

        assertEquals("CLASS A REMOVED\nCLASS B\n", outline(compare(old, new)))
    }

    @Test
    fun `a removed container keeps its removed members`() {
        val old = tree { cls("A") { method("foo") }; cls("B") }
        val new = tree { cls("B") }

        assertEquals("CLASS A REMOVED\n  METHOD foo() REMOVED\nCLASS B\n", outline(compare(old, new)))
    }

    @Test
    fun `a renamed class too small to compare by content is removed and added, with its members moved into the new class`() {
        val old = tree { cls("Parser") { method("parse", "String"); method("dropped") } }
        val new = tree { cls("Reader") { method("parse", "String") } }

        assertEquals(
            "CLASS Parser REMOVED\n  METHOD dropped() REMOVED\nCLASS Reader ADDED\n  METHOD parse(String) moved\n",
            outline(compare(old, new)),
        )
    }

    @Test
    fun `a moved declaration appears under its new container only`() {
        val old = tree { cls("A") { method("foo") }; cls("B") }
        val new = tree { cls("A"); cls("B") { method("foo") } }

        assertEquals("CLASS A\nCLASS B\n  METHOD foo() moved\n", outline(compare(old, new)))
    }

    @Test
    fun `a reordered declaration is moved`() {
        val old = tree { cls("A") { method("a"); method("b") } }
        val new = tree { cls("A") { method("b"); method("a") } }

        assertEquals("CLASS A\n  METHOD b() moved\n  METHOD a()\n", outline(compare(old, new)))
    }

    @Test
    fun `uncertain declarations list their candidates on the other side`() {
        val old = tree { cls("A") { method("foo", "int"); method("foo", "String") } }
        val new = tree { cls("A") { method("foo", "long"); method("foo", "char"); method("foo", "byte") } }

        val result = compare(old, new)

        val members = result.changes.single().children
        assertEquals(5, members.size)
        assertTrue(members.all { it.presence == Presence.UNCERTAIN })
        val (newSide, oldSide) = members.partition { it.newRange != null }
        assertEquals(3, newSide.size)
        newSide.forEach { node ->
            assertNull(node.oldRange)
            assertEquals(old.all.filter { it.name == "foo" }.map { it.range }, node.candidates.map { it.range })
        }
        oldSide.forEach { node ->
            assertNull(node.newRange)
            assertEquals(listOf("long", "char", "byte"), node.candidates.map { (it.subject as Subject.Declaration).signature })
        }
    }

    @Test
    fun `an unchanged file region is null`() {
        assertNull(compare(tree(fileRegion = "import a;"), tree(fileRegion = "import a;")).fileRegion)
    }

    @Test
    fun `a changed file region spans its ranges on each side`() {
        val result = compare(tree(fileRegion = "import a;"), tree(fileRegion = "import b;\n"))

        val region = result.fileRegion!!
        assertEquals(Subject.Region(DeclarationAnalysis.FILE_REGION), region.subject)
        assertEquals(Content.CHANGED, region.content)
        assertEquals(TextRange(0, 9), region.oldRange)
        assertEquals(TextRange(0, 10), region.newRange)
        assertEquals(Status.ANALYZED, result.status)
    }

    @Test
    fun `a file region on one side only gets an empty range on the other`() {
        val region = compare(tree(), tree(fileRegion = "import a;")).fileRegion!!

        assertEquals(TextRange(0, 0), region.oldRange)
        assertEquals(Content.CHANGED, region.content)
    }

    @Test
    fun `a file region follows the whitespace policy`() {
        val kotlin = WhitespacePolicy.LINE_BREAKS_SIGNIFICANT
        assertEquals(
            Content.FORMATTING_ONLY,
            compare(tree(kotlin, "import a\n"), tree(kotlin, "import  a\n")).fileRegion!!.content,
        )
        assertEquals(Content.CHANGED, compare(tree(kotlin, "import a\n"), tree(kotlin, "import\na\n")).fileRegion!!.content)
    }

    @Test
    fun `a parse error in the file region makes it unanalyzed and the result partial`() {
        val old = tree(fileRegion = "import a;") { cls("A") }
        val new = tree(fileRegion = "import a", fileRegionParseError = true) { cls("A") }

        val result = compare(old, new)

        assertEquals(Content.UNANALYZED, result.fileRegion!!.content)
        assertEquals(Status.PARTIAL, result.status)
    }

    @Test
    fun `an unchanged file region with a parse error is unchanged`() {
        val old = tree(fileRegion = "import a", fileRegionParseError = true) { cls("A") }
        val new = tree(fileRegion = "import a", fileRegionParseError = true) { cls("A") }

        val result = compare(old, new)

        assertNull(result.fileRegion)
        assertEquals(Status.ANALYZED, result.status)
    }

    @Test
    fun `a side with a parse error and no declarations fails`() {
        val broken = tree(fileRegion = "class {", fileRegionParseError = true)

        val result = compare(tree { cls("A") }, broken)

        assertEquals(Status.FAILED, result.status)
        assertEquals("the new side could not be parsed", result.fallbackReason)
        assertTrue(result.changes.isEmpty())
        assertEquals(Status.FAILED, compare(broken, tree { cls("A") }).status)
    }

    @Test
    fun `a blank side with a parse error does not fail`() {
        val blank = tree(fileRegion = "\n\n", fileRegionParseError = true)

        assertEquals(Status.PARTIAL, compare(blank, tree { cls("A") }).status)
    }

    @Test
    fun `too many declarations on either side is too large`() {
        val small = tree { cls("A") }
        val large = tree { cls("A") { method("a"); method("b") } }

        assertEquals(Status.ANALYZED, DeclarationAnalysis(ENGINE, "1").compare(small, large, 3, Cancellation.NONE).status)
        val result = DeclarationAnalysis(ENGINE, "1").compare(small, large, 2, Cancellation.NONE)
        assertEquals(Status.TOO_LARGE, result.status)
        assertEquals("file has 3 declarations, the limit is 2", result.fallbackReason)
        assertEquals(Status.TOO_LARGE, DeclarationAnalysis(ENGINE, "1").compare(large, small, 2, Cancellation.NONE).status)
    }

    @Test
    fun `every declaration of both sides appears exactly once`() {
        val old = tree { cls("A") { method("foo", "int"); method("foo", "String"); method("bar"); cls("In") { field("x") } }; cls("B") }
        val new = tree { cls("C") { method("bar") }; cls("A") { method("foo", "long"); method("foo", "char"); method("foo", "byte") } }

        val result = compare(old, new)

        val nodes = result.changes.flatMap { it.flatten() }
        assertEquals(old.all.map { it.range }.sortedBy { it.start }, nodes.mapNotNull { it.oldRange }.sortedBy { it.start })
        assertEquals(new.all.map { it.range }.sortedBy { it.start }, nodes.mapNotNull { it.newRange }.sortedBy { it.start })
    }

    @Test
    fun `the result is deterministic`() {
        val build = { tree(fileRegion = "import a;") { cls("A") { method("foo", "int"); method("b") }; cls("B") } }
        val new = tree(fileRegion = "import b;") { cls("B") { method("b") }; cls("A") { method("foo", "long") } }

        assertEquals(compare(build(), new), compare(build(), new))
    }

    @Test
    fun `a change covers the leading whitespace of its declaration, an unchanged declaration does not`() {
        fun method(name: String, start: Int, body: String = "{}") = Declaration(
            DeclarationKind.METHOD, name, "", TextRange(start, start + 10), ownTokens = tokens("\n\n    void $name() $body"),
            leadingWhitespace = TextRange(start - 6, start),
        )
        val old = DeclarationTree(listOf(method("kept", 6), method("edited", 22)), FileRegion(emptyList(), emptyList()), WhitespacePolicy.INSIGNIFICANT)
        val new = DeclarationTree(
            listOf(method("kept", 6), method("edited", 22, "{ x(); }"), method("added", 38)),
            FileRegion(emptyList(), emptyList()), WhitespacePolicy.INSIGNIFICANT,
        )

        val (kept, edited, added) = compare(old, new).changes

        assertEquals(emptyList<RangePair>(), kept.details)
        assertEquals(listOf(RangePair(TextRange(16, 22), TextRange(16, 22))), edited.details)
        assertEquals(listOf(RangePair(null, TextRange(32, 38))), added.details)
    }

    @Test
    fun `a rename candidate is one matched node, unchanged when only the name differs`() {
        val body = "{ int x = compute(a, b); return x * 2; }"
        val old = tree { cls("A") { method("total", "int", "int total(int a) $body"); method("other", "", "void other() { a(); b(); c(); }") } }
        val new = tree { cls("A") { method("sum", "int", "int sum(int a) $body"); method("other", "", "void other() { a(); b(); c(); }") } }

        val sum = compare(old, new).changes.single().children.first()

        assertEquals(Presence.MATCHED, sum.presence)
        assertEquals(Subject.Declaration(DeclarationKind.METHOD, "total", "int"), sum.renamedFrom)
        assertEquals(Content.UNCHANGED, sum.content)
        assertTrue(sum.isReported)
        assertEquals("CLASS A\n  METHOD sum(int) renamed from total\n  METHOD other()\n", outline(compare(old, new)))
    }

    @Test
    fun `a rename candidate with a changed body is changed too`() {
        val old = tree { method("total", "int", "int total(int a) { int x = compute(a, b); return x * 2; }") }
        val new = tree { method("sum", "int", "int sum(int a) { int x = compute(a, b); return x * 3; }") }

        assertEquals("METHOD sum(int) renamed from total CHANGED\n", outline(compare(old, new)))
    }

    @Test
    fun `a renamed class keeps its members and its constructor follows it`() {
        val old = tree {
            cls("Parser", "class Parser { private final String text; }") {
                declaration(DeclarationKind.CONSTRUCTOR, "Parser", "String", "Parser(String text) { this.text = text; }")
                method("parse", "", "{ return text.trim(); }")
            }
        }
        val new = tree {
            cls("Reader", "class Reader { private final String text; }") {
                declaration(DeclarationKind.CONSTRUCTOR, "Reader", "String", "Reader(String text) { this.text = text; }")
                method("parse", "", "{ return text.trim(); }")
            }
        }

        assertEquals("CLASS Reader renamed from Parser\n  CONSTRUCTOR Reader(String)\n  METHOD parse()\n", outline(compare(old, new)))
        assertFalse(compare(old, new).changes.single().children.any { it.isReported })
    }

    @Test
    fun `labels use the display signature and receiver`() {
        val old = tree()
        val new = DeclarationTree(
            listOf(Declaration(DeclarationKind.FUNCTION, "formatIsoDate", "Date.", TextRange(0, 10), displaySignature = "", receiver = "Date")),
            FileRegion(emptyList(), emptyList()), WhitespacePolicy.LINE_BREAKS_SIGNIFICANT,
        )

        assertEquals(Subject.Declaration(DeclarationKind.FUNCTION, "formatIsoDate", "", "Date"), compare(old, new).changes.single().subject)
    }

    private fun compare(old: DeclarationTree, new: DeclarationTree): ComparisonResult =
        DeclarationAnalysis(ENGINE, "1").compare(old, new, maxDeclarations = 5_000, cancellation = Cancellation.NONE)

    private fun ChangeNode.flatten(): List<ChangeNode> = listOf(this) + children.flatMap { it.flatten() }

    /** One line per node: kind, name, signature, then presence (unless matched), moved and content (unless unchanged). */
    private fun outline(result: ComparisonResult): String = buildString {
        fun render(node: ChangeNode, depth: Int) {
            val subject = node.subject as Subject.Declaration
            append("  ".repeat(depth)).append(subject.kind).append(' ').append(subject.name)
            if (subject.signature != null) append('(').append(subject.signature).append(')')
            if (node.presence != Presence.MATCHED) append(' ').append(node.presence)
            (node.renamedFrom as Subject.Declaration?)?.let { append(" renamed from ").append(it.name) }
            if (node.moved) append(" moved")
            if (node.content != Content.UNCHANGED) append(' ').append(node.content)
            append('\n')
            node.children.forEach { render(it, depth + 1) }
        }
        result.changes.forEach { render(it, 0) }
    }

    private companion object {
        val ENGINE = EngineId("smartdiff.test.declarations")
    }
}
