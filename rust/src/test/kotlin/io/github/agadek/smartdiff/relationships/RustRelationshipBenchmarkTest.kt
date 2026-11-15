package io.github.agadek.smartdiff.relationships

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class RustRelationshipBenchmarkTest : BasePlatformTestCase() {

    fun `test Rust relationship benchmark cases have no wrong or forced pairs`() {
        // languages/05-rust R17
        val testData = resolveTestData()
        val rustCases = File(testData, "rust/relationships")
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue("no Rust relationship benchmark cases in ${rustCases}", rustCases.isNotEmpty())
        val corpus = Files.createTempFile("rust-relationships", ".txt").toFile()
        val output = Files.createTempDirectory("rust-relationships-report").toFile()
        try {
            corpus.writeText(rustCases.joinToString("\n") { it.absolutePath } + "\n")
            val report = RelationshipBenchmark.run(
                corpus = corpus,
                output = output,
                minimum = 0,
                commitMinimum = 0,
            )
            assertTrue("no wrong or forced pairs: ${report.gateFailures}", report.gateFailures.isEmpty())
            val rustMetrics = report.metricsByLanguage["rust"]
            assertNotNull("rust row present", rustMetrics)
            val tp = rustMetrics!!.perKind.values.sumOf { it.tp }
            val fp = rustMetrics.perKind.values.sumOf { it.fp }
            val fn = rustMetrics.perKind.values.sumOf { it.fn }
            println(
                "RUST BENCHMARK cases=${report.cases.size} tp=$tp fp=$fp fn=$fn " +
                    "precision=${"%.3f".format(rustMetrics.precision)} recall=${"%.3f".format(rustMetrics.recall)}",
            )
        } finally {
            corpus.delete()
            output.deleteRecursively()
        }
    }

    private fun resolveTestData(): File {
        val candidates = listOf(File("src/test/testData"), File("../src/test/testData"))
        return candidates.firstOrNull { it.isDirectory } ?: candidates.first()
    }
}
