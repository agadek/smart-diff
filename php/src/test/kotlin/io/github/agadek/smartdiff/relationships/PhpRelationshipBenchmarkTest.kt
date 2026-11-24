package io.github.agadek.smartdiff.relationships

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class PhpRelationshipBenchmarkTest : BasePlatformTestCase() {

    fun `test PHP relationship benchmark cases have no wrong or forced pairs`() {
        // languages/19-php R12, R14
        val corpus = resolveCorpus()
        val output = File("build/php-relationships-report").apply { mkdirs() }
        try {
            val report = RelationshipBenchmark.run(
                corpus = corpus,
                output = output,
                minimum = 0,
                commitMinimum = 0,
            )
            val phpMetrics = report.metricsByLanguage["php"]
            assertNotNull("php row present", phpMetrics)
            val tp = phpMetrics!!.perKind.values.sumOf { it.tp }
            val fp = phpMetrics.perKind.values.sumOf { it.fp }
            val fn = phpMetrics.perKind.values.sumOf { it.fn }
            println(
                "PHP BENCHMARK cases=${report.cases.size} tp=$tp fp=$fp fn=$fn " +
                    "precision=${"%.3f".format(phpMetrics.precision)} recall=${"%.3f".format(phpMetrics.recall)} " +
                    "report=${output.absolutePath}",
            )
            assertTrue("no wrong or forced pairs: ${report.gateFailures}", report.gateFailures.isEmpty())
            assertEquals("expected PHP relationships found", 0, fn)
            assertEquals("no wrong PHP relationships", 0, fp)
            assertTrue("at least one PHP relationship found", tp > 0)
        } finally {
            corpus.delete()
        }
    }

    private fun resolveCorpus(): File {
        val listCandidates = listOf(
            File("src/test/testData/relationships-corpus-php.txt"),
            File("../src/test/testData/relationships-corpus-php.txt"),
        )
        val listFile = listCandidates.firstOrNull { it.isFile }
            ?: error("PHP corpus list missing: tried ${listCandidates.map { it.path }}")
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
                    ?: error("PHP corpus case missing: $line (tried ${candidates.map { it.path }})")
            }
        val temp = Files.createTempFile("php-relationships", ".txt").toFile()
        temp.writeText(resolved.joinToString("\n") { it.path } + "\n")
        return temp
    }
}
