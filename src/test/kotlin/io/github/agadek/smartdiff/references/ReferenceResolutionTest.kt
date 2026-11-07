package io.github.agadek.smartdiff.references

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.api.compareSafely
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine

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

    fun `test R14 a language without a resolver keeps the engine result`() {
        val old = "class Order { int total() { return 2; } }\n"
        val new = "class Order { int sum() { return 2; } }\n"
        val request = ComparisonRequest(old, new, "UNKNOWN", "Order.txt")
        val result = compareSafely(DeclarationEngine(project), request, Cancellation.NONE)

        val outcome = ReferencePass.runForBenchmark(project, request, result, Cancellation.NONE)

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
        return ReferencePass.runForBenchmark(project, request, result, Cancellation.NONE)
    }

    private fun usages(language: String, fileName: String, old: String, new: String): List<Evidence.Reference> {
        val request = ComparisonRequest(old, new, language, fileName)
        val result = compareSafely(DeclarationEngine(project), request, Cancellation.NONE)
        val outcome = ReferencePass.runForBenchmark(project, request, result, Cancellation.NONE)

        assertEquals(ReferencePass.Status.GROUPED, outcome.status)
        return outcome.result.relationships
            .filter { RelationshipKind.RENAME in it.kinds }
            .flatMap { it.evidence.filterIsInstance<Evidence.Reference>() }
    }
}
