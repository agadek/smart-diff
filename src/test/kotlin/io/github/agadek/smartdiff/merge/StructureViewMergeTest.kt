package io.github.agadek.smartdiff.merge

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.AnalysisNote
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import java.util.concurrent.Executor

/** The merge outline for a structure-view fallback file (languages/06-structure-view-fallback R11). */
class StructureViewMergeTest : BasePlatformTestCase() {

    // languages/06-structure-view-fallback R11
    fun `test R11 both sides of a merge are compared through the fallback`() {
        val engine = DeclarationEngine(project)
        val base = "{\n  \"a\": 1,\n  \"b\": 2\n}\n"
        val left = "{\n  \"a\": 1,\n  \"b\": 3\n}\n"
        val right = "{\n  \"a\": 2,\n  \"b\": 2\n}\n"

        val joined = MergeComparison.compare(
            engine,
            MergeTexts(base, left, right, "JSON", "a.json"),
            Cancellation.NONE,
            Executor { it.run() },
        )

        assertEquals(Status.ANALYZED, joined.left.status)
        assertEquals(Status.ANALYZED, joined.right.status)
        assertEquals(AnalysisNote.STRUCTURE_VIEW, joined.left.analysisNote)
        assertEquals(AnalysisNote.STRUCTURE_VIEW, joined.right.analysisNote)
        assertTrue("the base differs on the left", joined.left.hasReportedChanges)
        assertTrue("the base differs on the right", joined.right.hasReportedChanges)
    }
}
