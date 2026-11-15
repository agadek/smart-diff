package io.github.agadek.smartdiff.relationships

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class RustRelationshipBenchmarkTest : BasePlatformTestCase() {

    fun `test Rust relationship benchmark cases have no wrong or forced pairs`() {
        // languages/05-rust R17
        val corpus = resolveCorpus()
        val output = File("build/rust-relationships-report").apply { mkdirs() }
        try {
            val report = RelationshipBenchmark.run(
                corpus = corpus,
                output = output,
                minimum = 0,
                commitMinimum = 0,
            )
            val rustMetrics = report.metricsByLanguage["rust"]
            assertNotNull("rust row present", rustMetrics)
            val tp = rustMetrics!!.perKind.values.sumOf { it.tp }
            val fp = rustMetrics.perKind.values.sumOf { it.fp }
            val fn = rustMetrics.perKind.values.sumOf { it.fn }
            println(
                "RUST BENCHMARK cases=${report.cases.size} tp=$tp fp=$fp fn=$fn " +
                    "precision=${"%.3f".format(rustMetrics.precision)} recall=${"%.3f".format(rustMetrics.recall)} " +
                    "report=${output.absolutePath}",
            )
            assertTrue("no wrong or forced pairs: ${report.gateFailures}", report.gateFailures.isEmpty())
            assertEquals("expected Rust relationships found", 0, fn)
            assertEquals("no wrong Rust relationships", 0, fp)
            assertTrue("at least one Rust relationship found", tp > 0)
        } finally {
            corpus.delete()
        }
    }

    private fun resolveCorpus(): File {
        val listCandidates = listOf(
            File("src/test/testData/relationships-corpus-rust.txt"),
            File("../src/test/testData/relationships-corpus-rust.txt"),
        )
        val listFile = listCandidates.firstOrNull { it.isFile }
            ?: error("Rust corpus list missing: tried ${listCandidates.map { it.path }}")
        val base = listFile.absoluteFile.parentFile.parentFile.parentFile // project root containing src/
        val resolved = listFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val candidates = listOf(
                    File(line),
                    File(base, line),
                    File("../$line"),
                    File("../../$line"),
                )
                candidates.firstOrNull { it.isDirectory }?.absoluteFile?.normalize()
                    ?: error("Rust corpus case missing: $line (tried ${candidates.map { it.path }})")
            }
        val temp = Files.createTempFile("rust-relationships", ".txt").toFile()
        temp.writeText(resolved.joinToString("\n") { it.path } + "\n")
        return temp
    }
}
