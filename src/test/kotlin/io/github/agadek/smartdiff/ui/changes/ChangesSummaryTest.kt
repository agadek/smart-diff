package io.github.agadek.smartdiff.ui.changes

import com.intellij.openapi.util.BooleanGetter
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesViewModifier
import com.intellij.openapi.vcs.changes.SimpleContentRevision
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserChangeNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.openapi.vcs.changes.ui.NoneChangesGroupingFactory
import com.intellij.openapi.vcs.changes.ui.TreeModelBuilder
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcsUtil.VcsUtil
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.ui.SmartDiffSettings

/** The structural summary after each file in the Local Changes and Commit tree. */
class ChangesSummaryTest : BasePlatformTestCase() {

    private val settings get() = SmartDiffSettings.getInstance()
    private var saved: SmartDiffSettings.SettingsState? = null

    override fun setUp() {
        super.setUp()
        saved = settings.state
        settings.loadState(SmartDiffSettings.SettingsState())
        // The light project, and so the summary cache, is shared by the tests.
        ChangesSummaryService.getInstance(project).run {
            waitForSummaries()
            clear()
        }
    }

    override fun tearDown() {
        try {
            saved?.let(settings::loadState)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test the modifier is registered for the Changes view`() {
        assertTrue(ChangesViewModifier.KEY.getExtensions(project).any { it is ChangesSummaryModifier })
    }

    fun `test a changed file shows its summary once it is computed`() {
        val change = modified("Cart.java", OLD_CART, NEW_CART)

        assertNull("the first tree is built before the summary is known", summaryNode(change))
        ChangesSummaryService.getInstance(project).waitForSummaries()

        val node = summaryNode(change)
        assertNotNull("the next tree shows the summary", node)
        assertEquals("1 changed · 1 added", node!!.summary)
        assertSame(change, node.userObject)
    }

    fun `test the row shows the file name and then the summary`() {
        val change = modified("Cart.java", OLD_CART, NEW_CART)
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()
        val node = summaryNode(change)!!

        val renderer = ChangesBrowserNodeRenderer(project, BooleanGetter { false }, false)
        node.render(renderer, false, false, false)

        val text = renderer.getCharSequence(false).toString()
        assertTrue(text, text.startsWith("Cart.java"))
        assertTrue(text, text.endsWith("1 changed · 1 added"))
    }

    fun `test the summary is the outline's summary line, or none when the file was not analyzed`() {
        val engine = EngineId("test")

        assertEquals("no structural changes", ChangesSummaryService.summary(ComparisonResult(engine, "1", Status.ANALYZED)))
        assertNull(ChangesSummaryService.summary(ComparisonResult(engine, "1", Status.TOO_LARGE)))
        assertNull(ChangesSummaryService.summary(ComparisonResult(engine, "1", Status.FAILED)))
    }

    fun `test added and deleted files and other languages get no summary`() {
        val added = Change(null, SimpleContentRevision(NEW_CART, VcsUtil.getFilePath("/src/New.java", false), "2"))
        val deleted = Change(SimpleContentRevision(OLD_CART, VcsUtil.getFilePath("/src/Old.java", false), "1"), null)
        val text = modified("notes.txt", "a\n", "b\n")

        listOf(added, deleted, text).forEach { summaryNode(it, listOf(added, deleted, text)) }
        ChangesSummaryService.getInstance(project).waitForSummaries()

        listOf(added, deleted, text).forEach { assertNull(summaryNode(it, listOf(added, deleted, text))) }
    }

    fun `test no summary is shown when the setting is off`() {
        val change = modified("Cart.java", OLD_CART, NEW_CART)
        summaryNode(change)
        ChangesSummaryService.getInstance(project).waitForSummaries()

        settings.showChangesSummary = false

        assertNull(summaryNode(change))
    }

    private fun modified(fileName: String, oldText: String, newText: String): Change {
        val path = VcsUtil.getFilePath("/src/$fileName", false)
        return Change(SimpleContentRevision(oldText, path, "1"), SimpleContentRevision(newText, path, "2"))
    }

    /** Builds the tree for [changes] as the Changes view does and returns the row of [change] if it has a summary. */
    private fun summaryNode(change: Change, changes: List<Change> = listOf(change)): SummaryChangeNode? {
        val builder = TreeModelBuilder(project, NoneChangesGroupingFactory).setChanges(changes, null)
        ChangesSummaryModifier(project).modifyTreeModelBuilder(builder)
        builder.build()
        val all = builder.myRoot.depthFirstEnumeration().toList().filterIsInstance<ChangesBrowserChangeNode>()
        assertEquals("every change keeps one row", changes.size, all.size)
        return all.filterIsInstance<SummaryChangeNode>().firstOrNull { it.userObject === change }
    }

    private companion object {
        val OLD_CART = """
            // cart
            class Cart {
                int total() {
                    return 1;
                }
            }
        """.trimIndent()

        val NEW_CART = """
            // cart
            class Cart {
                int total() {
                    return 2;
                }

                boolean isEmpty() {
                    return total() == 0;
                }
            }
        """.trimIndent()
    }
}
