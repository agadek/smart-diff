package io.github.agadek.smartdiff.engine.lines

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.AnalysisNote
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.contract.NothingHidden
import io.github.agadek.smartdiff.ui.OutlineModel
import java.io.File
import java.util.concurrent.CancellationException

/** The line comparison of files no comparison engine supports (engines/04). */
class LineEngineTest : BasePlatformTestCase() {

    private val header = "Release notes for the release\n"
    private val first = "The first section has a long line of text here.\nIt goes on with a second line of text.\nA third line ends the first section.\n"
    private val second = "The second section has a long line of text here.\nIt goes on with another line of text.\nA third line ends the second section.\n"
    private val third = "The third section has a long line of text here.\nIt goes on with a different line of text.\nA third line ends the third section.\n"
    private val footer = "End of file\n"

    private val swappedOld get() = header + first + second + third + footer
    private val swappedNew get() = header + third + second + first + footer

    private fun compare(old: String, new: String, cancellation: Cancellation = Cancellation.NONE): ComparisonResult =
        LineEngine().compare(ComparisonRequest(old, new, "TEXT"), cancellation)

    private fun titles(result: ComparisonResult) = result.changes.map { (it.subject as Subject.Region).description }

    private fun reindented(): String = first.lines().joinToString("\n") { "    $it" } + "\n"

    private fun canceled(): Cancellation = object : Cancellation {
        override fun checkCanceled() = throw CancellationException("canceled")
    }

    private fun bothSides(details: List<RangePair>): Boolean = details.all { it.old != null && it.new != null }

    private fun read(file: File): String = file.readText().replace("\r\n", "\n")

    fun `test engines 04 R5 R9 a swapped pair of paragraphs gives one block with exact title and ranges`() {
        // engines/04-moved-lines R5, R9
        val old = header + first + second + footer
        val new = header + second + first + footer
        val result = compare(old, new)

        assertEquals(listOf("lines 2–4 → 5–7"), titles(result))
        val moved = result.changes.single()
        assertEquals(TextRange(header.length, header.length + first.length), moved.oldRange)
        assertEquals(TextRange(header.length + second.length, header.length + second.length + first.length), moved.newRange)
        assertTrue(moved.moved)
        assertEquals(Presence.MATCHED, moved.presence)
    }

    fun `test engines 04 R5 R8 a blank line before a moved block is left in the file region`() {
        // engines/04-moved-lines R5, R8: a blank line never starts a run, so the block starts at the first non-blank line.
        val long = second + third.lines().take(2).joinToString("\n") + "\n"
        val old = header + "\n" + first + long + footer
        val new = header + long + "\n" + first + footer
        val result = compare(old, new)

        val moved = result.changes.single()
        assertEquals(TextRange(header.length + 1, header.length + 1 + first.length), moved.oldRange)
        assertTrue(moved.moved)
    }

    fun `test engines 04 R5 a two-line block and a three-line block of braces give no block`() {
        // engines/04-moved-lines R5: below 3 lines, and below 30 non-whitespace characters.
        val two = "}\n}\n"
        val three = "}\n}\n}\n"
        val old = "start of file\n" + two + "middle of file\n" + three + "end of file\n"
        val new = "start of file\n" + three + "middle of file\n" + two + "end of file\n"

        val result = compare(old, new)

        assertTrue("short blocks are not moved: ${titles(result)}", result.changes.isEmpty())
        assertNotNull("the edits are still reported", result.fileRegion)
    }

    fun `test engines 04 R6 R10 a re-indented block is moved and changed with one detail per line`() {
        // engines/04-moved-lines R6, R10
        val result = compare(header + first + footer, header + reindented() + footer)

        assertEquals(listOf("lines 2–4 → 2–4"), titles(result))
        val block = result.changes.single()
        assertTrue(block.moved)
        assertEquals(Content.CHANGED, block.content)
        assertEquals(3, block.details.size)
        assertTrue(bothSides(block.details))
    }

    fun `test engines 04 R7 a block whose text appears twice among added lines is dropped`() {
        // engines/04-moved-lines R7
        val old = header + first + footer
        val new = header + first + second + first + footer

        val result = compare(old, new)

        assertTrue("an ambiguous block is not moved: ${titles(result)}", result.changes.isEmpty())
        assertNotNull(result.fileRegion)
        assertEquals(Content.CHANGED, result.fileRegion!!.content)
    }

