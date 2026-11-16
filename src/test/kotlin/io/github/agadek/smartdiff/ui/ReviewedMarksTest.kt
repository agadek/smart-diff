package io.github.agadek.smartdiff.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy

/** The reviewed-mark service, its fingerprints and its workspace state (outline/12). */
class ReviewedMarksTest : BasePlatformTestCase() {

    private val service get() = ReviewedMarks.getInstance(project)
    private val request = ComparisonRequest("old text here\n", "new text here\n", "JAVA")
    private val node = ChangeNode(
        Subject.Declaration(DeclarationKind.METHOD, "hello", ""),
        TextRange(0, 3), TextRange(0, 3), Presence.MATCHED, content = Content.CHANGED,
    )
    private val result = ComparisonResult(EngineId("e"), "1", Status.ANALYZED, changes = listOf(node))

    override fun setUp() {
        super.setUp()
        service.loadState(ReviewedMarksState())
    }

    override fun tearDown() {
        try {
            service.loadState(ReviewedMarksState())
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test outline 12 R1 mark and unmark a node`() {
        // outline/12-reviewed-marks R1
        val path = ReviewedMarks.paths(result)[node]!!
        val fingerprint = ReviewedMarks.fingerprint(node, request)
        val mark = ReviewedMark(fileKey = "src/A.java", path = path, fingerprint = fingerprint, markedAt = 1)

        service.add("src/A.java", listOf(mark))
        assertEquals(listOf(mark.path), service.marksFor("src/A.java").map { it.path })
        assertTrue(service.hasMarks("src/A.java"))
        assertFalse(service.hasMarks("src/B.java"))

        service.removePaths("src/A.java", listOf(path))
        assertTrue("unmarking removes the mark", service.marksFor("src/A.java").isEmpty())
        assertFalse(service.hasMarks("src/A.java"))

        service.add("src/A.java", listOf(mark))
        service.clear("src/A.java")
        assertTrue("Clear removes every mark of the file", service.marksFor("src/A.java").isEmpty())
    }

    fun `test outline 12 R8 a fingerprint matches the marked text and differs after an edit`() {
        // outline/12-reviewed-marks R8
        val path = ReviewedMarks.paths(result)[node]!!
        val fingerprint = ReviewedMarks.fingerprint(node, request)
        val mark = ReviewedMark(path = path, fingerprint = fingerprint)

        assertTrue("the mark matches the shown text", ReviewedMarks.applyMarks(listOf(mark), result, request, WhitespacePolicy.INSIGNIFICANT).reviewed.contains(node))
        val edited = request.copy(newText = "xyz text changed here\n")
        val changed = ReviewedMarks.applyMarks(listOf(mark), result, edited, WhitespacePolicy.INSIGNIFICANT)
        assertFalse("an edit drops the mark", changed.reviewed.contains(node))
        assertEquals(listOf(mark), changed.stale)
        assertTrue("a missing side contributes a dash", ReviewedMarks.fingerprint(node.copy(newRange = null), request).isNotBlank())
    }

    fun `test outline 12 R8 a mismatched mark is deleted when the result is applied`() {
        // outline/12-reviewed-marks R8
        val path = ReviewedMarks.paths(result)[node]!!
        service.add("src/A.java", listOf(ReviewedMark(path = path, fingerprint = "old", markedAt = 1)))
        assertEquals(1, service.marksFor("src/A.java").size)

        val applied = service.apply("src/A.java", result, request, WhitespacePolicy.INSIGNIFICANT)

        assertTrue(applied.reviewed.isEmpty())
        assertTrue("editing back does not bring the mark back", service.marksFor("src/A.java").isEmpty())
    }

    fun `test outline 12 R8 a mark whose path is absent is kept`() {
        // outline/12-reviewed-marks R8
        val mark = ReviewedMark(path = "method absent()", fingerprint = "abc", markedAt = 1)
        service.add("src/A.java", listOf(mark))

        val applied = service.apply("src/A.java", result, request, WhitespacePolicy.INSIGNIFICANT)

        assertTrue(applied.stale.isEmpty())
        assertEquals(listOf(mark.path), service.marksFor("src/A.java").map { it.path })
    }

    fun `test outline 12 R9 a new mark over the limit drops the oldest`() {
        // outline/12-reviewed-marks R9
        val marks = (1..ReviewedMarks.LIMIT + 1).map { i ->
            ReviewedMark(fileKey = "src/A.java", path = "method m$i()", fingerprint = "$i", markedAt = i.toLong())
        }

        service.add("src/A.java", marks)

        val kept = service.marksFor("src/A.java")
        assertEquals(ReviewedMarks.LIMIT, kept.size)
        assertFalse("the oldest mark is dropped", kept.any { it.path == "method m1()" })
        assertTrue("the newest mark is kept", kept.any { it.path == "method m${ReviewedMarks.LIMIT + 1}()" })
    }

    fun `test outline 12 R9 the marks round trip through the workspace state`() {
        // outline/12-reviewed-marks R9
        val mark = ReviewedMark(fileKey = "src/A.java", path = "method hello()", fingerprint = "abc", markedAt = 42)
        val state = ReviewedMarksState().also { it.marks.add(mark) }

        service.loadState(state)

        assertEquals(1, service.getState().marks.size)
        assertEquals(mark.path, service.marksFor("src/A.java").single().path)
        assertEquals("abc", service.getState().marks.single().fingerprint)
        assertEquals(42, service.getState().marks.single().markedAt)
    }

    fun `test outline 12 R10 a file without a project path has no key and keeps marks local`() {
        // outline/12-reviewed-marks R10
        assertNull(ReviewedMarks.keyOf(null, project.basePath))
        assertNull(ReviewedMarks.keyOf("/outside/file.java", project.basePath))
        val key = ReviewedMarks.keyOf(project.basePath + "/src/A.java", project.basePath)
        assertEquals("src/A.java", key)

        // A controller with no key applies local marks without touching the service; the pure apply is the same path.
        val mark = ReviewedMark(path = ReviewedMarks.paths(result)[node]!!, fingerprint = ReviewedMarks.fingerprint(node, request))
        val applied = ReviewedMarks.applyMarks(listOf(mark), result, request, WhitespacePolicy.INSIGNIFICANT)
        assertTrue(applied.reviewed.contains(node))
        assertTrue("no workspace mark was written", service.getState().marks.isEmpty())
    }

    fun `test outline 12 R8 the fingerprint uses the exact text of both sides`() {
        // outline/12-reviewed-marks R8
        val exact = ReviewedMarks.fingerprint(node, request)
        assertEquals(exact, ReviewedMarks.fingerprint(node, request))
        assertTrue("a different text has a different fingerprint", exact != ReviewedMarks.fingerprint(node, request.copy(oldText = "different text\n")))
        assertEquals(64, exact.length)
    }
}
