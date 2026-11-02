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
        val languages = mapOf("JAVA" to "java", "kotlin" to "kotlin", "Scala" to "scala2")
        val categories = listOf("method-rename", "move-in-file", "formatting-only")
        val labels = languages.flatMap { (language, corpus) ->
            categories.flatMap { category -> (1..3).map { label("$category-$it", language, category, corpus = corpus) } }
        }
        val corpora = languages.values.toList()
        assertTrue(RelationshipBenchmark.matrixFailures(labels, corpora, categories, commitMinimum = 0).isEmpty())
        assertTrue(RelationshipBenchmark.matrixFailures(labels.drop(1), corpora, categories, commitMinimum = 0).isNotEmpty())
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
        assertEquals(1, metrics.presenceTotal)
        assertEquals(1.0, metrics.presenceAccuracy, 0.0)
        assertEquals(1, metrics.contentTotal)
        assertEquals(1.0, metrics.contentAccuracy, 0.0)
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
        val drafts = RelationshipBenchmark.listFile(System.getProperty(RelationshipBenchmark.DRAFTS_PROPERTY))
        val report = RelationshipBenchmark.run(list, drafts = drafts)
        assertTrue(report.gateFailures.toString(), report.gateFailures.isEmpty())
        assertTrue(File("build/reports/smartdiff-relationships/summary.tsv").isFile)
        assertTrue(File("build/reports/smartdiff-relationships/report.md").isFile)
        assertTrue(File("build/reports/smartdiff-relationships/report.json").isFile)
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
        val result = caseResult(label, actuals, nodes())
        assertTrue(RelationshipBenchmark.wrongPairs(listOf(result)).isEmpty())
        assertTrue(RelationshipBenchmark.missedRelationships(listOf(result)).isEmpty())

        val oneKind = caseResult(label, listOf(actuals.first()), nodes())
        assertEquals(1, RelationshipBenchmark.evaluate(listOf(label), listOf(oneKind)).perKind.getValue("MOVE").fn)
        assertTrue(RelationshipBenchmark.missedRelationships(listOf(oneKind)).any { it.contains("MOVE") })
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

    fun `test the validation section is a smoke run and not the R29 baseline`() {
        // relationships/01-relationship-benchmark R29, R32
        val validation = File("docs/VALIDATION.md").readText()
        assertTrue("relationship benchmark section", validation.contains("## 2026-11-01: relationship benchmark"))
        assertTrue("the corpus is recorded", validation.contains("**Corpus:**"))
        assertTrue("the section calls itself a smoke run", validation.contains("**smoke run**"))
        assertTrue("the section says it is not the R29 baseline", validation.contains("not the R29 baseline"))
    }

    fun `test every run checks the case matrix minimums`() {
        // relationships/01-relationship-benchmark R30
        val labels = (1..3).map { label("method-rename-$it", "JAVA", "method-rename", corpus = "java") }
        val failures = RelationshipBenchmark.matrixFailures(
            labels,
            corpora = listOf("java"),
            categories = listOf("method-rename", "class-rename"),
            labelledCommits = mapOf("java" to 4),
        )
        assertTrue("a category below three cases is listed", failures.any { it.corpus == "java" && it.scope == "class-rename" && it.count == 0 })
        assertFalse("a category at the minimum is not listed", failures.any { it.scope == "method-rename" })
        assertTrue("labelled commits are listed", failures.any { it.corpus == "java" && it.scope == "labelled commits" && it.count == 4 })

        val excused = RelationshipBenchmark.matrixFailures(
            labels,
            corpora = listOf("java"),
            categories = listOf("method-rename", "class-rename"),
            labelledCommits = mapOf("java" to 30),
            notApplicable = mapOf("java" to mapOf("class-rename" to "the language has no such construct")),
        )
        assertTrue("a category with a reason is not a shortfall", excused.isEmpty())
        assertEquals(listOf("class-rename in java: the language has no such construct"), RelationshipBenchmark.notApplicableReasons(mapOf("java" to mapOf("class-rename" to "the language has no such construct"))))
    }

    fun `test a run below a minimum is a smoke run in both reports`() {
        // relationships/01-relationship-benchmark R31
        val output = Files.createTempDirectory("relationship-smoke").toFile()
        try {
            val report = RelationshipBenchmark.run(File("src/test/testData/relationships-corpus.txt"), output)
            assertTrue("the checked-in four-case corpus is below the matrix minimums", report.smoke)
            assertTrue(report.matrixFailures.isNotEmpty())
            val markdown = File(output, "report.md").readText()
            assertTrue("the report header says smoke run", markdown.contains("Status: smoke run"))
            assertTrue("the report lists the shortfalls", markdown.contains("## Shortfalls"))
            assertTrue("the report.json marks the smoke run", File(output, "report.json").readText().contains("\"smoke\": true"))
        } finally {
            output.deleteRecursively()
        }
    }

    fun `test a smoke baseline is refused`() {
        // relationships/01-relationship-benchmark R32
        val directory = Files.createTempDirectory("relationship-smoke-baseline").toFile()
        try {
            val baseline = File(directory, "baseline.json").apply {
                writeText("""{"smoke":true,"wrongPairs":[],"precision":{},"recall":{}}""")
            }
            val result = caseResult(label("rename-1", "JAVA", "method-rename"), emptyList(), nodes())
            val metrics = RelationshipBenchmark.evaluate(listOf(result.label), listOf(result))
            val failures = RelationshipBenchmark.gateFailures(
                "no-new-wrong-pairs", metrics, mapOf("java" to metrics), baseline, 0.0, listOf(result),
            )
            assertTrue(failures.any { it.contains("baseline is a smoke run") })
        } finally {
            directory.deleteRecursively()
        }
    }

    fun `test Scala 2 and Scala 3 are separate rows and minimums`() {
        // relationships/01-relationship-benchmark R33
        val scala2 = (1..3).map { label("case-s2-$it", "Scala", "method-rename", corpus = "scala2") }
        val scala3 = (1..3).map { label("case-s3-$it", "Scala", "method-rename", corpus = "scala3") }
        val labels = scala2 + scala3
        assertTrue(
            RelationshipBenchmark.matrixFailures(
                labels, listOf("scala2", "scala3"), listOf("method-rename"),
                labelledCommits = mapOf("scala2" to 30, "scala3" to 30),
            ).isEmpty(),
        )
        val byCorpus = labels.map { caseResult(it, emptyList(), emptyList()) }
            .groupBy { it.label.corpus }
            .mapValues { RelationshipBenchmark.evaluate(it.value.map { row -> row.label }, it.value) }
        assertEquals(setOf("scala2", "scala3"), byCorpus.keys)
        val short = RelationshipBenchmark.matrixFailures(
            labels.dropLast(1), listOf("scala2", "scala3"), listOf("method-rename"),
            labelledCommits = mapOf("scala2" to 30, "scala3" to 30),
        )
        assertEquals(listOf("scala3"), short.map { it.corpus })
    }

    fun `test a real commit line compares every selected file with its first parent`() {
        // relationships/01-relationship-benchmark R34
        val repository = gitRepository()
        try {
            val report = runRepository(repository)
            assertEquals("the labelled file is scored", 1, report.cases.size)
            assertEquals("the unlabelled file is run and listed", "B.java", report.unlabelled.single().path)
            assertTrue("neither file was skipped", report.skippedFiles.isEmpty())
            assertEquals("a commit with an unlabelled file is not a labelled commit", 0, report.labelledCommits["java"] ?: 0)
        } finally {
            repository.root.deleteRecursively()
        }
    }

    fun `test a real commit label names repository commit and path`() {
        // relationships/01-relationship-benchmark R35
        val repository = gitRepository()
        try {
            val file = writeCommitLabel(repository, "A", reviewed = true)
            val label = RelationshipBenchmark.parseLabel(file)
            assertEquals("sample-repo", label.source!!.repository)
            assertEquals(repository.commit, label.source!!.commit)
            assertEquals("A.java", label.source!!.path)

            // The repository is matched by the clone folder's name: a copy under another name finds no label.
            val copy = File(repository.root, "other-clone")
            repository.repository.copyRecursively(copy)
            val copied = runRepository(repository, clone = copy)
            assertEquals("no label matches another clone's folder", 0, copied.cases.size)
            assertEquals(2, copied.unlabelled.size)

            // A label whose commit is not in the clone fails with the label's path.
            val orphan = writeCommitLabel(repository, "A", reviewed = true, repositoryName = "other-clone", short = "deadbee", commit = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
            val error = try {
                runRepository(repository, clone = copy)
                null
            } catch (e: IllegalStateException) {
                e
            }
            assertNotNull("a missing commit fails the run", error)
            assertTrue("the failure names the label path", error!!.message.orEmpty().contains(orphan.path))
        } finally {
            repository.root.deleteRecursively()
        }
    }

    fun `test an unlabelled real commit file is counted and listed and not scored`() {
        // relationships/01-relationship-benchmark R36
        val repository = gitRepository()
        try {
            val report = runRepository(repository)
            assertEquals(1, report.cases.size)
            val unlabelled = report.unlabelled.single()
            assertEquals("B.java", unlabelled.path)
            assertEquals("java", unlabelled.corpus)
            assertEquals("no label", unlabelled.reason)
            val markdown = File(repository.root, "output/report.md").readText()
            assertTrue("the report.md lists the unlabelled file", markdown.contains("B.java"))
            val json = File(repository.root, "output/report.json").readText()
            assertTrue("the report.json lists the unlabelled file", json.contains("\"unlabelled\""))
            assertTrue(json.contains("B.java"))
        } finally {
            repository.root.deleteRecursively()
        }
    }

    fun `test drafts are reviewed false and not scored until reviewed`() {
        // relationships/01-relationship-benchmark R37
        val repository = gitRepository()
        try {
            val drafts = File(repository.root, "drafts")
            val first = runRepository(repository, drafts = drafts)
            val draft = File(drafts, "java/relationships/commits/sample-repo/${repository.short}/B.yaml")
            assertTrue("a draft is written for the unlabelled file", draft.isFile)
            assertTrue("a draft is not reviewed", draft.readText().contains("reviewed: false"))
            assertEquals("a draft is not scored", 1, first.cases.size)
            assertEquals(1, first.unlabelled.size)

            val target = File(repository.labels, "java/relationships/commits/sample-repo/${repository.short}/B.yaml")
            target.parentFile.mkdirs()
            draft.copyTo(target, overwrite = true)
            val second = runRepository(repository)
            assertEquals("an unreviewed label is not scored", 1, second.cases.size)
            assertEquals(1, second.unlabelled.size)

            target.writeText(target.readText().replace("reviewed: false", "reviewed: true"))
            val third = runRepository(repository)
            assertEquals("a reviewed label is scored", 2, third.cases.size)
            assertTrue("nothing is unlabelled once the draft is reviewed", third.unlabelled.isEmpty())
        } finally {
            repository.root.deleteRecursively()
        }
    }

    fun `test a reported relationship is matched per kind`() {
        // relationships/01-relationship-benchmark R38
        val both = label("move-rename", "JAVA", "move-with-rename").copy(
            relationships = listOf(
                RelationshipBenchmark.LabelledRelationship(
                    setOf(RelationshipBenchmark.Kind.RENAME, RelationshipBenchmark.Kind.MOVE), old("total"), new("sum"), null,
                ),
            ),
        )
        val two = listOf(
            actual(RelationshipBenchmark.Kind.RENAME, old("total"), new("sum")),
            actual(RelationshipBenchmark.Kind.MOVE, old("total"), new("sum")),
        )
        val bothMetrics = RelationshipBenchmark.evaluate(listOf(both), listOf(caseResult(both, two, nodes())))
        assertEquals(1, bothMetrics.perKind.getValue("RENAME").tp)
        assertEquals(1, bothMetrics.perKind.getValue("MOVE").tp)
        assertEquals(0, bothMetrics.perKind.values.sumOf { it.fp })
        assertEquals(0, bothMetrics.perKind.values.sumOf { it.fn })

        val renameOnly = both.copy(relationships = listOf(RelationshipBenchmark.LabelledRelationship(setOf(RelationshipBenchmark.Kind.RENAME), old("total"), new("sum"), null)))
        val oneKind = RelationshipBenchmark.evaluate(listOf(renameOnly), listOf(caseResult(renameOnly, two, nodes())))
        assertEquals(1, oneKind.perKind.getValue("RENAME").tp)
        assertEquals(1, oneKind.perKind.getValue("MOVE").fp)
        assertEquals(0, oneKind.perKind.getValue("RENAME").fn)

        val moveOnly = both.copy(relationships = listOf(RelationshipBenchmark.LabelledRelationship(setOf(RelationshipBenchmark.Kind.MOVE), old("total"), new("sum"), null)))
        val otherKind = RelationshipBenchmark.evaluate(listOf(moveOnly), listOf(caseResult(moveOnly, listOf(two.first()), nodes())))
        assertEquals(1, otherKind.perKind.getValue("MOVE").fn)
        assertEquals(1, otherKind.perKind.getValue("RENAME").fp)
    }

    fun `test a forced pair stays out of precision`() {
        // relationships/01-relationship-benchmark R39
        val ambiguous = label("ambiguous-1", "JAVA", "ambiguous-rename-group").copy(relationships = emptyList())
        val result = caseResult(ambiguous, listOf(actual(RelationshipBenchmark.Kind.RENAME, old("ambiguous"), new("other"))), nodes())
        val metrics = RelationshipBenchmark.evaluate(listOf(ambiguous), listOf(result))
        assertEquals(1, metrics.forcedPairs)
        assertEquals(0, metrics.perKind.values.sumOf { it.fp })
        assertEquals(1.0, metrics.precision, 0.0)
        assertTrue(RelationshipBenchmark.forcedPairs(listOf(result)).isNotEmpty())
        assertTrue(RelationshipBenchmark.wrongPairs(listOf(result)).isEmpty())
    }

    fun `test a missing baseline path fails the run`() {
        // relationships/01-relationship-benchmark R40
        val result = caseResult(label("rename-1", "JAVA", "method-rename"), emptyList(), nodes())
        val metrics = RelationshipBenchmark.evaluate(listOf(result.label), listOf(result))
        val failures = RelationshipBenchmark.gateFailures(
            "no-new-wrong-pairs", metrics, mapOf("java" to metrics),
            File("/tmp/no-such-relationship-benchmark.json"), 0.0, listOf(result),
        )
        assertTrue(failures.any { it == "baseline not found: /tmp/no-such-relationship-benchmark.json" })
    }

    fun `test gates compare each corpus and kind and list new pairs`() {
        // relationships/01-relationship-benchmark R41
        val directory = Files.createTempDirectory("relationship-kind-gate").toFile()
        try {
            val baseline = File(directory, "baseline.json").apply {
                writeText("""{"smoke":false,"wrongPairs":[],"precision":{"java|RENAME":0.5},"recall":{"java|RENAME":0.5}}""")
            }
            val java = label("java-1", "JAVA", "method-rename")
            val kotlin = label("kotlin-1", "kotlin", "method-rename")
            val javaCase = caseResult(java, listOf(actual(RelationshipBenchmark.Kind.RENAME, old("total"), new("sum"))), nodes())
            val kotlinCase = caseResult(kotlin, listOf(actual(RelationshipBenchmark.Kind.RENAME, old("total"), new("sum"))), nodes())
            val javaMetrics = RelationshipBenchmark.evaluate(listOf(java), listOf(javaCase))
            val kotlinMetrics = RelationshipBenchmark.evaluate(listOf(kotlin), listOf(kotlinCase))
            val byCorpus = mapOf("java" to javaMetrics, "kotlin" to kotlinMetrics)
            assertTrue(
                "a pair the baseline lacks does not fail the gate",
                RelationshipBenchmark.gateFailures("precision", javaMetrics, byCorpus, baseline, 0.0, listOf(javaCase, kotlinCase)).isEmpty(),
            )
            assertEquals(listOf("new in this run: kotlin|RENAME"), RelationshipBenchmark.baselineGaps(byCorpus, baseline))

            val high = File(directory, "high.json").apply {
                writeText("""{"smoke":false,"wrongPairs":[],"precision":{"java|RENAME":0.9},"recall":{"java|RENAME":0.5}}""")
            }
            val withWrongPair = caseResult(
                java,
                listOf(
                    actual(RelationshipBenchmark.Kind.RENAME, old("total"), new("sum")),
                    actual(RelationshipBenchmark.Kind.RENAME, old("other"), new("wrong")),
                ),
                nodes(),
            )
            val lower = RelationshipBenchmark.evaluate(listOf(java), listOf(withWrongPair))
            assertTrue(
                "a lower precision in one pair fails",
                RelationshipBenchmark.gateFailures("precision", lower, mapOf("java" to lower), high, 0.0, listOf(withWrongPair))
                    .any { it.contains("precision fell for java|RENAME") },
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    fun `test a wrong classification is listed with both values`() {
        // relationships/01-relationship-benchmark R42
        val wrong = label("rename-1", "JAVA", "method-rename").copy(
            changes = listOf(RelationshipBenchmark.ExpectedChange(endpoint("sum"), Presence.ADDED, Content.CHANGED)),
        )
        val result = caseResult(wrong, emptyList(), nodes())
        val mismatch = RelationshipBenchmark.classificationMismatches(listOf(result)).single()
        assertTrue("the expected values are listed", mismatch.contains("expected ADDED/CHANGED"))
        assertTrue("the actual values are listed", mismatch.contains("actual MATCHED/UNCHANGED"))
    }

    fun `test editing a label changes the corpus revision`() {
        // relationships/01-relationship-benchmark R43
        val directory = Files.createTempDirectory("relationship-revision").toFile()
        try {
            val case = File(directory, "case").apply { mkdirs() }
            File(case, "old.java").writeText("class Order { int total() { return 1; } }\n")
            File(case, "new.java").writeText("class Order { int sum() { return 1; } }\n")
            File(case, "label.yaml").writeText(LABEL)
            val corpus = File(directory, "corpus.txt").apply { writeText(case.absolutePath + "\n") }
            val before = RelationshipBenchmark.corpusRevision(corpus)
            File(case, "label.yaml").appendText("# edited\n")
            val after = RelationshipBenchmark.corpusRevision(corpus)
            assertFalse("editing a label changes the revision", before == after)
            File(case, "old.java").appendText("\n")
            assertFalse("editing a case file changes the revision", after == RelationshipBenchmark.corpusRevision(corpus))
        } finally {
            directory.deleteRecursively()
        }
    }

    private class TestRepository(
        val root: File,
        val repository: File,
        val commit: String,
        val short: String,
        val labels: File,
        val corpus: File,
        val output: File,
    )

    /** A clone with one commit that changes two Java files, so one file can be labelled and one left unlabelled. */
    private fun gitRepository(): TestRepository {
        val root = Files.createTempDirectory("relationship-git").toFile()
        val repository = File(root, "sample-repo").apply { mkdirs() }
        git(repository, "init", "-q")
        git(repository, "config", "user.email", "test@example.com")
        git(repository, "config", "user.name", "SMART Diff test")
        git(repository, "config", "commit.gpgsign", "false")
        File(repository, "A.java").writeText("class A {\n  int total() { return 1; }\n}\n")
        File(repository, "B.java").writeText("class B {\n  int first() { return 1; }\n}\n")
        git(repository, "add", ".")
        git(repository, "commit", "-q", "-m", "initial")
        File(repository, "A.java").writeText("class A {\n  int sum() { return 1; }\n}\n")
        File(repository, "B.java").writeText("class B {\n  int second() { return 1; }\n}\n")
        git(repository, "commit", "-q", "-am", "change both")
        val commit = git(repository, "rev-parse", "HEAD").trim()
        val labels = File(root, "labels")
        val corpus = File(root, "corpus.txt").apply { writeText("${repository.absolutePath} --max-count=1 HEAD\n") }
        val output = File(root, "output")
        val test = TestRepository(root, repository, commit, commit.take(7), labels, corpus, output)
        writeCommitLabel(test, "A", reviewed = true)
        return test
    }

    private fun writeCommitLabel(
        test: TestRepository,
        name: String,
        reviewed: Boolean,
        repositoryName: String = "sample-repo",
        short: String = test.short,
        commit: String = test.commit,
    ): File {
        val file = File(test.labels, "java/relationships/commits/$repositoryName/$short/$name.yaml")
        file.parentFile.mkdirs()
        file.writeText(
            """case: $repositoryName-$short-$name
language: JAVA
corpus: java
category: real-commit
split: held-out
reviewed: $reviewed
source:
  repository: $repositoryName
  commit: $commit
  path: $name.java
expected:
  relationships:
    - kinds: [RENAME]
      old: { path: "class $name", kind: METHOD, name: total, signature: "()" }
      new: { path: "class $name", kind: METHOD, name: sum, signature: "()" }
  changes: []
  ambiguous: []
notes: "Built inside the test."
""",
        )
        return file
    }

    private fun runRepository(test: TestRepository, clone: File = test.repository, drafts: File? = null) =
        RelationshipBenchmark.run(
            corpus = if (clone == test.repository) test.corpus else File(test.root, "copy-corpus.txt").apply { writeText("${clone.absolutePath} --max-count=1 HEAD\n") },
            output = test.output,
            labelRoot = test.labels,
            drafts = drafts,
            minimum = 0,
            commitMinimum = 0,
        )

    private fun git(repository: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.absolutePath) + arguments)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed in $repository" }
        return output
    }

    private fun label(
        case: String,
        language: String,
        category: String,
        split: RelationshipBenchmark.Split = RelationshipBenchmark.Split.TUNING,
        corpus: String = language.lowercase(),
        reviewed: Boolean = true,
    ) = RelationshipBenchmark.Label(
        case, language, split,
        listOf(RelationshipBenchmark.LabelledRelationship(setOf(RelationshipBenchmark.Kind.RENAME), old("total"), new("sum"), null)),
        listOf(RelationshipBenchmark.ExpectedChange(endpoint("sum"), Presence.MATCHED, Content.UNCHANGED)),
        listOf(old("ambiguous")),
        null,
        category,
        corpus,
        reviewed,
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
corpus: java
category: method-rename
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