    fun `test engines 04 R8 overlapping candidates share no line and the longest blocks are kept`() {
        // engines/04-moved-lines R8
        val blocks = compare(swappedOld, swappedNew).changes

        assertEquals("the two maximal blocks are kept", 2, blocks.size)
        val oldLines = blocks.flatMap(::oldRangeLines)
        val newLines = blocks.flatMap(::newRangeLines)
        assertEquals("no old line belongs to two blocks", oldLines.size, oldLines.toSet().size)
        assertEquals("no new line belongs to two blocks", newLines.size, newLines.toSet().size)
    }

    fun `test engines 04 R9 blocks are sorted by the new side`() {
        // engines/04-moved-lines R9
        val result = compare(swappedOld, swappedNew)

        assertEquals(listOf("lines 5–7 → 5–7", "lines 2–4 → 8–10"), titles(result))
        assertTrue(result.changes[0].newRange!!.start < result.changes[1].newRange!!.start)
    }

    fun `test engines 04 R12 remaining edits are one file region with changed`() {
        // engines/04-moved-lines R12
        val old = header + first + second + footer
        val new = header + second + "The first section has a typo here.\n" + first.lines().drop(1).joinToString("\n") + "\n" + footer
        val result = compare(old, new)

        val region = result.fileRegion ?: error("the remaining edit has no file region")
        assertEquals(Content.CHANGED, region.content)
        assertTrue(region.details.isNotEmpty())
    }

    fun `test engines 04 R14 identical sides and a file with only edits give no block`() {
        // engines/04-moved-lines R14
        assertTrue(compare(first, first).changes.isEmpty())
        val onlyEdits = compare("a normal line\nsecond line here\n", "a changed line\nsecond line here\n")
        assertTrue(onlyEdits.changes.isEmpty())
        assertNotNull(onlyEdits.fileRegion)
    }

    fun `test engines 04 R13 the result carries the lines analysis note`() {
        // engines/04-moved-lines R13
        val result = compare(swappedOld, swappedNew)

        assertEquals(AnalysisNote.LINES, result.analysisNote)
        assertTrue(OutlineModel.summary(result).endsWith("· lines"))
    }

    fun `test engines 04 R5 R12 every differing character is covered by the result`() {
        // engines/04-moved-lines R5, R12; engine contract rule 6
        val cases = listOf(
            swappedOld to swappedNew,
            (header + first + footer) to (header + reindented() + footer),
            "one\n" to "two\n",
            "" to "added line\n",
        )
        for ((old, new) in cases) {
            val violation = NothingHidden.violation(old, new, compare(old, new))
            assertNull("$old -> $new: $violation", violation)
        }
    }

    fun `test engines 04 R18 a canceled comparison throws`() {
        // engines/04-moved-lines R18
        try {
            compare(swappedOld, swappedNew, canceled())
            fail("the cancellation was ignored")
        } catch (expected: CancellationException) {
            assertEquals("canceled", expected.message)
        }
    }

    fun `test engines 04 R2 R3 R9 R12 R13 golden fixtures`() {
        // engines/04-moved-lines R2, R3, R9, R12, R13
        val root = File("src/test/testData/lines")
        val cases = listOf("changelog" to "txt", "csv" to "csv", "sql" to "sql")
        val failures = mutableListOf<String>()
        for ((name, extension) in cases) {
            val case = File(root, name)
            val old = read(File(case, "old.$extension"))
            val new = read(File(case, "new.$extension"))
            val result = compare(old, new)
            val violation = NothingHidden.violation(old, new, result)
            if (violation != null) {
                failures += "$name hides a difference: $violation"
                continue
            }
            val rows = OutlineModel.build(result, old, new, false).map { it.label }
            val actual = OutlineModel.summary(result) + "\n" + rows.joinToString("\n")
            val expectedFile = File(case, "expected.txt")
            val expected = if (expectedFile.isFile) read(expectedFile) else null
            if (actual == expected) continue
            if (UPDATE) {
                expectedFile.writeText(actual)
                println("GOLDEN updated ${expectedFile.path}")
            } else {
                failures += "$name: expected\n${expected ?: "(no expected.txt)"}\nbut was\n$actual"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }

    private fun oldRangeLines(node: io.github.agadek.smartdiff.engine.api.ChangeNode): List<Int> {
        val range = node.oldRange!!
        return (range.start until range.end).toList()
    }

    private fun newRangeLines(node: io.github.agadek.smartdiff.engine.api.ChangeNode): List<Int> {
        val range = node.newRange!!
        return (range.start until range.end).toList()
    }

    private companion object {
        val UPDATE = System.getProperty("smartdiff.updateGolden").toBoolean()
    }
}
