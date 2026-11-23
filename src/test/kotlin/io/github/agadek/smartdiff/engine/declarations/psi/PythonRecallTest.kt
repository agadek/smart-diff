package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Subject
import java.io.File

/**
 * Relationship recall on the Python provider's real tokens: a short class variable pairs as a rename
 * (relationships/03 R21), and an inlining target's short scattered kept runs count as added (relationships/05 R21).
 */
class PythonRecallTest : BasePlatformTestCase() {

    private fun compare(case: String): ComparisonResult {
        val dir = File("src/test/testData/python/relationships/$case")
        val request = ComparisonRequest(dir.resolve("old.py").readText(), dir.resolve("new.py").readText(), "Python", "test.py")
        return DeclarationEngine(null).compare(request, Cancellation.NONE)
    }

    private fun links(result: ComparisonResult, kind: RelationshipKind): List<String> =
        result.relationships.filter { kind in it.kinds }.map { relationship ->
            val old = relationship.old.single().subject as Subject.Declaration
            val new = relationship.new.single().subject as Subject.Declaration
            "${old.name} -> ${new.name}"
        }

    fun `test R21 a renamed class variable with a short value is a rename`() {
        // relationships/03-relationship-matching R21
        assertEquals(listOf("max_size -> max_length"), links(compare("field-rename-2"), RelationshipKind.RENAME))
    }

    fun `test R21 a helper inlined past the call's scattered tokens links both callers`() {
        // relationships/05-inlining-detection R21
        assertEquals(
            listOf("heading -> render", "heading -> summary"),
            links(compare("inlining-2"), RelationshipKind.INLINE).sorted(),
        )
    }
}
