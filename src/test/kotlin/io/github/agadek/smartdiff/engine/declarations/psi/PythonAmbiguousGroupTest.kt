package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import java.io.File

/**
 * relationships/07 R3: the benchmark's ambiguous-rename-group cases must not be forced into a pair. The Python
 * provider's functions carry an empty first token, so their bigram bags used to differ and the shared
 * duplicate-body logic of the matcher never saw them as duplicates.
 */
class PythonAmbiguousGroupTest : BasePlatformTestCase() {

    private fun noForcedRelationships(case: String) {
        val dir = File("src/test/testData/python/relationships/$case")
        val request = ComparisonRequest(dir.resolve("old.py").readText(), dir.resolve("new.py").readText(), "Python", "test.py")
        val result = DeclarationEngine(null).compare(request, Cancellation.NONE)
        assertTrue("$case reports no relationship: ${result.relationships}", result.relationships.isEmpty())
    }

    fun `test the ambiguous rename group 1 is not forced`() = noForcedRelationships("ambiguous-rename-group-1")
    fun `test the ambiguous rename group 2 is not forced`() = noForcedRelationships("ambiguous-rename-group-2")
    fun `test the ambiguous rename group 3 is not forced`() = noForcedRelationships("ambiguous-rename-group-3")
}
