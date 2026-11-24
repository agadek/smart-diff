package io.github.agadek.smartdiff.engine.declarations.psi.go

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationProvider
import io.github.agadek.smartdiff.merge.MergeComparison
import io.github.agadek.smartdiff.merge.MergeSide
import io.github.agadek.smartdiff.merge.MergeTexts
import io.github.agadek.smartdiff.ui.OutlineModel
import io.github.agadek.smartdiff.ui.merge.MergeOutlineLayout
import io.github.agadek.smartdiff.ui.merge.MergeOutlineModel
import io.github.agadek.smartdiff.ui.merge.MergeSideName
import java.util.concurrent.Executors

class GoDeclarationProviderTest : BasePlatformTestCase() {

    private fun provider(): DeclarationProvider =
        DeclarationProvider.EP_NAME.extensionList.single { it.supports("go") }

    private fun compare(old: String, new: String): ComparisonResult = ReadAction.compute(ThrowableComputable {
        val engine = DeclarationEngine(ProjectManager.getInstance().defaultProject)
        engine.compare(ComparisonRequest(oldText = old, newText = new, languageId = "go", fileName = "file.go"), Cancellation.NONE)
    })

    private fun tree(text: String): DeclarationTree = ReadAction.compute(ThrowableComputable {
        val project = ProjectManager.getInstance().defaultProject
        val provider = provider()
        provider.build(provider.parse(project, text, "file.go", null), Cancellation.NONE)
    })

    private fun all(tree: DeclarationTree): List<Declaration> = tree.all

    private fun changes(result: ComparisonResult): List<ChangeNode> = buildList {
        fun visit(node: ChangeNode) {
            add(node)
            node.children.forEach(::visit)
        }
        result.changes.forEach(::visit)
    }

    private fun name(node: ChangeNode): String = (node.subject as Subject.Declaration).name

    /** Every declaration's range, own tokens and file region tokens are slices of [text] (details §9). */
    private fun assertOffsets(text: String, tree: DeclarationTree = tree(text)) {
        for (decl in all(tree)) {
            assertTrue("range of ${decl.name} inside the text", decl.range.start >= 0 && decl.range.end <= text.length && decl.range.start < decl.range.end)
            assertEquals("tokens and starts of ${decl.name}", decl.ownTokens.size, decl.ownTokenStarts.size)
            decl.ownTokens.zip(decl.ownTokenStarts).forEach { (token, start) ->
                assertEquals("token at $start in ${decl.name}", text.substring(start, start + token.text.length), token.text)
            }
            for (child in decl.children) {
                assertTrue("${child.name} inside ${decl.name}", child.range.start >= decl.range.start && child.range.end <= decl.range.end)
            }
        }
        tree.fileRegion.tokens.zip(tree.fileRegion.tokenStarts).forEach { (token, start) ->
            assertEquals("file region token at $start", text.substring(start, start + token.text.length), token.text)
        }
    }

    /** [assertOffsets] on [text] and on the same text with CRLF line ends (details §9). */
    private fun assertOffsetsLfAndCrlf(text: String) {
        assertOffsets(text)
        val crlf = text.replace("\n", "\r\n")
        assertOffsets(crlf)
        val lf = tree(text)
        val cr = tree(crlf)
        assertEquals("same declarations on LF and CRLF", lf.all.map { it.kind to it.name }, cr.all.map { it.kind to it.name })
    }

    private fun slice(text: String, decl: Declaration): String = text.substring(decl.range.start, decl.range.end)

