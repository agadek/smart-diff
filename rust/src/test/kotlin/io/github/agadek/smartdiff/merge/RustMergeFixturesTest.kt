package io.github.agadek.smartdiff.merge

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.contract.NothingHidden
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import java.io.File
import java.util.concurrent.Executor

/**
 * Rust merge fixtures (languages/05-rust R16, R17, merge/05 R11): run from :rust where the Rust plugin is present.
 * `src/test/testData/rust/merge/<case>/{base,left,right}.rs` and `expected-smartdiff.declarations.txt`.
 */
class RustMergeFixturesTest : BasePlatformTestCase() {

    private val engine by lazy { DeclarationEngine(project) }
    private val executor = Executor { it.run() }

    fun `test Rust merge fixtures`() {
        // languages/05-rust R16, R17
        val cases = mergeCases()
        assertTrue("no Rust merge fixtures in ${testData().absolutePath}", cases.isNotEmpty())
        val failures = mutableListOf<String>()
        for (case in cases) {
            val base = read(File(case, "base.rs"))
            val left = read(File(case, "left.rs"))
            val right = read(File(case, "right.rs"))
            val joined = MergeComparison.compare(
                engine,
                MergeTexts(base, left, right, "Rust", "${case.name}.rs"),
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
            fail("${failures.size} of ${cases.size} Rust merge fixtures failed:\n\n" + failures.joinToString("\n"))
        }
    }

    fun `test Rust merge fixtures cover merge 05 R11 scenarios and Rust cases`() {
        // merge/05-scala-merges R11, languages/05-rust R17
        val present = mergeCases().map { it.name }.toSet()
        for (scenario in REQUIRED_SCENARIOS) {
            assertTrue("Rust merge fixtures: $scenario", scenario in present)
        }
        for (scenario in RUST_SCENARIOS) {
            assertTrue("Rust merge fixtures: $scenario", scenario in present)
        }
    }

    private fun mergeCases(): List<File> =
        File(testData(), "rust/merge").listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }

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
        val RUST_SCENARIOS = listOf(
            "derive-added-versus-method-changed",
            "impl-trait-changed",
            "macro-invocation-respaced",
            "whole-file-reformat-outside-macros",
        )
    }
}
