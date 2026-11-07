package io.github.agadek.smartdiff.references

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.compareSafely
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine

/**
 * The Java resolver resolves a same-file usage from a light copy (relationships/04 R5, R14). Step 2 adds the Kotlin K2,
 * Scala 2 and Scala 3 fixtures to this class.
 */
class ReferenceResolutionTest : BasePlatformTestCase() {

    fun `test R5 R14 Java resolves a same-file method usage`() {
        val old = "class Order {\n    int total() { return 2; }\n    int twice() { return total() * 2; }\n}\n"
        val new = "class Order {\n    int sum() { return 2; }\n    int twice() { return sum() * 2; }\n}\n"
        val request = ComparisonRequest(old, new, "JAVA", "Order.java")
        val result = compareSafely(DeclarationEngine(project), request, Cancellation.NONE)

        val outcome = ReferencePass.runForBenchmark(project, request, result, Cancellation.NONE)

        assertEquals(ReferencePass.Status.GROUPED, outcome.status)
        val reference = outcome.result.relationships.single().evidence.filterIsInstance<Evidence.Reference>().single()
        assertEquals("total", old.substring(reference.oldRange.start, reference.oldRange.end))
        assertEquals("sum", new.substring(reference.newRange.start, reference.newRange.end))
    }

    fun `test R5 R14 Java maps a constructor reference to its renamed class`() {
        val old = "class Order {\n    Order() {}\n    static Order create() { return new Order(); }\n}\n"
        val new = "class Cart {\n    Cart() {}\n    static Cart create() { return new Cart(); }\n}\n"
        val request = ComparisonRequest(old, new, "JAVA", "Order.java")
        val result = compareSafely(DeclarationEngine(project), request, Cancellation.NONE)

        val outcome = ReferencePass.runForBenchmark(project, request, result, Cancellation.NONE)

        val references = outcome.result.relationships
            .filter { RelationshipKind.RENAME in it.kinds }
            .flatMap { it.evidence.filterIsInstance<Evidence.Reference>() }
        val constructor = references.filter { old.substring(it.oldRange.start, it.oldRange.end) == "Order" }
        assertFalse("the constructor reference is grouped as a usage of the class: $references", constructor.isEmpty())
        assertTrue(constructor.all { it.symbol.name == "Cart" })
    }
}
