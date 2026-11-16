package io.github.agadek.smartdiff.ui

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Which fold regions count as the diff's collapsed unchanged fragments (outline/14, fragment detection). The integration
 * tests use the platform's real fragments; this unit test builds the regions by hand to fix the boundary.
 */
class UnchangedFragmentsTest : BasePlatformTestCase() {

    fun `test outline 14 fragment detection a blank whole-line region is a fragment`() {
        val editor = editor("first\nsecond\nthird\nfourth\nfifth\n")
        // outline/14-expand-to-declaration fragment detection
        fold(editor, 1, 3, "     ", expanded = false)

        val collapsed = UnchangedFragments.collapsed(editor)

        assertEquals(1, collapsed.size)
        assertEquals(1, editor.document.getLineNumber(collapsed.single().startOffset))
        assertEquals(3, editor.document.getLineNumber(collapsed.single().endOffset))
    }

    fun `test outline 14 fragment detection a non-blank placeholder is not a fragment`() {
        val editor = editor("first\nsecond\nthird\nfourth\nfifth\n")
        // outline/14-expand-to-declaration fragment detection
        fold(editor, 1, 3, "{...}", expanded = false)

        assertTrue("a manual code fold is not an unchanged fragment", UnchangedFragments.collapsed(editor).isEmpty())
    }

    fun `test outline 14 fragment detection a region that does not cover whole lines is not a fragment`() {
        val editor = editor("first\nsecond\nthird\nfourth\nfifth\n")
        // outline/14-expand-to-declaration fragment detection
        val document = editor.document
        val start = document.getLineStartOffset(1) + 2
        val end = document.getLineEndOffset(2) - 1
        foldOffsets(editor, start, end, "     ", expanded = false)

        assertTrue("a region starting or ending mid-line is not an unchanged fragment", UnchangedFragments.collapsed(editor).isEmpty())
    }

    fun `test outline 14 fragment detection an expanded region is not a fragment`() {
        val editor = editor("first\nsecond\nthird\nfourth\nfifth\n")
        // outline/14-expand-to-declaration fragment detection
        fold(editor, 1, 3, "     ", expanded = true)

        assertTrue("an expanded region is not collapsed", UnchangedFragments.collapsed(editor).isEmpty())
    }

    /** An editor over [text], released with the test. */
    private fun editor(text: String): com.intellij.openapi.editor.Editor {
        val document = EditorFactory.getInstance().createDocument(text)
        val editor = EditorFactory.getInstance().createEditor(document, project)
        Disposer.register(testRootDisposable) { EditorFactory.getInstance().releaseEditor(editor) }
        return editor
    }

    /** Adds a fold region covering whole lines [first]..[last] (inclusive), as `FoldingModelSupport.addFolding` creates. */
    private fun fold(editor: com.intellij.openapi.editor.Editor, first: Int, last: Int, placeholder: String, expanded: Boolean) {
        val document = editor.document
        foldOffsets(editor, document.getLineStartOffset(first), document.getLineEndOffset(last), placeholder, expanded)
    }

    private fun foldOffsets(
        editor: com.intellij.openapi.editor.Editor,
        start: Int,
        end: Int,
        placeholder: String,
        expanded: Boolean,
    ) {
        editor.foldingModel.runBatchFoldingOperation {
            val region = editor.foldingModel.addFoldRegion(start, end, placeholder)
            assertNotNull("the fold region was added", region)
            region!!.isExpanded = expanded
        }
    }
}
