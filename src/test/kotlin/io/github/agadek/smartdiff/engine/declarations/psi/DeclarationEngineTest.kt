package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Limits
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.contract.ComparisonEngineContract
import io.github.agadek.smartdiff.engine.api.contract.ContractCorpus
import io.github.agadek.smartdiff.engine.api.contract.request
import io.github.agadek.smartdiff.engine.registry.EngineRegistry
import java.util.concurrent.CancellationException
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/** The declaration engine over the engine contract suite (technical specification §12) and its own rules. */
class DeclarationEngineTest : BasePlatformTestCase() {

    private val contract by lazy { ComparisonEngineContract { DeclarationEngineFactory().create(null) } }

    fun `test engine supports at least one corpus language`() = contract.`engine supports at least one corpus language`()

    fun `test identical texts have no reported changes`() = contract.`identical texts have no reported changes`()

    fun `test empty and one-side-empty texts are analyzed`() = contract.`empty and one-side-empty texts are analyzed`()

    fun `test results are well-formed and hide nothing`() = contract.`results are well-formed and hide nothing`()

    fun `test malformed input gives a result, not an exception`() = contract.`malformed input gives a result, not an exception`()

    fun `test results are deterministic`() = contract.`results are deterministic`()

    fun `test cancellation propagates promptly`() = contract.`cancellation propagates promptly`()

    fun `test the engine is registered first and supports Java and Kotlin only`() {
        val engines = EngineRegistry.getInstance(project).engines()
        assertEquals(DeclarationEngine.ID, engines.first().id)
        val engine = engines.first()
        assertTrue(engine.supports("JAVA"))
        assertTrue(engine.supports("kotlin"))
        assertFalse(engine.supports("TEXT"))
    }

    fun `test an unsupported language gives UNSUPPORTED`() {
        val result = DeclarationEngine(null).compare(ComparisonRequest("a", "b", "TEXT"), Cancellation.NONE)

        assertEquals(Status.UNSUPPORTED, result.status)
        assertNotNull(result.fallbackReason)
    }

    fun `test too many declarations give TOO_LARGE`() {
        val case = ContractCorpus.large(ContractCorpus.JAVA, size = 20)
        val request = case.request().copy(limits = Limits.DEFAULT.copy(maxDeclarations = 10))

        val result = DeclarationEngine(project).compare(request, Cancellation.NONE)

        assertEquals(Status.TOO_LARGE, result.status)
    }

    fun `test a background thread gives the same result as the test thread`() {
        val engine = DeclarationEngine(null)
        for (case in ContractCorpus.cases.filter { engine.supports(it.languageId) }) {
            val here = engine.compare(case.request(), Cancellation.NONE)
            assertEquals(case.toString(), here, onPooledThread { engine.compare(case.request(), Cancellation.NONE) })
        }
    }

    fun `test cancellation on a background thread reaches the caller unchanged`() {
        val engine = DeclarationEngine(null)
        val canceled = CancellationException("canceled by the test")
        // Caught on the pooled thread, so the platform does not log it as an uncaught exception.
        val outcome = ApplicationManager.getApplication().executeOnPooledThread(Callable {
            runCatching { engine.compare(ContractCorpus.large(ContractCorpus.KOTLIN).request(), Cancellation { throw canceled }) }
        }).get(30, TimeUnit.SECONDS)
        assertSame(canceled, outcome.exceptionOrNull())
    }

    /** Runs [compare] on a pooled thread, where the engine takes read actions that give way to write actions. */
    private fun onPooledThread(compare: () -> ComparisonResult): ComparisonResult =
        ApplicationManager.getApplication().executeOnPooledThread(Callable { compare() }).get(30, TimeUnit.SECONDS)
}
