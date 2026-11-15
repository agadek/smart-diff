package io.github.agadek.smartdiff.engine.declarations.psi

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.contract.NothingHidden
import java.io.File

/**
 * Runs the golden fixture cases in [testData]/[directory] with the declaration engine (technical specification §12).
 * Shared by the root tests and the Rust subproject's tests (languages/05-rust-details §2, §11).
 */
object GoldenFixtures {

    fun checkFixtures(
        directory: String,
        languageId: String,
        extension: String,
        testData: File = File("src/test/testData"),
        update: Boolean = System.getProperty("smartdiff.updateGolden").toBoolean(),
        fail: (String) -> Nothing = { error(it) },
        assertTrue: (String, Boolean) -> Unit = { message, condition -> if (!condition) fail(message) },
        println: (String) -> Unit = { kotlin.io.println(it) },
    ) {
        val cases = File(testData, directory)
            .listFiles { file -> file.isDirectory && file.name != "merge" && file.name != "relationships" }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue("no fixtures in ${File(testData, directory).absolutePath}", cases.isNotEmpty())
        val engine = DeclarationEngine(null)
        val failures = mutableListOf<String>()
        for (case in cases) {
            val old = read(File(case, "old.$extension"))
            val new = read(File(case, "new.$extension"))
            val result = engine.compare(ComparisonRequest(old, new, languageId, "${case.name}.$extension"), Cancellation.NONE)
            if (result.status == Status.ANALYZED || result.status == Status.PARTIAL) {
                NothingHidden.violation(old, new, result)?.let { failures += "${case.name}: $it" }
            }
            val actual = Rendering.result(result, old, new)
            val expectedFile = File(case, "expected-${engine.id}.txt")
            val expected = if (expectedFile.isFile) read(expectedFile) else null
            if (actual == expected) continue
            if (update) {
                expectedFile.writeText(actual)
                println("GOLDEN updated ${expectedFile.path}")
            } else {
                failures += "${case.name}: expected\n${expected ?: "(no ${expectedFile.name})\n"}but was\n$actual"
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} $directory fixtures failed:\n\n" + failures.joinToString("\n"))
    }

    /** Fixture texts are LF-separated like document snapshots, whatever the checkout's line ends. */
    private fun read(file: File): String = file.readText().replace("\r\n", "\n")
}
