package io.github.agadek.smartdiff.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.relationships.RelationshipBenchmark
import java.io.File

/**
 * relationships/06 R6: every change the container tree shows is in the refactorings view, checked over the golden
 * fixtures and every checked-in relationship benchmark case, so a new label or relationship kind cannot disappear
 * from the second view unnoticed.
 */
class RefactoringsCoverageTest : BasePlatformTestCase() {

    fun `test R6 every golden fixture change is in the refactorings view`() {
        val engine = DeclarationEngine(null)
        var cases = 0
        for ((directory, languageId, extension) in LANGUAGES) {
            val folders = File(TEST_DATA, directory)
                .listFiles { file -> file.isDirectory && file.name != "merge" && file.name != "relationships" }
                .orEmpty()
                .sortedBy { it.name }
            assertTrue("no fixtures in $directory", folders.isNotEmpty())
            for (case in folders) {
                cases++
                val old = read(File(case, "old.$extension"))
                val new = read(File(case, "new.$extension"))
                val result = engine.compare(ComparisonRequest(old, new, languageId, "${case.name}.$extension"), Cancellation.NONE)
                assertCovered("$directory/${case.name}", result, old, new)
            }
        }
        assertTrue("the fixtures were checked", cases > 0)
    }

    fun `test R6 every checked-in relationship benchmark case is in the refactorings view`() {
        val engine = DeclarationEngine(null)
        val entries = RelationshipBenchmark.parseCorpus(File("src/test/testData/relationships-corpus.txt"))
        val cases = entries.mapNotNull { it.path }
        assertTrue("the relationship corpus has cases", cases.isNotEmpty())
        for (case in cases) {
            val label = RelationshipBenchmark.parseLabel(File(case, "label.yaml"))
            val extension = extensionFor(label.language)
            val old = read(File(case, "old.$extension"))
            val new = read(File(case, "new.$extension"))
            val result = engine.compare(ComparisonRequest(old, new, label.language, "${label.case}.$extension"), Cancellation.NONE)
            assertCovered(case.name, result, old, new)
        }
    }

    private fun assertCovered(case: String, result: ComparisonResult, old: String, new: String) {
        if (result.status != Status.ANALYZED && result.status != Status.PARTIAL) return
        val container = OutlineModel.build(result, old, new, formattingInline = false)
        val refactorings = RefactoringsModel.build(result, old, new, formattingInline = false)
        val ranges = Ranges.of(refactorings)
        for (item in labelled(container)) {
            assertTrue(
                "$case: ${item.label} [${item.oldRange}, ${item.newRange}] is missing from the refactorings view",
                ranges.covers(item),
            )
        }
    }

    /** The container tree's rows that are changes, not only containers of changes. */
    private fun labelled(items: List<OutlineItem>): List<OutlineItem> =
        items.flatMap { item -> (if (item.tags.isNotEmpty() || item.note != null) listOf(item) else emptyList()) + labelled(item.children) }

    private class Ranges(val old: Set<TextRange>, val new: Set<TextRange>) {
        fun covers(item: OutlineItem): Boolean =
            (item.oldRange != null && item.oldRange in old) || (item.newRange != null && item.newRange in new)

        companion object {
            fun of(items: List<OutlineItem>): Ranges {
                val old = HashSet<TextRange>()
                val new = HashSet<TextRange>()
                fun visit(item: OutlineItem) {
                    item.oldRange?.let { old += it }
                    item.newRange?.let { new += it }
                    item.frames.forEach { frame ->
                        frame.old?.let { old += it }
                        frame.new?.let { new += it }
                    }
                    item.details.forEach { detail ->
                        detail.old?.let { old += it }
                        detail.new?.let { new += it }
                    }
                    item.children.forEach(::visit)
                }
                items.forEach(::visit)
                return Ranges(old, new)
            }
        }
    }

    private fun read(file: File): String = file.readText().replace("\r\n", "\n")

    private fun extensionFor(language: String): String = when (language) {
        "JAVA" -> "java"
        "kotlin" -> "kt"
        "Scala" -> "scala"
        else -> error("unknown language $language")
    }

    private companion object {
        val TEST_DATA = File("src/test/testData")
        val LANGUAGES = listOf(
            Triple("java", "JAVA", "java"),
            Triple("kotlin", "kotlin", "kt"),
            Triple("scala2", "Scala", "scala"),
            Triple("scala3", "Scala", "scala"),
        )
    }
}
