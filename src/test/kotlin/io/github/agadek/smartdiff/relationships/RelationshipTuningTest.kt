package io.github.agadek.smartdiff.relationships

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.ConfidencePolicy
import org.junit.Assert.assertNull
import java.nio.file.Files

/**
 * The tuning run of relationships/03 R20. It scores the `tuning` split only, so the held-out split is never read while
 * the weights and the high threshold are chosen. The run is opt-in through the benchmark's corpus property; without it
 * the test checks the threshold rule on a hand-built table.
 */
class RelationshipTuningTest : BasePlatformTestCase() {

    fun `test R20 the high threshold is tuned on the tuning split only`() {
        // relationships/03-relationship-matching R20
        val list = RelationshipBenchmark.listFile(System.getProperty(RelationshipBenchmark.CORPUS_PROPERTY))
        if (list == null) {
            assertNull("no tuning table without a corpus", RelationshipBenchmark.tunedHigh(emptyList()))
            val rows = listOf(
                RelationshipBenchmark.TuningRow(0.80, high = 10, correctHigh = 10, correctPairs = 10),
                RelationshipBenchmark.TuningRow(0.90, high = 10, correctHigh = 10, correctPairs = 10),
                RelationshipBenchmark.TuningRow(1.00, high = 1, correctHigh = 1, correctPairs = 10),
            )
            // The highest threshold with at least 95% precision and three quarters coverage.
            assertEquals(0.90, RelationshipBenchmark.tunedHigh(rows))
            return
        }
        val output = Files.createTempDirectory("relationship-tuning").toFile()
        try {
            val report = RelationshipBenchmark.run(
                list,
                output,
                split = RelationshipBenchmark.Split.TUNING,
                minimum = 0,
                commitMinimum = 0,
            )
            val metrics = report.overall
            println(
                "RELATIONSHIPS TUNING cases=${report.cases.size} precision=${metrics.precision} recall=${metrics.recall} " +
                    "reported=${metrics.reportedPairs} labelled=${metrics.labelledPairs}",
            )
            for ((kind, counts) in metrics.perKind) {
                println("RELATIONSHIPS TUNING kind=$kind tp=${counts.tp} fp=${counts.fp} fn=${counts.fn} precision=${counts.precision} recall=${counts.recall}")
            }
            RelationshipBenchmark.wrongPairLines(report.cases).sorted().forEach { println("RELATIONSHIPS TUNING wrong=$it") }
            RelationshipBenchmark.missedRelationships(report.cases).sorted().forEach { println("RELATIONSHIPS TUNING missed=$it") }
            val rows = RelationshipBenchmark.tuningTable(report.cases)
            for (row in rows) {
                println(
                    "RELATIONSHIPS TUNING threshold=${row.threshold} high=${row.high} correctHigh=${row.correctHigh} " +
                        "correct=${row.correctPairs} precision=${row.precision} coverage=${row.coverage}",
                )
            }
            val tuned = RelationshipBenchmark.tunedHigh(rows)
            println("RELATIONSHIPS TUNING chosen=${tuned ?: "none, keep ${ConfidencePolicy.DEFAULT.high}"}")
            if (tuned != null) {
                assertEquals("the checked-in high threshold is the tuned value", ConfidencePolicy.DEFAULT.high, tuned, 1e-9)
            }
        } finally {
            output.deleteRecursively()
        }
    }

}
