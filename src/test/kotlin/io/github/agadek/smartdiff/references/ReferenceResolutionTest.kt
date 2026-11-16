package io.github.agadek.smartdiff.references

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.api.compareSafely
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine

/**
 * A generous test-only pass budget: these tests check resolution (relationships/04 R5, R10, R14, R19), not time. On a
 * cold JVM the first Kotlin K2 resolve pays a one-time warm-up that can exceed the production 1 s budget on a slow
 * runner. R17's budget is checked separately by ReferencePassTest's deterministic slow-resolver test.
 */
private const val TEST_BUDGET_MILLIS = 60_000L

/**
 * The Java, Kotlin K2, Scala 2 and Scala 3 resolvers resolve same-file usages from a light copy
 * (relationships/04 R5, R14). A language that cannot resolve keeps the engine result.
 */
class ReferenceResolutionTest : BasePlatformTestCase() {

    fun `test R5 R14 Java resolves a same-file method usage`() {
        val old = "class Order {\n    int total() { return 2; }\n    int twice() { return total() * 2; }\n}\n"
        val new = "class Order {\n    int sum() { return 2; }\n    int twice() { return sum() * 2; }\n}\n"
        val reference = usages("JAVA", "Order.java", old, new).single()

        assertEquals("total", old.substring(reference.oldRange.start, reference.oldRange.end))
        assertEquals("sum", new.substring(reference.newRange.start, reference.newRange.end))
    }

    fun `test R5 R14 Java maps a constructor reference to its renamed class`() {
        val old = "class Order {\n    Order() {}\n    static Order create() { return new Order(); }\n}\n"
        val new = "class Cart {\n    Cart() {}\n    static Cart create() { return new Cart(); }\n}\n"
        val references = usages("JAVA", "Order.java", old, new)

        val constructor = references.filter { old.substring(it.oldRange.start, it.oldRange.end) == "Order" }
        assertFalse("the constructor reference is grouped as a usage of the class: $references", constructor.isEmpty())
        assertTrue(constructor.all { it.symbol.name == "Cart" })
    }

    fun `test R5 R14 Kotlin K2 resolves a same-file function usage`() {
        val old = "class Order {\n    fun total(): Int { val a = 1; return a + 1 }\n    fun twice(): Int = total() * 2\n}\n"
        val new = "class Order {\n    fun sum(): Int { val a = 1; return a + 1 }\n    fun twice(): Int = sum() * 2\n}\n"
        val reference = usages("kotlin", "Order.kt", old, new).single()

        assertEquals("total", old.substring(reference.oldRange.start, reference.oldRange.end))
        assertEquals("sum", new.substring(reference.newRange.start, reference.newRange.end))
    }

    fun `test R5 R14 Kotlin K2 maps a constructor reference to its renamed class`() {
        val old = "class Order {\n    fun make(): Order = Order()\n}\n"
        val new = "class Cart {\n    fun make(): Cart = Cart()\n}\n"
        val references = usages("kotlin", "Order.kt", old, new)

        assertEquals("the return type and the constructor call are usages", 2, references.size)
        assertTrue(references.all { old.substring(it.oldRange.start, it.oldRange.end) == "Order" })
    }

    fun `test R5 R14 Scala 2 resolves a same-file function usage`() {
        val old = "class Order {\n  def total(): Int = { val a = 1; a + 1 }\n  def twice(): Int = total() * 2\n}\n"
        val new = "class Order {\n  def sum(): Int = { val a = 1; a + 1 }\n  def twice(): Int = sum() * 2\n}\n"
        val reference = usages("Scala", "Order.scala", old, new).single()

        assertEquals("total", old.substring(reference.oldRange.start, reference.oldRange.end))
        assertEquals("sum", new.substring(reference.newRange.start, reference.newRange.end))
    }

    fun `test R5 R14 Scala 2 maps a constructor reference to its renamed class`() {
        val old = "class Order {\n  def make(): Order = new Order\n}\n"
        val new = "class Cart {\n  def make(): Cart = new Cart\n}\n"
        val references = usages("Scala", "Order.scala", old, new)

        assertEquals("the return type and the constructor call are usages", 2, references.size)
        assertTrue(references.all { old.substring(it.oldRange.start, it.oldRange.end) == "Order" })
    }

    fun `test R5 R14 Scala 3 resolves a same-file function usage`() {
        val old = "class Order:\n  def total(): Int =\n    val a = 1\n    a + 1\n  def twice(): Int = total() * 2\n"
        val new = "class Order:\n  def sum(): Int =\n    val a = 1\n    a + 1\n  def twice(): Int = sum() * 2\n"
        val reference = usages("Scala", "Order.scala", old, new).single()

        assertEquals("total", old.substring(reference.oldRange.start, reference.oldRange.end))
        assertEquals("sum", new.substring(reference.newRange.start, reference.newRange.end))
    }

