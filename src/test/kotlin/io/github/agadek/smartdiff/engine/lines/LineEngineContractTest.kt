package io.github.agadek.smartdiff.engine.lines

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.contract.ComparisonEngineContractTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LineEngineContractTest : ComparisonEngineContractTest() {
    override fun createEngine(): ComparisonEngine = LineEngine()

    @Test
    fun `R22 the line engine reports no relationships and passes every relationship check`() {
        // relationships/02-relationship-model R22
        val result = LineEngine().compare(ComparisonRequest("a\nb\n", "a\nc\n", "TEXT"), Cancellation.NONE)

        assertEquals(emptyList<Relationship>(), result.relationships)
    }
}
