package io.github.agadek.smartdiff.engine.treesitter

import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.contract.ComparisonEngineContractTest
import java.nio.file.Path

/** engines/05 R6, R25, engines/02 R11: the Tree-sitter engine passes the shared contract suite on its Swift cases. */
class TreeSitterEngineContractTest : ComparisonEngineContractTest() {
    override fun createEngine(): ComparisonEngine =
        TreeSitterEngine(TreeSitterHelpers { Path.of(System.getProperty(TreeSitterHelpers.HELPER_PROPERTY)) }, enabled = { true })
}