    fun `test R5 R14 Scala 3 maps a constructor reference to its renamed class`() {
        val old = "class Order:\n  def make(): Order = new Order\n"
        val new = "class Cart:\n  def make(): Cart = new Cart\n"
        val references = usages("Scala", "Order.scala", old, new)

        assertEquals("the return type and the constructor call are usages", 2, references.size)
        assertTrue(references.all { old.substring(it.oldRange.start, it.oldRange.end) == "Order" })
    }

    // languages/04-python R19
    fun `test R19 Python resolves a renamed function usage`() {
        val old = "def total(x):\n    return x + 1\n\ndef use(x):\n    return total(x)\n"
        val new = "def sum(x):\n    return x + 1\n\ndef use(x):\n    return sum(x)\n"
        val references = usages("Python", "calc.py", old, new)
        assertEquals("total", old.substring(references.single().oldRange.start, references.single().oldRange.end))
        assertEquals("sum", new.substring(references.single().newRange.start, references.single().newRange.end))
    }

    fun `test R19 Python resolves a renamed class usage`() {
        // languages/04-python R19
        val old = "class Order:\n    def value(self):\n        return 1\n\no = Order()\n"
        val new = "class Cart:\n    def value(self):\n        return 1\n\no = Cart()\n"
        val references = usages("Python", "m.py", old, new)
        assertEquals("Order", old.substring(references.single().oldRange.start, references.single().oldRange.end))
        assertEquals("Cart", new.substring(references.single().newRange.start, references.single().newRange.end))
    }

    fun `test R19 Python resolves a renamed variable usage`() {
        // languages/04-python R19
        val old = """
            CONFIG = {
                "a": 1,
                "b": 2,
                "c": 3,
                "d": 4,
            }
            def first():
                return CONFIG["a"]
            def second():
                return CONFIG["b"]
        """.trimIndent() + "\n"
        val new = """
            SETTINGS = {
                "a": 1,
                "b": 2,
                "c": 3,
                "d": 4,
            }
            def first():
                return SETTINGS["a"]
            def second():
                return SETTINGS["b"]
        """.trimIndent() + "\n"
        val references = usages("Python", "m.py", old, new)
        assertEquals(2, references.size)
        assertEquals("CONFIG", old.substring(references[0].oldRange.start, references[0].oldRange.end))
        assertEquals("SETTINGS", new.substring(references[0].newRange.start, references[0].newRange.end))
        assertEquals("CONFIG", old.substring(references[1].oldRange.start, references[1].oldRange.end))
        assertEquals("SETTINGS", new.substring(references[1].newRange.start, references[1].newRange.end))
    }

    fun `test R19 Python resolves a self method call`() {
        // languages/04-python R19
        val old = "class C:\n    def helper(self):\n        return 1 + 2\n    def use(self):\n        return self.helper()\n"
        val new = "class C:\n    def helper2(self):\n        return 1 + 2\n    def use(self):\n        return self.helper2()\n"
        val references = usages("Python", "m.py", old, new)
        assertEquals("helper", old.substring(references.single().oldRange.start, references.single().oldRange.end))
        assertEquals("helper2", new.substring(references.single().newRange.start, references.single().newRange.end))
    }

    fun `test R19 Python excludes obj helper and unresolved names`() {
        // languages/04-python R19
        val old = "class C:\n    def helper(self):\n        return 1 + 2\n\no = C()\nprint(o.helper())\nprint(missing())\n"
        val new = "class C:\n    def helper2(self):\n        return 1 + 2\n\no = C()\nprint(o.helper2())\nprint(missing())\n"
        val request = ComparisonRequest(old, new, "Python", "m.py")
        val result = compareSafely(DeclarationEngine(project), request, Cancellation.NONE)
        val outcome = ReferencePass.runWith(project, request, result, Cancellation.NONE, ReferenceResolvers.all, TEST_BUDGET_MILLIS)
        // rename should be detected but obj.helper/missing() not grouped
        val renameRels = outcome.result.relationships.filter { RelationshipKind.RENAME in it.kinds }
        assertTrue("rename helper->helper2 should be detected", renameRels.isNotEmpty())
        val references = renameRels.flatMap { it.evidence.filterIsInstance<Evidence.Reference>() }
        assertTrue("no references grouped for qualified/unresolved calls", references.isEmpty())
    }

