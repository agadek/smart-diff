package io.github.agadek.smartdiff.ui.changes

import com.intellij.openapi.vcs.changes.ui.ChangesViewModelBuilder
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.ApiGuard
import io.github.agadek.smartdiff.engine.api.GuardedFeature
import java.lang.reflect.Proxy

/** A Changes tree hook whose platform API is missing leaves the tree without summaries (platform/01 R13, R14). */
class GuardedChangesTreeTest : BasePlatformTestCase() {

    private val warnings = mutableListOf<String>()
    private var savedReporter: (String) -> Unit = {}

    override fun setUp() {
        super.setUp()
        ApiGuard.resetForTests()
        savedReporter = ApiGuard.reporter
        ApiGuard.reporter = { warnings += it }
    }

    override fun tearDown() {
        try {
            ApiGuard.reporter = savedReporter
            ApiGuard.resetForTests()
        } finally {
            super.tearDown()
        }
    }

    private val builder = Proxy.newProxyInstance(
        ChangesViewModelBuilder::class.java.classLoader, arrayOf(ChangesViewModelBuilder::class.java),
    ) { _, _, _ -> null } as ChangesViewModelBuilder

    // platform/01-platform-api-stability R13, R14
    fun `test the modifier throwing NoSuchMethodError leaves the tree without summaries`() {
        var calls = 0
        val modifier = ChangesSummaryModifier(project) { calls++; throw NoSuchMethodError("ChangesViewModelBuilder.gone()") }

        modifier.modifyTreeModelBuilder(builder) // does not fail
        modifier.modifyTreeModelBuilder(builder)

        assertEquals("the hook is off after the first failure", 1, calls)
        assertFalse(ApiGuard.isOn(GuardedFeature.CHANGES_TREE_HOOK))
        assertEquals("one warning, never an error report", 1, warnings.size)
        assertTrue(warnings.single(), "the experimental Changes view API" in warnings.single())
    }

    // platform/01-platform-api-stability R13
    fun `test the node action and the tree setup do nothing while the hook is off`() {
        ApiGuard.turnOff(GuardedFeature.CHANGES_TREE_HOOK, NoSuchMethodError("gone"))
        var opened = false
        val action = DeclarationNodeAction(project) { _, _, _, _, _ -> opened = true; true }
        assertFalse(action.handleDoubleClick(com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode.createRoot()))
        assertFalse(opened)
        ChangesTreeSetup.installIn(project, suppressExpansion = true) // returns without touching any tree
    }
}
