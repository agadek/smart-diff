package io.github.agadek.smartdiff.merge

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.contract.NothingHidden
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import java.io.File
import java.util.concurrent.Executor

/**
 * TypeScript merge fixtures (languages/16-javascript-typescript R19, merge/05 R11): run from :javascript where the
 * JavaScript plugin is present. `src/test/testData/typescript/merge/<case>/{base,left,right}.ts` and
 * `expected-smartdiff.declarations.txt`.
 */
class JavaScriptMergeFixturesTest : BasePlatformTestCase() {

    private val engine by lazy { DeclarationEngine(project) }
    private val executor = Executor { it.run() }

    fun `test TypeScript merge fixtures`() {
        // languages/16-javascript-typescript R18, R19
        val cases = mergeCases()
        assertTrue("no TypeScript merge fixtures in ${testData().absolutePath}", cases.isNotEmpty())
        val failures = mutableListOf<String>()
        for (case in cases) {
            val base = read(File(case, "base.ts"))
            val left = read(File(case, "left.ts"))
            val right = read(File(case, "right.ts"))
            val joined = MergeComparison.compare(
                engine,
                MergeTexts(base, left, right, "TypeScript", "${case.name}.ts"),
                Cancellation.NONE,
                executor,
            )
            for ((side, text, result) in listOf(
                Triple("left", left, joined.left),
                Triple("right", right, joined.right),
            )) {
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
            fail("${failures.size} of ${cases.size} TypeScript merge fixtures failed:\n\n" + failures.joinToString("\n"))
        }
    }

    fun `test TypeScript merge fixtures cover merge 05 R11 scenarios`() {
        // merge/05-scala-merges R11, languages/16-javascript-typescript R19
        val present = mergeCases().map { it.name }.toSet()
        for (scenario in REQUIRED_SCENARIOS + TYPESCRIPT_SCENARIOS) {
            assertTrue("TypeScript merge fixtures: $scenario", scenario in present)
        }
    }

    private fun mergeCases(): List<File> =
        File(testData(), "typescript/merge").listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }

    private fun testData(): File {
        val candidates = listOf(File("src/test/testData"), File("../src/test/testData"))
        return candidates.firstOrNull { it.isDirectory } ?: candidates.first()
    }

    private fun read(file: File): String = file.readText().replace("\r\n", "\n")

    private companion object {
        val UPDATE = System.getProperty("smartdiff.updateGolden").toBoolean()
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
        val TYPESCRIPT_SCENARIOS = listOf(
            "export-added-versus-method-changed",
            "interface-property-added-on-both-sides",
        )
    }
}
