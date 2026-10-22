package io.github.agadek.smartdiff.engine.api.contract

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.TextRange
import java.util.concurrent.CancellationException

/**
 * The checks every comparison engine must pass (technical specification §3.1, §12), as plain functions that throw
 * [AssertionError], so both [ComparisonEngineContractTest] and platform tests can run them.
 *
 * [newEngine] creates a fresh engine; the checks run only the corpus cases whose language the engine supports.
 */
class ComparisonEngineContract(private val newEngine: () -> ComparisonEngine) {

    private val engine: ComparisonEngine by lazy(newEngine)

    private fun supported(cases: List<ContractCase>) = cases.filter { engine.supports(it.languageId) }

    private val supportedLanguages: List<String> get() = ContractCorpus.languages.filter { engine.supports(it) }

    fun `engine supports at least one corpus language`() {
        check(supportedLanguages.isNotEmpty()) {
            "engine ${engine.id} supports none of ${ContractCorpus.languages}; the contract suite would check nothing"
        }
    }

    fun `identical texts have no reported changes`() {
        for (case in supported(ContractCorpus.identicalCases)) {
            val result = compare(case)
            checkWellFormed(case, result)
            check(case, result.status == Status.ANALYZED) { "status is ${result.status}, expected ANALYZED" }
            check(case, !result.hasReportedChanges) { "reported changes: ${result.changes.filter { it.isReported }}, file region ${result.fileRegion}, unanalyzed ${result.unanalyzed}" }
        }
    }

    fun `empty and one-side-empty texts are analyzed`() {
        for (case in supported(ContractCorpus.emptyCases)) {
            val result = compare(case)
            checkWellFormed(case, result)
            check(case, result.status == Status.ANALYZED || result.status == Status.PARTIAL) { "status is ${result.status}: ${result.fallbackReason}" }
            if (case.oldText.isEmpty() && case.newText.isEmpty()) check(case, !result.hasReportedChanges) { "reported changes between two empty texts" }
            checkNothingHidden(case, result)
        }
    }

    fun `results are well-formed and hide nothing`() {
        for (case in supported(ContractCorpus.cases)) {
            val result = compare(case)
            checkWellFormed(case, result)
            checkNothingHidden(case, result)
        }
    }

    fun `malformed input gives a result, not an exception`() {
        for (case in supported(ContractCorpus.cases.filter { it.malformed })) {
            val result = try {
                engine.compare(case.request(), Cancellation.NONE)
            } catch (e: CancellationException) {
                throw AssertionError("$case: canceled without being asked to", e)
            } catch (e: Exception) {
                throw AssertionError("$case: compare threw instead of returning a FAILED result", e)
            }
            checkWellFormed(case, result)
            checkNothingHidden(case, result)
        }
    }

    fun `results are deterministic`() {
        val cases = supported(ContractCorpus.cases + ContractCorpus.emptyCases)
        val first = cases.map { compare(it) }
        val second = cases.map { compare(it) }
        val fresh = newEngine().let { other -> cases.map { other.compare(it.request(), Cancellation.NONE) } }
        cases.indices.forEach { i ->
            check(cases[i], first[i] == second[i]) { "two runs on one engine differ:\n${first[i]}\n${second[i]}" }
            check(cases[i], first[i] == fresh[i]) { "a fresh engine gives a different result:\n${first[i]}\n${fresh[i]}" }
        }
    }

    fun `cancellation propagates promptly`() {
        for (language in supportedLanguages) {
            val case = ContractCorpus.large(language)

            val counter = CountingCancellation(cancelAt = Int.MAX_VALUE)
            engine.compare(case.request(), counter)
            check(case, counter.calls >= 2) { "checkCanceled was called ${counter.calls} times on a large input; it must be called regularly" }

            for (cancelAt in listOf(1, (counter.calls + 1) / 2, counter.calls)) {
                val canceling = CountingCancellation(cancelAt)
                val thrown = try {
                    val result = engine.compare(case.request(), canceling)
                    throw AssertionError("$case: canceled at check $cancelAt but returned ${result.status}")
                } catch (e: CancellationException) {
                    e
                }
                check(case, thrown === canceling.exception) { "the cancellation exception was replaced by $thrown" }
                check(case, canceling.calls == cancelAt) { "checkCanceled was called again after it threw (${canceling.calls} calls, canceled at $cancelAt)" }
            }
        }
    }

    /** Runs every check. */
    fun checkAll() {
        `engine supports at least one corpus language`()
        `identical texts have no reported changes`()
        `empty and one-side-empty texts are analyzed`()
        `results are well-formed and hide nothing`()
        `malformed input gives a result, not an exception`()
        `results are deterministic`()
        `cancellation propagates promptly`()
    }