    fun `test R1 registers the Go declaration provider when the plugin is present`() {
        // languages/17-go R1
        assertNotNull("the Go plugin is loaded", PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.plugins.go")))
        val providers = DeclarationProvider.EP_NAME.extensionList.filter { it.supports("go") }
        assertEquals("one Go provider, from smartdiff-go.xml", 1, providers.size)
        assertTrue(providers.single() is GoDeclarationProvider)
        assertEquals(io.github.agadek.smartdiff.engine.api.GuardedFeature.GO_PROVIDER, providers.single().guardedFeature)
    }

    fun `test R2 supports go files when the plugin is present`() {
        // languages/17-go R2
        assertTrue(provider().supports("go"))
        assertFalse(provider().supports("Go"))
        val result = compare("package p\n\nfunc a() {}\n", "package p\n\nfunc a() { println() }\n")
        assertEquals(Status.ANALYZED, result.status)
        assertEquals(Content.CHANGED, result.changes.single().content)
        assertOffsetsLfAndCrlf("package p\n\nfunc a() {}\n")
    }

    fun `test R3 top-level declarations and kinds`() {
        // languages/17-go R3
        val text = """
            package p

            import "fmt"

            func Run() {}

            func (s *Server) Start() {}

            type Server struct{}

            const Max = 10

            var count int

            var _ fmt.Stringer = (*Server)(nil)
        """.trimIndent() + "\n"
        val tree = tree(text)
        assertEquals(
            listOf(
                DeclarationKind.FUNCTION to "Run",
                DeclarationKind.METHOD to "Start",
                DeclarationKind.STRUCT to "Server",
                DeclarationKind.CONSTANT to "Max",
                DeclarationKind.VARIABLE to "count",
            ),
            tree.topLevel.map { it.kind to it.name },
        )
        assertEquals("const Max = 10", slice(text, tree.topLevel.single { it.name == "Max" }))
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R4 a method is shown with its receiver type without star and type parameters`() {
        // languages/17-go R4
        val text = """
            package p

            func (p *Point) Move(dx, dy int) {}

            func (l List[T]) Len() int { return 0 }

            func (m *Map[K, V]) Put(k K, v V) {}
        """.trimIndent() + "\n"
        val tree = tree(text)
        val methods = tree.topLevel
        assertEquals(listOf("Point", "List", "Map"), methods.map { it.receiver })
        assertEquals(listOf("Move", "Len", "Put"), methods.map { it.name })
        assertTrue(methods.all { it.kind == DeclarationKind.METHOD })
        val result = compare(text, text.replace("dx, dy int) {}", "dx, dy int) { p.X += dx }"))
        val move = result.changes.single { it.content == Content.CHANGED }
        assertEquals("method Point.Move(int, int)", OutlineModel.title(move.subject as Subject.Declaration))
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R5 a type's kind follows how it is written`() {
        // languages/17-go R5
        val text = """
            package p

            type S struct{ X int }

            type I interface{ M() }

            type A = string

            type ID int64

            type Handler func(int) error

            type Ptr *S
        """.trimIndent() + "\n"
        val tree = tree(text)
        assertEquals(
            listOf(
                DeclarationKind.STRUCT to "S",
                DeclarationKind.INTERFACE to "I",
                DeclarationKind.TYPE_ALIAS to "A",
                DeclarationKind.TYPE to "ID",
                DeclarationKind.TYPE to "Handler",
                DeclarationKind.TYPE to "Ptr",
            ),
            tree.topLevel.map { it.kind to it.name },
        )
        assertEquals("type", DeclarationKind.TYPE.label())
        assertTrue("a pointer type has no members", tree.topLevel.last().children.isEmpty())
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R6 struct fields embedded fields included and interface methods are members`() {
        // languages/17-go R6
        val text = """
            package p

            import "fmt"

            type Point struct {
            	X, Y int
            	Name string `json:"name"`
            	fmt.Stringer
            	*Base
            }

            type Shape interface {
            	Area() float64
            	Scale(f float64, origin Point) Shape
            	fmt.Stringer
            }
        """.trimIndent() + "\n"
        val tree = tree(text)
        val point = tree.topLevel.single { it.name == "Point" }
        assertEquals(listOf("X, Y", "Name", "Stringer", "Base"), point.children.map { it.name })
        assertTrue(point.children.all { it.kind == DeclarationKind.FIELD })
        assertEquals("Name string `json:\"name\"`", slice(text, point.children[1]))
        val shape = tree.topLevel.single { it.name == "Shape" }
        assertEquals(listOf("Area", "Scale"), shape.children.map { it.name })
        assertTrue(shape.children.all { it.kind == DeclarationKind.METHOD })
        assertEquals(listOf("", "float64,Point"), shape.children.map { it.signature })
        assertEquals("float64, Point", shape.children[1].displaySignature)

        val embedded = compare(text, text.replace("\tfmt.Stringer\n}\n", "\tfmt.Stringer\n\tio.Reader\n}\n"))
        assertTrue("adding an embedded interface changes the interface", embedded.changes.single { it.content == Content.CHANGED }.let { name(it) == "Shape" })
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R7 each spec of a grouped declaration is its own declaration`() {
        // languages/17-go R7
        val text = """
            package p

            type (
            	ID    int64
            	Alias = string
            )

            const (
            	A = iota
            	B
            )

            var (
            	z int
            )
        """.trimIndent() + "\n"
        val tree = tree(text)
        assertEquals(listOf("ID", "Alias", "A", "B", "z"), tree.topLevel.map { it.name })
        assertEquals("ID    int64", slice(text, tree.topLevel[0]))
        assertEquals("B", slice(text, tree.topLevel[3]))
        val result = compare(text, text.replace("\tB\n", "\tB\n\tC\n"))
        val added = changes(result).single { it.presence == Presence.ADDED }
        assertEquals("C", name(added))
        assertEquals(DeclarationKind.CONSTANT, (added.subject as Subject.Declaration).kind)
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R8 names declared together are one declaration`() {
        // languages/17-go R8
        val text = """
            package p

            type Point struct {
            	X, Y int
            }

            const a, b = 1, 2

            var x, y = f()
        """.trimIndent() + "\n"
        val tree = tree(text)
        assertEquals(listOf("X, Y"), tree.topLevel[0].children.map { it.name })
        assertEquals(listOf("Point", "a, b", "x, y"), tree.topLevel.map { it.name })
        val result = compare(text, text.replace("X, Y int", "X, Z int"))
        assertEquals(
            "renaming one name gives another field",
            listOf("X, Y" to Presence.REMOVED, "X, Z" to Presence.ADDED),
            changes(result).filter { it.presence != Presence.MATCHED }.map { name(it) to it.presence }.sortedBy { it.first },
        )
        val constant = compare(text, text.replace("const a, b = 1, 2", "const a, c = 1, 2"))
        val renamed = changes(constant).single { name(it) == "a, c" }
        assertEquals("a constant with a value is a rename candidate", "a, b", (renamed.renamedFrom as Subject.Declaration).name)
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R9 bodies function literals package imports and blank names are not declarations`() {
        // languages/17-go R9
        val text = """
            package p

            import (
            	"fmt"
            	"io"
            )

            var _ io.Reader = (*T)(nil)

            var a, _ = f()

            func _() {}

            func main() {
            	type local int
            	const c = 1
            	var v local
            	f := func() {}
            	_ = f
            	fmt.Println(c, v)
            }
        """.trimIndent() + "\n"
        val tree = tree(text)
        assertEquals(listOf("a, _", "main"), tree.topLevel.map { it.name })
        assertTrue("nothing inside main", tree.topLevel.last().children.isEmpty())
        val result = compare(text, text.replace("type local int", "type local string"))
        assertEquals("main", name(result.changes.single { it.content == Content.CHANGED }))
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R10 a declaration's range includes the comment lines directly above it`() {
        // languages/17-go R10
        val text = """
            //go:build linux

            // Package p does things.
            package p

            // Detached comment.

            // Point is a point.
            // It has two coordinates.
            type Point struct {
            	// X is across.
            	X int // pixels
            	/* Y is down. */
            	Y int
            }

            const (
            	// A is first.
            	A = 1
            )

            func a() {} // trailing
            func b() {}
        """.trimIndent() + "\n"
        val tree = tree(text)
        val point = tree.topLevel.single { it.name == "Point" }
        assertTrue(slice(text, point).startsWith("// Point is a point.\n// It has two coordinates.\ntype Point struct {"))
        assertEquals("// X is across.\n\tX int // pixels", slice(text, point.children[0]))
        assertEquals("/* Y is down. */\n\tY int", slice(text, point.children[1]))
        assertEquals("// A is first.\n\tA = 1", slice(text, tree.topLevel.single { it.name == "A" }))
        assertEquals("func b() {}", slice(text, tree.topLevel.single { it.name == "b" }))
        assertEquals("func a() {}", slice(text, tree.topLevel.single { it.name == "a" }))

        val documented = compare(text, text.replace("func b() {}", "// b does nothing.\nfunc b() {}"))
        val b = documented.changes.single { it.content != Content.UNCHANGED }
        assertEquals("adding a doc comment changes the declaration", "b", name(b))
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R11 signatures are parameter types without names type parameters and results`() {
        // languages/17-go R11
        val text = """
            package p

            func Move(dx, dy int) {}

            func Sum[T int | float64](xs ...T) (total T) { return }

            func Apply(f func(int) error, opts ...string) error { return nil }

            func Lookup(m map[string][]*Item, int) {}

            func None() {}

            func (p *Point) Move(dx, dy int) {}

            func (l List[T]) Len() int { return 0 }
        """.trimIndent() + "\n"
        val tree = tree(text)
        assertEquals(
            listOf("int,int", "...T", "func(int)error,...string", "map[string][]*Item,int", "", "Point.int,int", "List."),
            tree.topLevel.map { it.signature },
        )
        assertEquals(
            listOf("int, int", "...T", "func(int) error, ...string", "map[string][]*Item, int", "", "int, int", ""),
            tree.topLevel.map { it.displaySignature },
        )
        val types = compare(text, text.replace("func Sum[T int | float64]", "func Sum[T ~int]"))
        val sum = types.changes.single { it.content == Content.CHANGED }
        assertEquals("Sum", name(sum))
        assertFalse("type parameters are not the signature", sum.signatureChanged)
        val pointer = compare(text, text.replace("(l List[T]) Len", "(l *List[T]) Len"))
        val len = pointer.changes.single { it.content == Content.CHANGED }
        assertEquals("Len", name(len))
        assertEquals("a pointer receiver keeps the method paired", Presence.MATCHED, len.presence)
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R12 two init functions in one file are separate declarations`() {
        // languages/17-go R12
        val text = "package p\n\nfunc init() { a() }\n\nfunc init() { b() }\n"
        val tree = tree(text)
        assertEquals(listOf("init", "init"), tree.topLevel.map { it.name })
        val result = compare(text, text.replace("b()", "c()"))
        assertEquals(Status.ANALYZED, result.status)
        assertEquals(1, result.changes.count { it.content == Content.CHANGED })
        assertOffsetsLfAndCrlf(text)
    }

    fun `test R13 spaces and indentation are formatting-only`() {
        // languages/17-go R13
        val old = "package p\n\ntype T struct {\n\tA int\n\tLonger string\n}\n\nfunc f(a int,b int) { g( a ) }\n"
        val new = "package p\n\ntype T struct {\n\tA      int\n\tLonger string\n}\n\nfunc f(a int, b int) {   g(a) }\n"
        val result = compare(old, new)
        assertEquals(Status.ANALYZED, result.status)
        assertTrue(changes(result).filter { it.content != Content.UNCHANGED }.all { it.content == Content.FORMATTING_ONLY })
        assertTrue(changes(result).any { it.content == Content.FORMATTING_ONLY })
        assertEquals(WhitespacePolicy.LINE_BREAKS_SIGNIFICANT, tree(old).whitespacePolicy)
    }

    fun `test R14 an added or removed line break is a change`() {
        // languages/17-go R14
        val old = "package p\n\nfunc f() { return }\n"
        val new = "package p\n\nfunc f() {\n\treturn\n}\n"
        assertEquals(Content.CHANGED, compare(old, new).changes.single().content)
        val joined = compare("package p\n\nfunc g() {\n\ta()\n\tb()\n}\n", "package p\n\nfunc g() {\n\ta(); b()\n}\n")
        assertEquals(Content.CHANGED, joined.changes.single().content)
    }

    fun `test R15 extraction inlining renames kind changes and moves inside declarations work for Go`() {
        // languages/17-go R15
        val before = """
            package p

            func Render(items []int) int {
            	sum := 0
            	for _, i := range items {
            		sum += i
            	}
            	return sum
            }
        """.trimIndent() + "\n"
        val after = """
            package p

            func Render(items []int) int {
            	return sumItems(items)
            }

            func sumItems(items []int) int {
            	sum := 0
            	for _, i := range items {
            		sum += i
            	}
            	return sum
            }
        """.trimIndent() + "\n"
        assertTrue("extraction", compare(before, after).relationships.any { RelationshipKind.EXTRACT in it.kinds })
        assertTrue("inlining", compare(after, before).relationships.any { RelationshipKind.INLINE in it.kinds })
        val renamed = compare(before, before.replace("Render", "Total"))
        assertTrue("rename", renamed.relationships.any { RelationshipKind.RENAME in it.kinds })
        val kind = compare("package p\n\ntype ID int64\n", "package p\n\ntype ID struct {\n\tvalue int64\n}\n")
        assertTrue("defined type to struct is a kind change", kind.relationships.any { RelationshipKind.KIND_CHANGE in it.kinds })
        assertEquals(DeclarationKind.TYPE, kind.changes.single().kindChangedFrom)
        val moved = compare(
            "package p\n\nfunc f(o Order) {\n\tif o.valid {\n\t\ttotal := computeTotal(o.items, o.discount, o.tax)\n\t\tship(o, total)\n\t}\n\tlabel := describeOrder(o.customer, o.address, o.notes)\n\tprint(label)\n}\n",
            "package p\n\nfunc f(o Order) {\n\tlabel := describeOrder(o.customer, o.address, o.notes)\n\tif o.valid {\n\t\ttotal := computeTotal(o.items, o.discount, o.tax)\n\t\tship(o, total)\n\t}\n\tprint(label)\n}\n",
        )
        assertEquals(Status.ANALYZED, moved.status)
        assertTrue("a statement moved before the if block: ${moved.changes}", moved.changes.single().codeMoves.isNotEmpty())
        val statements = ReadAction.compute(ThrowableComputable { tree("package p\n\nfunc f() {\n\ta := 1\n\tif a > 0 {\n\t\tc()\n\t}\n}\n") })
            .topLevel.single().statements
        assertEquals("statements of the body and of the nested block", 3, statements.size)
    }

    fun `test R16 merge outline and changes tree show Go through the engine`() {
        // languages/17-go R16
        val base = "package p\n\nfunc f() int { return 1 }\n"
        val left = "package p\n\nfunc f() int { return 2 }\n"
        val right = "package p\n\nfunc f() int { return 3 }\n"
        val executor = Executors.newSingleThreadExecutor()
        try {
            val engine = DeclarationEngine(ProjectManager.getInstance().defaultProject)
            val joined = ReadAction.compute(ThrowableComputable {
                MergeComparison.compare(engine, MergeTexts(base, left, right, "go", "file.go"), Cancellation.NONE, executor)
            })
            assertTrue(joined.isAnalyzed(MergeSide.LEFT))
            assertTrue(joined.isAnalyzed(MergeSide.RIGHT))
            val model = MergeOutlineModel.build(
                joined, base, left, right,
                MergeSideName("main", "main"), MergeSideName("feature", "feature"),
                MergeOutlineLayout.GROUPED_BY_SIDE,
            )
            assertEquals("1 changed on both sides", model.summary)
            val summary = OutlineModel.summary(compare(base, left), oldText = base, newText = left)
            assertTrue("Changes tree summary: $summary", summary.contains("changed"))
        } finally {
            executor.shutdownNow()
        }
    }
}
