package io.github.agadek.smartdiff.engine.api

import com.intellij.openapi.progress.ProcessCanceledException
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** [ApiGuard] turns off only the feature whose API is missing (platform/01 R10, R14). */
class ApiGuardTest {

    private val warnings = mutableListOf<String>()
    private var savedReporter: (String) -> Unit = {}

    @Before
    fun setUp() {
        ApiGuard.resetForTests()
        savedReporter = ApiGuard.reporter
        ApiGuard.reporter = { warnings += it }
    }

    @After
    fun tearDown() {
        ApiGuard.reporter = savedReporter
        ApiGuard.resetForTests()
    }

    // platform/01-platform-api-stability R10
    @Test
    fun `each linkage error turns the feature off and returns the fallback`() {
        val errors = listOf(
            NoClassDefFoundError("org/jetbrains/plugins/scala/Gone"),
            NoSuchMethodError("ScFunction.gone()"),
            NoSuchFieldError("INSTANCE"),
            AbstractMethodError("ScFunction.name()"),
            IncompatibleClassChangeError("ScFunction is now a class"),
        )
        for (error in errors) {
            ApiGuard.resetForTests()
            assertEquals("$error", "fallback", ApiGuard.run(GuardedFeature.SCALA_PROVIDER, { "fallback" }) { throw error })
            assertFalse("$error turns the feature off", ApiGuard.isOn(GuardedFeature.SCALA_PROVIDER))
        }
    }

    // platform/01-platform-api-stability R10
    @Test
    fun `a feature that is off skips its block`() {
        ApiGuard.run(GuardedFeature.PYTHON_PROVIDER, { 0 }) { throw NoSuchMethodError("gone") }
        var ran = false
        assertEquals(0, ApiGuard.run(GuardedFeature.PYTHON_PROVIDER, { 0 }) { ran = true; 1 })
        assertFalse("the block does not run again", ran)
    }

    // platform/01-platform-api-stability R10, R14
    @Test
    fun `one warning names the feature and the API`() {
        repeat(3) { ApiGuard.run(GuardedFeature.RUST_PROVIDER, {}) { throw NoClassDefFoundError("org/rust/Gone") } }
        assertEquals(1, warnings.size)
        val warning = warnings.single()
        assertTrue(warning, warning.startsWith("SMART Diff turned off the Rust declaration provider: the Rust plugin PSI is not available in this IDE ("))
        assertTrue(warning, "org/rust/Gone" in warning)
    }

    // platform/01-platform-api-stability R10
    @Test
    fun `cancellation and other failures pass through`() {
        for (thrown in listOf(ProcessCanceledException(), CancellationException(), IllegalStateException("other"))) {
            try {
                ApiGuard.run(GuardedFeature.SCALA_PROVIDER, { "fallback" }) { throw thrown }
                fail("$thrown is rethrown")
            } catch (e: Throwable) {
                assertTrue("$e", e === thrown)
            }
        }
        assertTrue("the feature stays on", ApiGuard.isOn(GuardedFeature.SCALA_PROVIDER))
        assertTrue(warnings.isEmpty())
    }

    // platform/01-platform-api-stability R14
    @Test
    fun `other features stay on`() {
        ApiGuard.run(GuardedFeature.SCALA_PROVIDER, {}) { throw NoSuchMethodError("gone") }
        GuardedFeature.entries.filter { it != GuardedFeature.SCALA_PROVIDER }.forEach {
            assertTrue("$it stays on", ApiGuard.isOn(it))
            assertEquals(1, ApiGuard.run(it, { 0 }) { 1 })
        }
    }
}
