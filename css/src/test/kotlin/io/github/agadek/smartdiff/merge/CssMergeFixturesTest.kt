package io.github.agadek.smartdiff.merge

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.contract.NothingHidden
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import java.io.File
import java.util.concurrent.Executor

/**
 * CSS, SCSS and Less merge fixtures (languages/18-css R19, R20, merge/05 R11): run from :css where the CSS plugin is
 * present. `src/test/testData/<dialect>/merge/<case>/{base,left,right}.<ext>` and `expected-smartdiff.declarations.txt`.
 */
class CssMergeFixturesTest : BasePlatformTestCase() {

    private val engine by lazy { DeclarationEngine(project) }
    private val executor = Executor { it.run() }

    fun `test CSS SCSS and Less merge fixtures`() {
        // languages/18-css R19, R20
        val failures = mutableListOf<String>()
        var count = 0
        for ((directory, languageId) in DIALECTS) {
            val cases = mergeCases(directory)
            assertTrue("no $directory merge fixtures in ${testData().absolutePath}", cases.isNotEmpty())
            for (case in cases) {
                count++
                val base = read(File(case, "base.$directory"))
                val left = read(File(case, "left.$directory"))
                val right = read(File(case, "right.$directory"))
                val joined = MergeComparison.compare(
                    engine,
                    MergeTexts(base, left, right, languageId, "${case.name}.$directory"),
                    Cancellation.NONE,
                    executor,
                )
                for ((side, text, result) in listOf(
                    Triple("left", left, joined.left),
                    Triple("right", right, joined.right),
                )) {
                    if (result.status != Status.ANALYZED && result.status != Status.PARTIAL) {
                        failures += "$directory/${case.name} $side: status ${result.status}"
                    } else {
                        NothingHidden.violation(base, text, result)?.let { failures += "$directory/${case.name} $side: $it" }
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
                    failures += "$directory/${case.name}: expected\n${expected ?: "(no ${expectedFile.name})\n"}but was\n$actual"
                }
            }
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of $count stylesheet merge fixtures failed:\n\n" + failures.joinToString("\n"))
        }
    }

    fun `test CSS merge fixtures cover merge 05 R11 scenarios and stylesheet cases`() {
        // merge/05-scala-merges R11, languages/18-css R20
        val present = mergeCases("css").map { it.name }.toSet()
        for (scenario in REQUIRED_SCENARIOS + CSS_SCENARIOS) {
            assertTrue("CSS merge fixtures: $scenario", scenario in present)
        }
        assertTrue("SCSS merge fixtures", mergeCases("scss").isNotEmpty())
        assertTrue("Less merge fixtures", mergeCases("less").isNotEmpty())
    }

    private fun mergeCases(directory: String): List<File> =
        File(testData(), "$directory/merge").listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }

    private fun testData(): File {
        val candidates = listOf(File("src/test/testData"), File("../src/test/testData"))
        return candidates.firstOrNull { it.isDirectory } ?: candidates.first()
    }

    private fun read(file: File): String = file.readText().replace("\r\n", "\n")

    private companion object {
        val UPDATE = System.getProperty("smartdiff.updateGolden").toBoolean()
        val DIALECTS = listOf("css" to "CSS", "scss" to "SCSS", "less" to "LESS")
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
        val CSS_SCENARIOS = listOf(
            "media-query-versus-nested-change",
            "custom-properties-both-sides",
        )
    }
}
