package io.github.agadek.smartdiff.ui.changes

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.ui.ChangeType
import io.github.agadek.smartdiff.ui.SmartDiffSettings

/** The SMART Diff settings and the change type filter also rebuild the review trees (changes-tree/07 R12, R13). */
class ChangesSummarySettingsListenerTest : BasePlatformTestCase() {

    private val settings get() = SmartDiffSettings.getInstance()
    private var saved: SmartDiffSettings.SettingsState? = null

    override fun setUp() {
        super.setUp()
        saved = settings.state
        settings.loadState(SmartDiffSettings.SettingsState())
    }

    override fun tearDown() {
        try {
            ReviewTreeWatcher.getInstance().detachAll()
            saved?.let(settings::loadState)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun attachedTree(): TestReviewTree {
        com.intellij.testFramework.ExtensionTestUtil.maskExtensions(ReviewTreeAdapter.EP_NAME, listOf(TestAdapter()), testRootDisposable)
        val tree = TestReviewTree(project)
        assertNotNull(ReviewTreeWatcher.getInstance().attach(tree))
        return tree
    }

    fun `test turning the summary setting off rebuilds the review trees`() {
        // changes-tree/07 R12
        val tree = attachedTree()
        val listener = ChangesSummarySettingsListener()
        val before = tree.rebuilds

        settings.showChangesSummary = false
        listener.settingsChanged()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue("the review tree was rebuilt", tree.rebuilds > before)
    }

    fun `test the declarations setting rebuilds the review trees`() {
        // changes-tree/07 R12
        val tree = attachedTree()
        val listener = ChangesSummarySettingsListener()
        val before = tree.rebuilds

        settings.showChangesDeclarations = false
        listener.settingsChanged()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(tree.rebuilds > before)
    }

    fun `test the change type filter rebuilds the review trees`() {
        // changes-tree/07 R13
        val tree = attachedTree()
        val listener = ChangesSummarySettingsListener()
        val before = tree.rebuilds

        settings.hiddenChangesTreeChangeTypes = setOf(ChangeType.ADDED)
        listener.settingsChanged()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(tree.rebuilds > before)
    }

    fun `test a detached tree is no longer rebuilt`() {
        // changes-tree/07 Edge cases: a tree disposed with its scope
        val tree = attachedTree()
        ReviewTreeWatcher.getInstance().detach(tree)
        val listener = ChangesSummarySettingsListener()
        val before = tree.rebuilds

        settings.showChangesSummary = false
        listener.settingsChanged()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(before, tree.rebuilds)
        assertNull(ReviewTreeSummaries.of(tree))
    }
}
