package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.ui.OutlineItem
import io.github.agadek.smartdiff.ui.OutlineModel
import io.github.agadek.smartdiff.ui.RefactoringsModel
import java.io.File

/**
 * Golden fixtures of code moves (relationships/08-moves-inside-declarations, Tests):
 * `src/test/testData/declarations/code-moves/<language>/<case>/{old,new}.<ext>` and `expected-outline.txt`, which lists
 * the container tree, the refactorings view's relationship rows and every code move with its moved text, `-` for the old
 * side and `+` for the new side. Run the build with `-Psmartdiff.updateGolden=true` to write the expected files; review
 * every change.
 */
class CodeMovesFixturesTest : BasePlatformTestCase() {

    // relationships/08-moves-inside-declarations R2 to R16, R20, R22, R27
    fun `test Java code moves`() = checkFixtures("java", "JAVA", "java")

    // relationships/08-moves-inside-declarations R2 to R16, R20, R22
    fun `test Kotlin code moves`() = checkFixtures("kotlin", "kotlin", "kt")

    // relationships/08-moves-inside-declarations R2 to R16, R20, R22
    fun `test Scala 2 code moves`() = checkFixtures("scala2", "Scala", "scala")

    // relationships/08-moves-inside-declarations R2 to R16, R20, R22, R27
    fun `test Scala 3 code moves`() = checkFixtures("scala3", "Scala", "scala")

    // relationships/08-moves-inside-declarations R2 to R16, R20, R22, R27
    fun `test Python code moves`() = checkFixtures("python", "Python", "py")

    private fun checkFixtures(directory: String, languageId: String, extension: String) {
        val root = File(TEST_DATA, directory)
        val cases = root.listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }
        assertTrue("no fixtures in ${root.absolutePath}", cases.isNotEmpty())
        val engine = DeclarationEngine(project)
        val failures = mutableListOf<String>()
        for (case in cases) {
            val old = read(File(case, "old.$extension"))
            val new = read(File(case, "new.$extension"))
            val result = engine.compare(ComparisonRequest(old, new, languageId, "${case.name}.$extension"), Cancellation.NONE)
            val actual = render(result, old, new)
            val expectedFile = File(case, "expected-outline.txt")
            val expected = if (expectedFile.isFile) read(expectedFile) else null
            if (actual == expected) continue
            if (UPDATE) {
                expectedFile.writeText(actual)
                println("GOLDEN updated ${expectedFile.path}")
            } else {
                failures += "${case.name}: expected\n${expected ?: "(no ${expectedFile.name})\n"}but was\n$actual"
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} $directory fixtures failed:\n\n" + failures.joinToString("\n"))
    }

    private fun render(result: ComparisonResult, old: String, new: String): String = buildString {
        appendLine("status: ${result.status}")
        appendLine("summary: ${OutlineModel.summary(result, oldText = old, newText = new)}")
        appendLine("container tree:")
        fun rows(items: List<OutlineItem>, depth: Int) {
            for (item in items) {
                appendLine("  ".repeat(depth) + item.label)
                rows(item.children, depth + 1)
            }
        }
        rows(OutlineModel.build(result, old, new, formattingInline = true), 1)
        appendLine("refactorings:")
        val sections = RefactoringsModel.build(result, old, new, formattingInline = true)
        sections.first().children.forEach { appendLine("  " + it.label) }
        appendLine("code moves:")
        fun text(source: String, range: TextRange) = source.substring(range.start, range.end)
            .replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
        for (relationship in result.relationships.filter { RelationshipKind.CODE_MOVE in it.kinds }) {
            val content = relationship.evidence.filterIsInstance<Evidence.Content>().single()
            appendLine("  ${RefactoringsModel.title(relationship)}  ${relationship.confidence.name.lowercase()}")
            appendLine("    - \"${text(old, content.oldRange)}\"")
            appendLine("    + \"${text(new, content.newRange)}\"")
        }
    }

    /** Fixture texts are LF-separated like document snapshots, whatever the checkout's line ends. */
    private fun read(file: File): String = file.readText().replace("\r\n", "\n")

    private companion object {
        val TEST_DATA = File("src/test/testData/declarations/code-moves")
        val UPDATE = System.getProperty("smartdiff.updateGolden").toBoolean()
    }
}