    private fun compare(case: ContractCase): ComparisonResult = engine.compare(case.request(), Cancellation.NONE)

    private fun checkNothingHidden(case: ContractCase, result: ComparisonResult) {
        // A fallback status shows the native diff only, so nothing can be hidden.
        if (result.status != Status.ANALYZED && result.status != Status.PARTIAL) return
        NothingHidden.violation(case.oldText, case.newText, result)?.let { throw AssertionError("$case: $it\n$result") }
    }

    private fun checkWellFormed(case: ContractCase, result: ComparisonResult) {
        check(case, result.engineId == engine.id) { "engine id ${result.engineId}, expected ${engine.id}" }
        check(case, result.engineVersion == engine.version) { "engine version ${result.engineVersion}, expected ${engine.version}" }
        when (result.status) {
            Status.ANALYZED -> {
                check(case, result.unanalyzed.isEmpty()) { "ANALYZED with unanalyzed ranges ${result.unanalyzed}" }
                check(case, allNodes(result).none { it.content == Content.UNANALYZED }) { "ANALYZED with UNANALYZED content" }
            }
            Status.PARTIAL -> check(case, result.unanalyzed.isNotEmpty() || allNodes(result).any { it.content == Content.UNANALYZED }) {
                "PARTIAL with nothing unanalyzed"
            }
            Status.UNSUPPORTED, Status.TOO_LARGE, Status.FAILED -> check(case, !result.fallbackReason.isNullOrBlank()) {
                "${result.status} without a reason"
            }
        }
        result.unanalyzed.forEach { checkRange(case, it.old, case.oldText, "unanalyzed old"); checkRange(case, it.new, case.newText, "unanalyzed new") }
        allNodes(result).forEach { checkNode(case, it) }
    }

    private fun checkNode(case: ContractCase, node: ChangeNode) {
        checkRange(case, node.oldRange, case.oldText, "old range of $node")
        checkRange(case, node.newRange, case.newText, "new range of $node")
        node.details.forEach { checkRange(case, it.old, case.oldText, "old detail of $node"); checkRange(case, it.new, case.newText, "new detail of $node") }
        when (node.presence) {
            Presence.MATCHED -> check(case, node.oldRange != null && node.newRange != null) { "MATCHED node without both ranges: $node" }
            Presence.ADDED -> check(case, node.oldRange == null && node.newRange != null) { "ADDED node must have only a new range: $node" }
            Presence.REMOVED -> check(case, node.oldRange != null && node.newRange == null) { "REMOVED node must have only an old range: $node" }
            Presence.UNCERTAIN -> {
                check(case, (node.oldRange == null) != (node.newRange == null)) { "UNCERTAIN node must be on exactly one side: $node" }
                check(case, node.candidates.isNotEmpty()) { "UNCERTAIN node without candidates: $node" }
                val otherSide = if (node.oldRange == null) case.oldText else case.newText
                node.candidates.forEach { checkRange(case, it.range, otherSide, "candidate of $node") }
            }
        }
        if (node.presence != Presence.MATCHED) {
            check(case, !node.moved && !node.signatureChanged && node.renamedFrom == null) {
                "moved, signature changed or renamed on a ${node.presence} node: $node"
            }
        }
        if (node.presence != Presence.UNCERTAIN) check(case, node.candidates.isEmpty()) { "candidates on a ${node.presence} node: $node" }
    }

    private fun checkRange(case: ContractCase, range: TextRange?, text: CharSequence, what: String) {
        if (range != null) check(case, range.end <= text.length) { "$what $range is outside the text (length ${text.length})" }
    }

    private fun allNodes(result: ComparisonResult): List<ChangeNode> {
        val nodes = mutableListOf<ChangeNode>()
        fun walk(node: ChangeNode) {
            nodes += node
            node.children.forEach(::walk)
        }
        result.fileRegion?.let(::walk)
        result.changes.forEach(::walk)
        return nodes
    }

    private inline fun check(case: ContractCase, condition: Boolean, message: () -> String) {
        if (!condition) throw AssertionError("engine ${engine.id}, case $case: ${message()}")
    }

    /** Throws from the [cancelAt]-th call of [checkCanceled] on. */
    private class CountingCancellation(private val cancelAt: Int) : Cancellation {
        var calls = 0
        val exception = CancellationException("canceled by the contract suite")

        override fun checkCanceled() {
            calls++
            if (calls >= cancelAt) throw exception
        }
    }
}

fun ContractCase.request(): ComparisonRequest = ComparisonRequest(oldText, newText, languageId, fileName = null)
