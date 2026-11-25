package io.github.agadek.smartdiff.relationships

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

class SqlRelationshipBenchmarkTest : BasePlatformTestCase() {

    fun `test SQL relationship benchmark cases have no wrong or forced pairs`() {
        // languages/20-sql R12, R14
        val corpus = resolveCorpus()
        val output = File("build/sql-relationships-report").apply { mkdirs() }
        try {
            val report = RelationshipBenchmark.run(
                corpus = corpus,
                output = output,
                minimum = 0,
                commitMinimum = 0,
            )
            val sqlMetrics = report.metricsByLanguage["sql"]
            assertNotNull("sql row present", sqlMetrics)
            val tp = sqlMetrics!!.perKind.values.sumOf { it.tp }
            val fp = sqlMetrics.perKind.values.sumOf { it.fp }
            val fn = sqlMetrics.perKind.values.sumOf { it.fn }
            println(
                "SQL BENCHMARK cases=${report.cases.size} tp=$tp fp=$fp fn=$fn " +
                    "precision=${"%.3f".format(sqlMetrics.precision)} recall=${"%.3f".format(sqlMetrics.recall)} " +
                    "report=${output.absolutePath}",
            )
            assertTrue("no wrong or forced pairs: ${report.gateFailures}", report.gateFailures.isEmpty())
            assertEquals("expected SQL relationships found", 0, fn)
            assertEquals("no wrong SQL relationships", 0, fp)
            assertTrue("at least one SQL relationship found", tp > 0)
        } finally {
            corpus.delete()
        }
    }

    private fun resolveCorpus(): File {
        val listCandidates = listOf(
            File("src/test/testData/relationships-corpus-sql.txt"),
            File("../src/test/testData/relationships-corpus-sql.txt"),
        )
        val listFile = listCandidates.firstOrNull { it.isFile }
            ?: error("SQL corpus list missing: tried ${listCandidates.map { it.path }}")
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
                    ?: error("SQL corpus case missing: $line (tried ${candidates.map { it.path }})")
            }
        val temp = Files.createTempFile("sql-relationships", ".txt").toFile()
        temp.writeText(resolved.joinToString("\n") { it.path } + "\n")
        return temp
    }
}
