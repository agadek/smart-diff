package io.github.agadek.smartdiff.relationships

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import java.io.File
import java.nio.file.Files

class RelationshipBenchmarkTest : BasePlatformTestCase() {

    fun `test the benchmark is opt in through the corpus property`() {
        // relationships/01-relationship-benchmark R1
        assertEquals("smartdiff.relationships.corpus", RelationshipBenchmark.CORPUS_PROPERTY)
        assertNull(RelationshipBenchmark.listFile(null))
        assertNull(RelationshipBenchmark.listFile(" "))
        assertNotNull(RelationshipBenchmark.listFile("/tmp/cases"))
    }

    fun `test a labelled case is old and new with a label beside them`() {
        // relationships/01-relationship-benchmark R2
        val directory = Files.createTempDirectory("relationship-case").toFile()
        try {
            File(directory, "old.java").writeText("class Order { int total() { return 1; } }\n")
            File(directory, "new.java").writeText("class Order { int sum() { return 1; } }\n")
            File(directory, "label.yaml").writeText(LABEL)
            val corpus = File(directory, "corpus.txt").apply { writeText(directory.absolutePath + "\n") }
            val entries = RelationshipBenchmark.parseCorpus(corpus)
            assertEquals(1, entries.size)
            assertEquals(directory, entries.single().path)
            assertEquals("rename-method", RelationshipBenchmark.parseLabel(File(directory, "label.yaml")).case)
        } finally {
            directory.deleteRecursively()
        }
    }

    fun `test labels parse relationships classifications and ambiguous cases`() {
        // relationships/01-relationship-benchmark R3, R4, R5, R6, R7
        val directory = Files.createTempDirectory("relationship-label").toFile()
        try {
            val file = File(directory, "label.yaml").apply { writeText(LABEL) }
            val label = RelationshipBenchmark.parseLabel(file)
            assertEquals("JAVA", label.language)
            assertEquals(RelationshipBenchmark.Split.HELD_OUT, label.split)
            assertEquals("rename-method", label.case)
            assertEquals(RelationshipBenchmark.Kind.RENAME, label.relationships.single().kinds.single())
            assertEquals("class Order", label.relationships.single().old.path)
            assertEquals(DeclarationKind.METHOD, label.relationships.single().old.kind)
            assertEquals("total", label.relationships.single().old.name)
            assertEquals("()", label.relationships.single().old.signature)
            assertEquals(TextRange(18, 24), label.relationships.single().old.range)
            assertEquals(1, label.changes.size)
            assertEquals(Presence.MATCHED, label.changes.single().presence)
            assertEquals(Content.UNCHANGED, label.changes.single().content)
            assertEquals("other", label.ambiguous.single().name)
        } finally {
            directory.deleteRecursively()
        }
    }

