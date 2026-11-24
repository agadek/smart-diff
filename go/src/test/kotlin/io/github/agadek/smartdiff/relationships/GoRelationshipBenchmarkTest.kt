package io.github.agadek.smartdiff.relationships

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class GoRelationshipBenchmarkTest : BasePlatformTestCase() {

    fun `test Go relationship benchmark cases have no wrong or forced pairs`() {
        // languages/17-go R15, R17
        val corpus = resolveCorpus()
        val output = File("build/go-relationships-report").apply { mkdirs() }
        try {
            val report = RelationshipBenchmark.run(
                corpus = corpus,
                output = output,
                minimum = 0,
                commitMinimum = 0,
            )
            val goMetrics = report.metricsByLanguage["go"]
            assertNotNull("go row present", goMetrics)
            val tp = goMetrics!!.perKind.values.sumOf { it.tp }
            val fp = goMetrics.perKind.values.sumOf { it.fp }
            val fn = goMetrics.perKind.values.sumOf { it.fn }
            println(
                "GO BENCHMARK cases=${report.cases.size} tp=$tp fp=$fp fn=$fn " +
                    "precision=${"%.3f".format(goMetrics.precision)} recall=${"%.3f".format(goMetrics.recall)} " +
                    "report=${output.absolutePath}",
            )
            assertTrue("no wrong or forced pairs: ${report.gateFailures}", report.gateFailures.isEmpty())
            assertEquals("expected Go relationships found", 0, fn)
            assertEquals("no wrong Go relationships", 0, fp)
            assertTrue("at least one Go relationship found", tp > 0)
        } finally {
            corpus.delete()
        }
    }

    private fun resolveCorpus(): File {
        val listCandidates = listOf(
            File("src/test/testData/relationships-corpus-go.txt"),
            File("../src/test/testData/relationships-corpus-go.txt"),
        )
        val listFile = listCandidates.firstOrNull { it.isFile }
            ?: error("Go corpus list missing: tried ${listCandidates.map { it.path }}")
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
                    ?: error("Go corpus case missing: $line (tried ${candidates.map { it.path }})")
            }
        val temp = Files.createTempFile("go-relationships", ".txt").toFile()
        temp.writeText(resolved.joinToString("\n") { it.path } + "\n")
        return temp
    }
}
