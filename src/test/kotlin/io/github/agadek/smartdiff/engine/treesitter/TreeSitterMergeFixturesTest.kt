package io.github.agadek.smartdiff.engine.treesitter

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.LanguageIds
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.contract.NothingHidden
import io.github.agadek.smartdiff.merge.MergeComparison
import io.github.agadek.smartdiff.merge.MergeRendering
import io.github.agadek.smartdiff.merge.MergeTexts
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Executor

/**
 * Merge fixtures for the Tree-sitter languages (engines/05 R6, R30; merge/05 R11), compared by the Tree-sitter engine with
 * the helper the build made: `src/test/testData/treesitter/<language>/merge/<case>/{base,left,right}.<ext>` and
 * `expected-smartdiff.treesitter.txt`, the joined rows as [MergeRendering] writes them. Every fixture also hides nothing
 * on either side. Run with `-Psmartdiff.updateGolden=true` to rewrite the expected files; review every change.
 */
class TreeSitterMergeFixturesTest : BasePlatformTestCase() {

    private val engine = TreeSitterEngine(
        TreeSitterHelpers { Path.of(System.getProperty(TreeSitterHelpers.HELPER_PROPERTY)) },
        enabled = { true },
    )
    private val executor = Executor { it.run() }

    // engines/05 R6, R30
    fun `test Swift merge fixtures`() = checkFixtures("swift", LanguageIds.TREE_SITTER_SWIFT, "swift")

    // engines/05 R6, R30
    fun `test C# merge fixtures`() = checkFixtures("c-sharp", LanguageIds.TREE_SITTER_C_SHARP, "cs")

    // engines/05 R6, R30
    fun `test C merge fixtures`() = checkFixtures("c", LanguageIds.TREE_SITTER_C, "c")

    // engines/05 R6, R30
    fun `test C++ merge fixtures`() = checkFixtures("cpp", LanguageIds.TREE_SITTER_CPP, "cpp")

    fun `test every Tree-sitter language covers the required merge scenarios`() {
        // engines/05 R30; merge/05-scala-merges R11
        for ((directory, extension) in LANGUAGES) {
            val cases = mergeCases(directory)
            val present = cases.map { it.name }.toSet()
            for (scenario in REQUIRED_SCENARIOS) assertTrue("$directory merge fixtures: $scenario", scenario in present)
            for (case in cases) {
                for (name in listOf("base", "left", "right")) {
                    assertTrue("${case.path}/$name.$extension", File(case, "$name.$extension").isFile)
                }
            }
        }
    }

    private fun checkFixtures(directory: String, languageId: String, extension: String) {
        val cases = mergeCases(directory)
        assertTrue("no $directory merge fixtures", cases.isNotEmpty())
        val failures = mutableListOf<String>()
        for (case in cases) {
            val base = read(File(case, "base.$extension"))
            val left = read(File(case, "left.$extension"))
            val right = read(File(case, "right.$extension"))
            val joined = MergeComparison.compare(
                engine,
                MergeTexts(base, left, right, languageId, "${case.name}.$extension"),
                Cancellation.NONE,
                executor,
            )
            for ((side, text, result) in listOf(Triple("left", left, joined.left), Triple("right", right, joined.right))) {
                if (result.status != Status.ANALYZED && result.status != Status.PARTIAL) {
                    failures += "${case.name} $side: status ${result.status}"
                } else {
                    NothingHidden.violation(base, text, result)?.let { failures += "${case.name} $side: $it" }
                }
            }
            val actual = MergeRendering.joined(joined, base, left, right)
            val expectedFile = File(case, "expected-${engine.id}.txt")
            val expected = if (expectedFile.isFile) read(expectedFile) else null
            if (actual == expected) continue
            if (UPDATE) {
                expectedFile.writeText(actual)
                println("GOLDEN updated ${expectedFile.path}")
            } else {
                failures += "${case.name}: expected\n${expected ?: "(no ${expectedFile.name})\n"}but was\n$actual"
            }
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${cases.size} $directory merge fixtures failed:\n\n" + failures.joinToString("\n"))
        }
    }

    private fun mergeCases(directory: String): List<File> =
        File(TEST_DATA, "$directory/merge").listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }

    private fun read(file: File): String = file.readText().replace("\r\n", "\n")

    private companion object {
        const val TEST_DATA = "src/test/testData/treesitter"
        val UPDATE = System.getProperty("smartdiff.updateGolden").toBoolean()
        val LANGUAGES = mapOf("swift" to "swift", "c-sharp" to "cs", "c" to "c", "cpp" to "cpp")
        val REQUIRED_SCENARIOS = listOf(
            "changed-on-both-sides",
            "changed-on-one-side",
            "rename-versus-change",
            "remove-versus-change",
            "add-on-both-sides",
            "move-versus-change",
            "formatting-versus-change",
            "same-change",
            "imports-both-sides",
        )
    }
}
