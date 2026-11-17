package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import java.io.File

/**
 * Golden fixtures of the changed runs (declarations/10-fine-grained-differencing, Tests):
 * `src/test/testData/declarations/changed-runs/<language>/<case>/{old,new}.<ext>` and `expected-changed-runs.txt`, which
 * lists every changed or formatting-only node with the text of its runs under it, `-` for the old side and `+` for the
 * new side. Run the build with `-Psmartdiff.updateGolden=true` to write the expected files; review every change.
 */
class ChangedRunsFixturesTest : BasePlatformTestCase() {

    // declarations/10-fine-grained-differencing R2 to R7
    fun `test Java changed runs`() = checkFixtures("java", "JAVA", "java")

    // declarations/10-fine-grained-differencing R2 to R7
    fun `test Kotlin changed runs`() = checkFixtures("kotlin", "kotlin", "kt")

    // declarations/10-fine-grained-differencing R2 to R7
    fun `test Scala 2 changed runs`() = checkFixtures("scala2", "Scala", "scala")

    // declarations/10-fine-grained-differencing R2 to R7
    fun `test Scala 3 changed runs`() = checkFixtures("scala3", "Scala", "scala")

    // declarations/10-fine-grained-differencing R2 to R7
    fun `test Python changed runs`() = checkFixtures("python", "Python", "py")

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
            val expectedFile = File(case, "expected-changed-runs.txt")
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
        fun text(source: String, range: TextRange) = source.substring(range.start, range.end)
            .replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
        fun visit(node: ChangeNode, depth: Int) {
            val compared = node.content == Content.CHANGED || node.content == Content.FORMATTING_ONLY
            if (compared) {
                val name = when (val subject = node.subject) {
                    is Subject.Declaration -> "${subject.kind.label()} ${subject.name}"
                    is Subject.Region -> subject.description
                }
                val state = when {
                    !node.comparedByTokens -> "not compared"
                    node.changedRuns.isEmpty() -> "no runs"
                    else -> "${node.changedRuns.size} run" + if (node.changedRuns.size == 1) "" else "s"
                }
                appendLine("  ".repeat(depth) + "$name  ${node.content.name.lowercase()}  $state")
                node.changedRuns.forEach { run ->
                    val parts = listOfNotNull(run.old?.let { "- \"${text(old, it)}\"" }, run.new?.let { "+ \"${text(new, it)}\"" })
                    appendLine("  ".repeat(depth + 1) + parts.joinToString("  "))
                }
            }
            node.children.forEach { visit(it, depth + if (compared) 1 else 0) }
        }
        result.fileRegion?.let { visit(it, 0) }
        result.changes.forEach { visit(it, 0) }
    }

    /** Fixture texts are LF-separated like document snapshots, whatever the checkout's line ends. */
    private fun read(file: File): String = file.readText().replace("\r\n", "\n")

    private companion object {
        val TEST_DATA = File("src/test/testData/declarations/changed-runs")
        val UPDATE = System.getProperty("smartdiff.updateGolden").toBoolean()
    }
}