    fun `test R19 Python groups only the renamed method when same name exists`() {
        // languages/04-python R19
        val old = "def helper():\n    return 1 + 2\n\nclass C:\n    def helper(self):\n        return 3\n    def use(self):\n        return self.helper()\n"
        val new = "def helper():\n    return 1 + 2\n\nclass C:\n    def helper2(self):\n        return 3\n    def use(self):\n        return self.helper2()\n"
        val references = usages("Python", "m.py", old, new)
        assertEquals(1, references.size)
        assertEquals("helper", old.substring(references.single().oldRange.start, references.single().oldRange.end))
        assertEquals("helper2", new.substring(references.single().newRange.start, references.single().newRange.end))
    }

    fun `test R19 Python does not group self access to renamed class variable`() {
        // languages/04-python R19
        val old = """
            class C:
                x = {
                    "a": 1,
                    "b": 2,
                    "c": 3,
                    "d": 4,
                }
                def f(self):
                    return self.x["a"]
        """.trimIndent() + "\n"
        val new = """
            class C:
                y = {
                    "a": 1,
                    "b": 2,
                    "c": 3,
                    "d": 4,
                }
                def f(self):
                    return self.y["a"]
        """.trimIndent() + "\n"
        val request = ComparisonRequest(old, new, "Python", "m.py")
        val result = compareSafely(DeclarationEngine(project), request, Cancellation.NONE)
        val outcome = ReferencePass.runWith(project, request, result, Cancellation.NONE, ReferenceResolvers.all, TEST_BUDGET_MILLIS)
        val renameRels = outcome.result.relationships.filter { RelationshipKind.RENAME in it.kinds }
        val xToY = renameRels.filter { rel ->
            val oldSub = rel.old.singleOrNull()?.subject as? Subject.Declaration
            val newSub = rel.new.singleOrNull()?.subject as? Subject.Declaration
            oldSub?.name == "x" && newSub?.name == "y"
        }
        assertTrue("expected a HIGH-confidence RENAME x -> y", xToY.isNotEmpty() && xToY.any { it.confidence == Confidence.HIGH })
        val refs = outcome.result.relationships
            .flatMap { it.evidence.filterIsInstance<Evidence.Reference>() }
        assertTrue("self.x/self.y should not be grouped as variable usages", refs.isEmpty())
    }

    fun `test R14 a language without a resolver keeps the engine result`() {
        val old = "class Order { int total() { return 2; } }\n"
        val new = "class Order { int sum() { return 2; } }\n"
        val request = ComparisonRequest(old, new, "UNKNOWN", "Order.txt")
        val result = compareSafely(DeclarationEngine(project), request, Cancellation.NONE)

        val outcome = ReferencePass.runWith(project, request, result, Cancellation.NONE, ReferenceResolvers.all, TEST_BUDGET_MILLIS)

        assertEquals(ReferencePass.Status.NOT_GROUPED, outcome.status)
        assertSame(result, outcome.result)
    }

    fun `test R10 Scala 2 and Scala 3 report their own whitespace policy to the outline`() {
        // relationships/04-rename-grouping R10; declarations/06-change-labels R10
        val scala2 = "class Order {\n  def total(): Int = 1\n  def twice(): Int = total() * 2\n}\n"
        val scala2New = "class Order {\n  def sum(): Int = 1\n  def twice(): Int = sum() * 2\n}\n"
        val scala3 = "class Order:\n  def total(): Int = 1\n  def twice(): Int = total() * 2\n"
        val scala3New = "class Order:\n  def sum(): Int = 1\n  def twice(): Int = sum() * 2\n"

        val two = outcome("Scala", "Order.scala", scala2, scala2New)
        val three = outcome("Scala", "Order.scala", scala3, scala3New)

        assertEquals(WhitespacePolicy.LINE_BREAKS_SIGNIFICANT, two.whitespacePolicy)
        assertEquals(WhitespacePolicy.INDENTATION_SIGNIFICANT, three.whitespacePolicy)
    }

    private fun outcome(language: String, fileName: String, old: String, new: String): ReferencePass.Outcome {
        val request = ComparisonRequest(old, new, language, fileName)
        val result = compareSafely(DeclarationEngine(project), request, Cancellation.NONE)
        return ReferencePass.runWith(project, request, result, Cancellation.NONE, ReferenceResolvers.all, TEST_BUDGET_MILLIS)
    }

    private fun usages(language: String, fileName: String, old: String, new: String): List<Evidence.Reference> {
        val request = ComparisonRequest(old, new, language, fileName)
        val result = compareSafely(DeclarationEngine(project), request, Cancellation.NONE)
        val outcome = ReferencePass.runWith(project, request, result, Cancellation.NONE, ReferenceResolvers.all, TEST_BUDGET_MILLIS)

        assertEquals(ReferencePass.Status.GROUPED, outcome.status)
        return outcome.result.relationships
            .filter { RelationshipKind.RENAME in it.kinds }
            .flatMap { it.evidence.filterIsInstance<Evidence.Reference>() }
    }
}
