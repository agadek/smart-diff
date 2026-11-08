package io.github.agadek.smartdiff.engine.api.contract

import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import org.junit.Test

/**
 * The engine contract suite (technical specification §12): extend it with [createEngine] returning a fresh engine.
 * Engines whose tests need the IntelliJ test framework can run [ComparisonEngineContract] directly instead.
 */
abstract class ComparisonEngineContractTest {

    protected abstract fun createEngine(): ComparisonEngine

    private val contract by lazy { ComparisonEngineContract(::createEngine) }

    @Test fun `engine supports at least one corpus language`() = contract.`engine supports at least one corpus language`()

    @Test fun `identical texts have no reported changes`() = contract.`identical texts have no reported changes`()

    @Test fun `empty and one-side-empty texts are analyzed`() = contract.`empty and one-side-empty texts are analyzed`()

    @Test fun `results are well-formed and hide nothing`() = contract.`results are well-formed and hide nothing`()

    @Test fun `malformed input gives a result, not an exception`() = contract.`malformed input gives a result, not an exception`()

    @Test fun `results are deterministic`() = contract.`results are deterministic`()

    @Test fun `relationships name declarations in the result`() = contract.`relationships name declarations in the result`()

    @Test fun `relationship ranges lie inside their side`() = contract.`relationship ranges lie inside their side`()

    @Test fun `references name a new endpoint and only a rename`() = contract.`references name a new endpoint and only a rename`()

    @Test fun `extraction fields agree and no relationship repeats`() = contract.`extraction fields agree and no relationship repeats`()

    @Test fun `relationships are in a fixed order`() = contract.`relationships are in a fixed order`()

    @Test fun `relationships hide nothing`() = contract.`relationships hide nothing`()

    @Test fun `cancellation propagates promptly`() = contract.`cancellation propagates promptly`()
}