    fun `test an invalid label fails clearly`() {
        // relationships/01-relationship-benchmark R4
        val directory = Files.createTempDirectory("relationship-bad").toFile()
        try {
            val file = File(directory, "label.yaml").apply { writeText("case: broken\n") }
            val error = try {
                RelationshipBenchmark.parseLabel(file)
                null
            } catch (e: IllegalStateException) {
                e
            }
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty().isNotEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    fun `test the case matrix needs three cases per category and language`() {
        // relationships/01-relationship-benchmark R8, R9
        val languages = listOf("JAVA", "kotlin", "Scala")
        val categories = listOf("method-rename", "move", "formatting")
        val labels = languages.flatMap { language ->
            categories.flatMap { category -> (1..3).map { label("$category-$it", language, category) } }
        }
        assertTrue(RelationshipBenchmark.matrixFailures(labels, languages, categories).isEmpty())
        assertTrue(RelationshipBenchmark.matrixFailures(labels.drop(1), languages, categories).isNotEmpty())
    }

    fun `test the corpus accepts case folders and real commit lines`() {
        // relationships/01-relationship-benchmark R10
        val directory = Files.createTempDirectory("relationship-corpus").toFile()
        try {
            val case = File(directory, "case").apply { mkdirs() }
            val corpus = File(directory, "corpus.txt")
            corpus.writeText(
                buildString {
                    appendLine(case.absolutePath)
                    for (language in listOf("java", "kotlin", "scala2", "scala3")) {
                        repeat(30) { appendLine("/src/$language --max-count=1 HEAD") }
                    }
                },
            )
            val entries = RelationshipBenchmark.parseCorpus(corpus)
            assertEquals(1, entries.count { it.path != null })
            assertEquals(120, entries.count { it.repository != null })
        } finally {
            directory.deleteRecursively()
        }
    }

    fun `test tuning and held-out cases are reported separately`() {
        // relationships/01-relationship-benchmark R11
        val tuning = caseResult(label("rename-1", "JAVA", "rename", RelationshipBenchmark.Split.TUNING), relationships(), nodes())
        val held = caseResult(label("rename-2", "JAVA", "rename", RelationshipBenchmark.Split.HELD_OUT), relationships(), nodes())
        val bySplit = listOf(tuning, held).groupBy { it.label.split }.mapValues { RelationshipBenchmark.evaluate(it.value.map { row -> row.label }, it.value) }
        assertEquals(setOf(RelationshipBenchmark.Split.TUNING, RelationshipBenchmark.Split.HELD_OUT), bySplit.keys)
    }

    fun `test metrics count true and false pairs and wrong endpoints`() {
        // relationships/01-relationship-benchmark R12, R13, R14, R15, R16, R17, R18
        val expected = label("rename-1", "JAVA", "rename")
        val correct = actual(RelationshipBenchmark.Kind.RENAME, old("total"), new("sum"))
        val wrong = actual(RelationshipBenchmark.Kind.RENAME, old("ambiguous"), new("other"))
        val wrongEndpoint = actual(RelationshipBenchmark.Kind.RENAME, old("total"), new("wrong"))
        val result = caseResult(expected, listOf(correct, wrong, wrongEndpoint), nodes(), millis = 2_001, hidden = true, status = Status.FAILED)
        val metrics = RelationshipBenchmark.evaluate(listOf(expected), listOf(result))

        val rename = metrics.perKind.getValue("RENAME")
        assertEquals(1, rename.tp)
        assertEquals(1, rename.fp)
        assertEquals(0, rename.fn)
        assertEquals(1, metrics.forcedPairs)
        assertEquals(1, metrics.classificationCorrect)
        assertEquals(1, metrics.classificationTotal)
        assertEquals(1, metrics.hidden)
        assertEquals(1, metrics.fallback)
        assertEquals(2_001, metrics.slowestMillis)
        assertTrue(RelationshipBenchmark.wrongPairs(listOf(result)).any { it.contains("wrong") })
    }

    fun `test the report has summary markdown json and versions`() {
        // relationships/01-relationship-benchmark R19, R20, R21
        val directory = Files.createTempDirectory("relationship-report").toFile()
        try {
            val result = caseResult(label("rename-1", "JAVA", "rename"), relationships(), nodes())
            val metrics = RelationshipBenchmark.evaluate(listOf(result.label), listOf(result))
            val report = RelationshipBenchmark.Report(
                listOf(result), mapOf("java" to metrics), mapOf(RelationshipBenchmark.Split.TUNING to metrics), metrics,
                "no-new-wrong-pairs", emptyList(), "abc123", directory,
            )
            RelationshipBenchmark.write(report)
            assertTrue(File(directory, "summary.tsv").isFile)
            assertTrue(File(directory, "report.json").isFile)
            val markdown = File(directory, "report.md").readText()
            assertTrue(markdown.contains("Engine:"))
            assertTrue(markdown.contains("IDE:"))
            assertTrue(markdown.contains("corpus revision: abc123"))
            assertTrue(markdown.contains("gate: no-new-wrong-pairs"))
        } finally {
            directory.deleteRecursively()
        }
    }

    fun `test gate modes compare with a baseline`() {
        // relationships/01-relationship-benchmark R22, R23, R24, R25, R26, R27
        val directory = Files.createTempDirectory("relationship-gate").toFile()
        try {
            val baseline = File(directory, "baseline.json").apply {
                writeText("""{"wrongPairs":[],"precision":{"java|RENAME":0.9},"recall":{"java|RENAME":0.9}}""")
            }
            val result = caseResult(label("rename-1", "JAVA", "rename"), listOf(actual(RelationshipBenchmark.Kind.RENAME, old("total"), new("other"))), nodes())
            val metrics = RelationshipBenchmark.evaluate(listOf(result.label), listOf(result))
            val cases = listOf(result)
            assertTrue(RelationshipBenchmark.gateFailures("no-new-wrong-pairs", metrics, mapOf("java" to metrics), baseline, 0.0, cases).isNotEmpty())
            assertTrue(RelationshipBenchmark.gateFailures("precision", metrics, mapOf("java" to metrics), baseline, 0.0, cases).isNotEmpty())
            assertTrue(RelationshipBenchmark.gateFailures("tolerance", metrics, mapOf("java" to metrics), baseline, 5.0, cases).isNotEmpty())
            assertTrue(RelationshipBenchmark.gateFailures("unknown", metrics, mapOf("java" to metrics), baseline, 0.0, cases).isNotEmpty())

            val clean = caseResult(result.label, emptyList(), nodes())
            val cleanMetrics = RelationshipBenchmark.evaluate(listOf(clean.label), listOf(clean))
            assertTrue(RelationshipBenchmark.gateFailures("no-new-wrong-pairs", cleanMetrics, mapOf("java" to cleanMetrics), baseline, 0.0, listOf(clean)).isEmpty())

            val hidden = clean.copy(hidden = true)
            assertTrue(RelationshipBenchmark.gateFailures("no-new-wrong-pairs", cleanMetrics, mapOf("java" to cleanMetrics), null, 0.0, listOf(hidden)).isNotEmpty())
            val slow = clean.copy(millis = 5_001)
            assertTrue(RelationshipBenchmark.gateFailures("no-new-wrong-pairs", cleanMetrics, mapOf("java" to cleanMetrics), null, 0.0, listOf(slow)).isNotEmpty())
            val thrown = clean.copy(exception = "boom")
            assertTrue(RelationshipBenchmark.gateFailures("no-new-wrong-pairs", cleanMetrics, mapOf("java" to cleanMetrics), null, 0.0, listOf(thrown)).isNotEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    fun `test two runs give the same report apart from times`() {
        // relationships/01-relationship-benchmark R28
        val first = Files.createTempDirectory("relationship-run-1").toFile()
        val second = Files.createTempDirectory("relationship-run-2").toFile()
        try {
            RelationshipBenchmark.run(File("src/test/testData/relationships-corpus.txt"), first)
            RelationshipBenchmark.run(File("src/test/testData/relationships-corpus.txt"), second)
            val time = Regex("\"(medianMillis|p95Millis|slowestMillis)\"\\s*:\\s*\\d+")
            val a = File(first, "report.json").readText().replace(time, "")
            val b = File(second, "report.json").readText().replace(time, "")
            assertEquals(a, b)
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }

    fun `test the checked-in labelled corpus runs and writes the report`() {
        // relationships/01-relationship-benchmark R2, R3, R20
        val output = Files.createTempDirectory("relationship-corpus-report").toFile()
        try {
            val report = RelationshipBenchmark.run(File("src/test/testData/relationships-corpus.txt"), output)
            assertEquals(4, report.cases.size)
            assertTrue(File(output, "summary.tsv").isFile)
            assertTrue(File(output, "report.md").isFile)
            assertTrue(File(output, "report.json").isFile)

        } finally {
            output.deleteRecursively()
        }
    }

    fun `test the benchmark runs the corpus named by the property`() {
        // relationships/01-relationship-benchmark R1, R22-R27
        val list = RelationshipBenchmark.listFile(System.getProperty(RelationshipBenchmark.CORPUS_PROPERTY))
        if (list == null) {
            println("RELATIONSHIPS skipped: no corpus (run with -P${RelationshipBenchmark.CORPUS_PROPERTY}=<list>)")
            return
        }
        val output = Files.createTempDirectory("relationship-property").toFile()
        try {
            val report = RelationshipBenchmark.run(list, output)
            assertTrue(report.gateFailures.toString(), report.gateFailures.isEmpty())
        } finally {
            output.deleteRecursively()
        }
    }

    fun `test a multi-kind relationship matches every actual kind`() {
        // relationships/01-relationship-benchmark R12
        val label = label("move-rename", "JAVA", "move-rename").copy(
            relationships = listOf(
                RelationshipBenchmark.LabelledRelationship(
                    setOf(RelationshipBenchmark.Kind.RENAME, RelationshipBenchmark.Kind.MOVE),
                    old("total"), new("sum"), null,
                ),
            ),
        )
        val actuals = listOf(
            actual(RelationshipBenchmark.Kind.RENAME, old("total"), new("sum")),
            actual(RelationshipBenchmark.Kind.MOVE, old("total"), new("sum")),
        )
        val metrics = RelationshipBenchmark.evaluate(listOf(label), listOf(caseResult(label, actuals, nodes())))
        assertEquals(1, metrics.perKind.getValue("RENAME").tp)
        assertEquals(1, metrics.perKind.getValue("MOVE").tp)
        assertEquals(0, metrics.perKind.values.sumOf { it.fp })
    }

    fun `test a missing baseline file is a gate failure`() {
        // relationships/01-relationship-benchmark R21, R22
        val result = caseResult(label("rename-1", "JAVA", "rename"), relationships(), nodes())
        val metrics = RelationshipBenchmark.evaluate(listOf(result.label), listOf(result))
        val failures = RelationshipBenchmark.gateFailures(
            "no-new-wrong-pairs", metrics, mapOf("java" to metrics),
            File("/tmp/no-such-relationship-baseline.json"), 0.0, listOf(result),
        )
        assertTrue(failures.any { it.contains("baseline not found") })
    }

    fun `test the report lists missed relationships and classification mismatches`() {
        // relationships/01-relationship-benchmark R18
        val directory = Files.createTempDirectory("relationship-missed").toFile()
        try {
            val label = label("rename-1", "JAVA", "rename").copy(changes = listOf(
                RelationshipBenchmark.ExpectedChange(endpoint("missing"), Presence.MATCHED, Content.UNCHANGED),
            ))
            val result = caseResult(label, emptyList(), nodes())
            val metrics = RelationshipBenchmark.evaluate(listOf(label), listOf(result))
            RelationshipBenchmark.write(
                RelationshipBenchmark.Report(
                    listOf(result), mapOf("java" to metrics), mapOf(RelationshipBenchmark.Split.TUNING to metrics), metrics,
                    "no-new-wrong-pairs", emptyList(), "abc", directory,
                ),
            )
            val markdown = File(directory, "report.md").readText()
            assertTrue(markdown.contains("## Missed relationships"))
            assertTrue(markdown.contains("## Classification mismatches"))
        } finally {
            directory.deleteRecursively()
        }
    }

    fun `test the baseline is recorded in the validation document`() {
        // relationships/01-relationship-benchmark R29
        val validation = File("docs/VALIDATION.md").readText()
        assertTrue("relationship benchmark section", validation.contains("## 2026-11-01: relationship benchmark"))
        assertTrue("the corpus is recorded", validation.contains("**Corpus:**"))
    }

    private fun label(
        case: String,
        language: String,
        category: String,
        split: RelationshipBenchmark.Split = RelationshipBenchmark.Split.TUNING,
    ) = RelationshipBenchmark.Label(
        case, language, split,
        listOf(RelationshipBenchmark.LabelledRelationship(setOf(RelationshipBenchmark.Kind.RENAME), old("total"), new("sum"), null)),
        listOf(RelationshipBenchmark.ExpectedChange(endpoint("sum"), Presence.MATCHED, Content.UNCHANGED)),
        listOf(old("ambiguous")),
        null,
        category,
    )

    private fun relationships() = listOf(actual(RelationshipBenchmark.Kind.RENAME, old("total"), new("sum")))

    private fun nodes(): List<Pair<RelationshipBenchmark.Endpoint, ChangeNode>> =
        listOf(endpoint("sum") to node("sum"))

    private fun caseResult(
        label: RelationshipBenchmark.Label,
        actualRelationships: List<RelationshipBenchmark.ActualRelationship>,
        nodes: List<Pair<RelationshipBenchmark.Endpoint, ChangeNode>>,
        millis: Long = 5,
        hidden: Boolean = false,
        status: Status = Status.ANALYZED,
        exception: String? = null,
    ) = RelationshipBenchmark.CaseResult(
        label, ComparisonResult(EngineId("test"), "1", status), actualRelationships, nodes, millis, hidden, exception,
    )

    private fun actual(kind: RelationshipBenchmark.Kind, old: RelationshipBenchmark.Endpoint, new: RelationshipBenchmark.Endpoint) =
        RelationshipBenchmark.ActualRelationship(kind, old, new, RelationshipBenchmark.Confidence.HIGH)

    private fun old(name: String) = endpoint(name, TextRange(0, 5))
    private fun new(name: String) = endpoint(name, TextRange(6, 11))
    private fun endpoint(name: String, range: TextRange? = null) =
        RelationshipBenchmark.Endpoint("class Order", DeclarationKind.METHOD, name, "()", range)

    private fun node(name: String) = ChangeNode(
        Subject.Declaration(DeclarationKind.METHOD, name, "()"),
        TextRange(0, 5),
        TextRange(6, 11),
        Presence.MATCHED,
    )

    private companion object {
        const val LABEL = """case: rename-method
language: JAVA
split: held-out
expected:
  relationships:
    - kinds: [RENAME]
      old: { path: "class Order", kind: METHOD, name: total, signature: "()", range: "18..24" }
      new: { path: "class Order", kind: METHOD, name: sum, signature: "()" }
  changes:
    - subject: { path: "class Order", kind: METHOD, name: sum, signature: "()" }
      presence: MATCHED
      content: UNCHANGED
  ambiguous:
    - { path: "class Order", kind: METHOD, name: other, signature: "()" }
notes: "A second method with the same body exists on purpose."
"""
    }
}
