package io.github.agadek.smartdiff.engine.lines

import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.contract.ComparisonEngineContractTest

class LineEngineContractTest : ComparisonEngineContractTest() {
    override fun createEngine(): ComparisonEngine = LineEngine()
}
