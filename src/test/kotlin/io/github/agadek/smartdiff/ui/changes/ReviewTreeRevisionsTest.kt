package io.github.agadek.smartdiff.ui.changes

import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNodeRenderer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.ui.changes.ReviewTreeFixtures.path
import io.github.agadek.smartdiff.ui.changes.ReviewTreeSummaryTest.Companion.revision

/**
 * The two revisions each review tree node compares (changes-tree/07 R4, R7). The Git log's nodes hold the change its
 * diff opens; a pull request node holds the base and head of its [RefComparisonChange].
 */
class ReviewTreeRevisionsTest : BasePlatformTestCase() {

    private fun node(userObject: Any): ChangesBrowserNode<*> = object : ChangesBrowserNode<Any>(userObject) {
        override fun render(renderer: ChangesBrowserNodeRenderer, selected: Boolean, expanded: Boolean, hasFocus: Boolean) = Unit
    }

    fun `test a Git log node compares the revisions of its own change`() {
        // changes-tree/07 R4: one commit, a range and each parent of a merge are each their own change; a MergedChange is a
        // Change too, so its node is summarized from the change it shows
        val commit = ReviewTreeFixtures.modified("Cart.java", oldNumber = "parent", newNumber = "commit")
        val range = ReviewTreeFixtures.modified("Cart.java", oldNumber = "oldest-parent", newNumber = "newest")
        val toFirstParent = ReviewTreeFixtures.modified("Cart.java", oldNumber = "parent-1", newNumber = "merge")
        val toSecondParent = ReviewTreeFixtures.modified("Cart.java", oldNumber = "parent-2", newNumber = "merge")
        val tree = TestReviewTree(project)

        for (change in listOf(commit, range, toFirstParent, toSecondParent)) {
            assertSame(change, GitLogTreeAdapter().changeOf(tree, ChangesBrowserNode.createChange(project, change)))
        }
        assertNull("a node without a change is not a file", GitLogTreeAdapter().changeOf(tree, node("folder")))
    }

    fun `test a pull request node compares the base and head of its change`() {
        // changes-tree/07 R4
        val ref = RefComparisonChange(revision("base"), path("Old.java"), revision("head"), path("New.java"))

        val change = GitHubTreeAdapter().changeOf(TestReviewTree(project), node(ref))!!

        assertEquals("base", change.beforeRevision!!.revisionNumber.asString())
        assertEquals("/repo/Old.java", change.beforeRevision!!.file.path)
        assertEquals("head", change.afterRevision!!.revisionNumber.asString())
        assertEquals("/repo/New.java", change.afterRevision!!.file.path)
        assertEquals("a moved file compares both paths", Change.Type.MOVED, change.type)
    }

    fun `test added and deleted files of a merge request have one side`() {
        // changes-tree/07 R7
        val added = GitLabTreeAdapter().changeOf(
            TestReviewTree(project),
            node(RefComparisonChange(revision("base"), null, revision("head"), path("New.java"))),
        )!!
        val deleted = GitLabTreeAdapter().changeOf(
            TestReviewTree(project),
            node(RefComparisonChange(revision("base"), path("Old.java"), revision("head"), null)),
        )!!

        assertNull(added.beforeRevision)
        assertEquals(Change.Type.NEW, added.type)
        assertNull(deleted.afterRevision)
        assertEquals(Change.Type.DELETED, deleted.type)
    }

    fun `test a pull request change is not read until its content is asked for`() {
        // changes-tree/07 R16: the conversion runs on the EDT and reads nothing
        val ref = RefComparisonChange(revision("base"), path("Cart.java"), revision("head"), path("Cart.java"))
        val change = CodeReviewTreeAdapter.toChange(project, ref)

        assertTrue(change.beforeRevision is LazyGitRevision)
        assertEquals("the cache version is the revision number", "head", ChangesSummaryService.version(change.afterRevision!!))
    }
}
