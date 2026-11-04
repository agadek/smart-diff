package io.github.agadek.smartdiff.relationships

import com.intellij.lang.Language
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Confidence as EngineConfidence
import io.github.agadek.smartdiff.engine.api.Origin
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.RelationshipEndpoint
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.FileRegion
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationProvider
import io.github.agadek.smartdiff.engine.declarations.psi.java.JavaDeclarationProvider
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
            assertEquals("every checked-in case runs", checkedInCaseFolders().size, report.cases.size)
            assertTrue("the case-only list is a smoke run because it has no real commits", report.smoke)
            assertTrue(File(output, "summary.tsv").isFile)
            assertTrue(File(output, "report.md").isFile)
            assertTrue(File(output, "report.json").isFile)
        } finally {
            output.deleteRecursively()
        }
    }

    fun `test the checked-in case matrix meets every minimum`() {
        // relationships/01-relationship-benchmark R8, R9, R30
        val entries = RelationshipBenchmark.parseCorpus(File("src/test/testData/relationships-corpus.txt"))
        val labels = entries.mapNotNull { it.path }.map { RelationshipBenchmark.parseLabel(File(it, "label.yaml")) }
        val checkedIn = checkedInCaseFolders().map { it.absoluteFile.normalize() }.toSet()
        val listed = entries.mapNotNull { it.path }.map { it.absoluteFile.normalize() }.toSet()
        assertEquals("every checked-in case folder is in the list", checkedIn, listed)
        assertTrue("every case is reviewed", labels.all { it.reviewed })
        val failures = RelationshipBenchmark.matrixFailures(labels, labelledCommits = emptyMap(), commitMinimum = 0)
        assertTrue("shortfalls: $failures", failures.isEmpty())
        for ((corpus, categories) in RelationshipBenchmark.NOT_APPLICABLE) {
            for ((category, reason) in categories) {
                assertTrue("$corpus $category needs a reason", reason.isNotBlank())
            }
        }
    }

    fun `test the full corpus list names the real commits without absolute paths`() {
        // relationships/01-relationship-benchmark R10, R30
        val file = File("src/test/testData/relationships-corpus-full.txt")
        val text = file.readText()
        assertFalse("no absolute path names a user", text.contains("/Users/"))
        val entries = RelationshipBenchmark.parseCorpus(file)
        assertEquals("all checked-in cases are listed", checkedInCaseFolders().size, entries.count { it.path != null })
        val commits = entries.filter { it.repository != null }
        assertTrue("the full list names the real-commit clones", commits.size >= 4)
        val hashes = commits.flatMap { line -> Regex("[0-9a-f]{40}").findAll(line.line).map { it.value }.toList() }
        val folders = checkedInCommitFolders()
        assertEquals("every checked-in labelled commit is in the list, once", folders.size, hashes.distinct().size)
        for (folder in folders) {
            assertTrue(
                "the list names the commit folder ${folder.parentFile.name}/${folder.name}",
                hashes.any { it.startsWith(folder.name) },
            )
        }
    }

    fun `test the checked-in real commit labels meet the commit minimum`() {
        // relationships/01-relationship-benchmark R10, R35, R36, R37
        for (corpus in RelationshipBenchmark.CORPORA) {
            val directory = File("src/test/testData/$corpus/relationships/commits")
            val commits = directory.listFiles().orEmpty().filter { it.isDirectory }
                .flatMap { repository -> repository.listFiles().orEmpty().filter { it.isDirectory } }
            assertTrue("$corpus has ${commits.size} labelled commits", commits.size >= RelationshipBenchmark.MINIMUM_COMMITS)
            for (commit in commits) {
                for (file in commit.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".yaml") }) {
                    val label = RelationshipBenchmark.parseLabel(file)
                    assertTrue("${file.path} is reviewed", label.reviewed)
                    assertNotNull("${file.path} names its commit", label.source)
                    assertEquals(corpus, label.corpus)
                }
            }
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

    fun `test a renamed move names its old subject`() {
        // relationships/02-relationship-model R31
        val oldSubject = Subject.Declaration(DeclarationKind.METHOD, "total", "()")
        val newSubject = Subject.Declaration(DeclarationKind.METHOD, "sum", "()")
        val result = ComparisonResult(
            EngineId("test"), "1", Status.ANALYZED,
            changes = listOf(ChangeNode(newSubject, TextRange(0, 5), TextRange(6, 11), Presence.MATCHED, moved = true)),
            relationships = listOf(
                relationship(
                    java.util.EnumSet.of(RelationshipKind.RENAME, RelationshipKind.MOVE),
                    oldSubject, TextRange(0, 5), newSubject, TextRange(6, 11),
                ),
            ),
        )
        val relationship = RelationshipBenchmark.reportedRelationships(result)
        assertEquals(setOf(RelationshipBenchmark.Kind.RENAME, RelationshipBenchmark.Kind.MOVE), relationship.map { it.kind }.toSet())
        assertEquals("total", relationship.single { it.kind == RelationshipBenchmark.Kind.MOVE }.old.name)
    }

    fun `test an extraction link is counted once`() {
        // relationships/02-relationship-model R31; relationships/01-relationship-benchmark R38
        val source = Subject.Declaration(DeclarationKind.METHOD, "render", "(String)")
        val target = Subject.Declaration(DeclarationKind.METHOD, "renderLines", "(String)")
        val sourceNode = ChangeNode(source, TextRange(0, 5), TextRange(6, 11), Presence.MATCHED)
        val targetNode = ChangeNode(target, null, TextRange(12, 20), Presence.ADDED)
        val extraction = RelationshipBenchmark.reportedRelationships(
            ComparisonResult(
                EngineId("test"), "1", Status.ANALYZED,
                changes = listOf(sourceNode, targetNode),
                relationships = listOf(
                    relationship(java.util.EnumSet.of(RelationshipKind.EXTRACT), source, TextRange(0, 5), target, TextRange(12, 20)),
                ),
            ),
        )
        assertEquals(1, extraction.count { it.kind == RelationshipBenchmark.Kind.EXTRACT })
    }

    fun `test an added overload is found by presence not identity alone`() {
        // relationships/01-relationship-benchmark R42
        val subject = Subject.Declaration(DeclarationKind.METHOD, "read", "()")
        val changed = ChangeNode(subject, TextRange(0, 5), TextRange(6, 11), Presence.MATCHED)
        val added = ChangeNode(subject, null, TextRange(12, 18), Presence.ADDED)
        val label = label("overload-1", "JAVA", "overloads-same-body").copy(
            relationships = emptyList(),
            changes = listOf(RelationshipBenchmark.ExpectedChange(endpoint("read"), Presence.ADDED, Content.UNCHANGED)),
            ambiguous = emptyList(),
        )
        val result = RelationshipBenchmark.CaseResult(
            label, ComparisonResult(EngineId("test"), "1", Status.ANALYZED),
            emptyList(), listOf(endpoint("read") to changed, endpoint("read") to added),
            1, false,
        )
        val metrics = RelationshipBenchmark.evaluate(listOf(label), listOf(result))
        assertEquals(1, metrics.presenceCorrect)
        assertTrue(RelationshipBenchmark.classificationMismatches(listOf(result)).isEmpty())
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
            assertTrue("wrong and forced pairs have their own heading", markdown.contains("## Wrong and forced relationships"))
            assertFalse("the old heading is gone", markdown.contains("## Wrong and missed relationships"))
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

    fun `test the validation section records the R29 baseline`() {
        // relationships/01-relationship-benchmark R29
        val validation = File("docs/VALIDATION.md").readText()
        assertTrue("the baseline section", validation.contains("## 2026-11-02: relationship benchmark baseline"))
        assertTrue("the run has no shortfall", validation.contains("no shortfall"))
        assertTrue("the corpus list is named", validation.contains("relationships-corpus-full.txt"))
        assertTrue("the checked-in baseline report is named", validation.contains("src/test/testData/relationships-baseline/report.json"))
        assertTrue("the report paths are named", validation.contains("build/reports/smartdiff-relationships/summary.tsv"))
        assertTrue("the old smoke run is linked", validation.contains("(#2026-11-01-relationship-benchmark)"))
    }

    fun `test the checked-in baseline report is complete and matches the recorded revision`() {
        // relationships/01-relationship-benchmark R20, R29, R32
        val file = File("src/test/testData/relationships-baseline/report.json")
        assertTrue("the baseline report is checked in", file.isFile)
        val json = file.readText()
        assertTrue("the baseline is not a smoke run", json.contains("\"smoke\": false"))
        assertTrue("the baseline has no shortfall", json.contains("\"shortfalls\": []"))
        val revision = Regex("\"corpusRevision\":\\s*\"([0-9a-f]+)\"").find(json)
        assertNotNull("the baseline records the corpus revision", revision)
        assertTrue(
            "VALIDATION.md records the baseline revision ${revision!!.groupValues[1]}",
            File("docs/VALIDATION.md").readText().contains(revision.groupValues[1]),
        )
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

    fun `test a smoke baseline report is refused`() {
        // relationships/01-relationship-benchmark R30, R31, R32
        val output = Files.createTempDirectory("relationship-smoke-baseline").toFile()
        try {
            val report = RelationshipBenchmark.run(File("src/test/testData/relationships-corpus.txt"), output)
            assertTrue("the case-only corpus is below the minimums", report.smoke)
            // The baseline is the smoke report.json the run itself produced, not a hand-written file.
            val baseline = File(output, "report.json")
            assertTrue(baseline.isFile)
            val result = caseResult(label("rename-1", "JAVA", "method-rename"), emptyList(), nodes())
            val metrics = RelationshipBenchmark.evaluate(listOf(result.label), listOf(result))
            val failures = RelationshipBenchmark.gateFailures(
                "no-new-wrong-pairs", metrics, mapOf("java" to metrics), baseline, 0.0, listOf(result),
            )
            assertTrue(
                "the produced smoke report is refused",
                failures.any { it == "baseline is a smoke run: ${baseline.path}" },
            )
        } finally {
            output.deleteRecursively()
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

    fun `test editing a real commit label changes the corpus revision`() {
        // relationships/01-relationship-benchmark R43
        val repository = gitRepository()
        try {
            val before = RelationshipBenchmark.corpusRevision(repository.corpus, repository.labels)
            val label = File(repository.labels, "java/relationships/commits/sample-repo/${repository.short}/A.yaml")
            assertTrue(label.isFile)
            label.appendText("# edited\n")
            assertFalse("editing a real-commit label changes the revision", before == RelationshipBenchmark.corpusRevision(repository.corpus, repository.labels))
        } finally {
            repository.root.deleteRecursively()
        }
    }

    fun `test moving a clone's HEAD changes the corpus revision`() {
        // relationships/01-relationship-benchmark R43
        val repository = gitRepository()
        try {
            val before = RelationshipBenchmark.corpusRevision(repository.corpus, repository.labels)
            git(repository.repository, "commit", "--allow-empty", "-q", "-m", "move head")
            assertFalse("moving a clone HEAD changes the revision", before == RelationshipBenchmark.corpusRevision(repository.corpus, repository.labels))
        } finally {
            repository.root.deleteRecursively()
        }
    }

    fun `test two same-identity changes cannot claim one node`() {
        // relationships/01-relationship-benchmark R15, R42
        val subject = Subject.Declaration(DeclarationKind.METHOD, "read", "()")
        val changed = ChangeNode(subject, TextRange(0, 5), TextRange(6, 11), Presence.MATCHED)
        val added = ChangeNode(subject, null, TextRange(12, 18), Presence.ADDED)
        val expected = RelationshipBenchmark.ExpectedChange(endpoint("read"), Presence.ADDED, Content.UNCHANGED)
        val label = label("overload-1", "JAVA", "overloads-same-body").copy(
            relationships = emptyList(),
            changes = listOf(expected, expected),
            ambiguous = emptyList(),
        )
        val result = RelationshipBenchmark.CaseResult(
            label, ComparisonResult(EngineId("test"), "1", Status.ANALYZED),
            emptyList(), listOf(endpoint("read") to changed, endpoint("read") to added),
            1, false,
        )
        val metrics = RelationshipBenchmark.evaluate(listOf(label), listOf(result))
        assertEquals("the added node is claimed once and the matched node scores as wrong", 1, metrics.presenceCorrect)
        assertEquals(2, metrics.presenceTotal)
        assertEquals("the second change is listed as a mismatch", 1, RelationshipBenchmark.classificationMismatches(listOf(result)).size)
    }

    fun `test R31 the benchmark reads the result relationships`() {
        // relationships/02-relationship-model R31
        val old = Subject.Declaration(DeclarationKind.METHOD, "total", "()")
        val new = Subject.Declaration(DeclarationKind.METHOD, "sum", "()")
        // The nodes carry none of the deprecated fields: the relationship is the only source.
        val renamed = ChangeNode(new, TextRange(0, 5), TextRange(6, 11), Presence.MATCHED)
        val result = ComparisonResult(
            EngineId("test"), "1", Status.ANALYZED,
            changes = listOf(renamed),
            relationships = listOf(
                relationship(java.util.EnumSet.of(RelationshipKind.RENAME), old, TextRange(0, 5), new, TextRange(6, 11)),
            ),
        )

        val reported = RelationshipBenchmark.reportedRelationships(result).single()

        assertEquals(RelationshipBenchmark.Kind.RENAME, reported.kind)
        assertEquals("total", reported.old.name)
        assertEquals("sum", reported.new.name)
        assertEquals(TextRange(0, 5), reported.old.range)
        assertEquals(RelationshipBenchmark.Confidence.HIGH, reported.confidence)
    }

    fun `test R32 a repeated relationship is a wrong pair and fails the gate`() {
        // relationships/02-relationship-model R32
        val directory = Files.createTempDirectory("relationship-repeat").toFile()
        try {
            val expected = label("rename-1", "JAVA", "method-rename")
            val reported = actual(RelationshipBenchmark.Kind.RENAME, old("total"), new("sum"))
            val result = caseResult(expected, listOf(reported, reported), nodes())
            val metrics = RelationshipBenchmark.evaluate(listOf(expected), listOf(result))

            assertEquals("the label claims one pair", 1, metrics.perKind.getValue("RENAME").tp)
            assertEquals("the repeated pair is a false positive", 1, metrics.perKind.getValue("RENAME").fp)
            assertTrue(RelationshipBenchmark.wrongPairs(listOf(result)).isNotEmpty())
            val baseline = File(directory, "baseline.json").apply {
                writeText("""{"smoke":false,"wrongPairs":[],"precision":{"java|RENAME":1.0},"recall":{"java|RENAME":1.0}}""")
            }
            val failures = RelationshipBenchmark.gateFailures(
                "no-new-wrong-pairs", metrics, mapOf("java" to metrics), baseline, 0.0, listOf(result),
            )
            assertTrue(failures.toString(), failures.any { it.contains("new wrong or forced pairs") })
        } finally {
            directory.deleteRecursively()
        }
    }

    fun `test R33 both reports count the reported relationships of each kind by confidence`() {
        // relationships/02-relationship-model R33
        val directory = Files.createTempDirectory("relationship-confidence").toFile()
        try {
            val expected = label("rename-1", "JAVA", "method-rename")
            val high = actual(RelationshipBenchmark.Kind.RENAME, old("total"), new("sum"))
            val medium = high.copy(confidence = RelationshipBenchmark.Confidence.MEDIUM)
            val result = caseResult(expected, listOf(high, medium), nodes())
            val metrics = RelationshipBenchmark.evaluate(listOf(expected), listOf(result))
            assertEquals(mapOf(RelationshipBenchmark.Confidence.HIGH to 1, RelationshipBenchmark.Confidence.MEDIUM to 1), metrics.confidenceByKind.getValue("RENAME"))

            val report = RelationshipBenchmark.Report(
                listOf(result), mapOf("java" to metrics), mapOf(RelationshipBenchmark.Split.TUNING to metrics), metrics,
                "no-new-wrong-pairs", emptyList(), "abc", directory,
            )
            RelationshipBenchmark.write(report)

            val markdown = File(directory, "report.md").readText()
            assertTrue(markdown.contains("## Confidence counts"))
            assertTrue(markdown.contains("| java | RENAME | 1 | 1 | 0 |"))
            val json = File(directory, "report.json").readText()
            assertTrue(json.contains("\"confidencePolicy\""))
            assertTrue(json.contains("\"HIGH\": 1"))
            assertTrue(json.contains("\"MEDIUM\": 1"))
            assertTrue(json.contains("\"LOW\": 0"))
        } finally {
            directory.deleteRecursively()
        }
    }

    fun `test a differing confidence does not change scoring`() {
        // relationships/01-relationship-benchmark-details.md: confidence is not compared until the engine reports one
        fun metricsFor(confidence: RelationshipBenchmark.Confidence?): RelationshipBenchmark.Metrics {
            val expected = RelationshipBenchmark.LabelledRelationship(
                setOf(RelationshipBenchmark.Kind.RENAME), old("total"), new("sum"), confidence,
            )
            val label = label("rename-1", "JAVA", "method-rename").copy(relationships = listOf(expected))
            val actual = RelationshipBenchmark.ActualRelationship(
                RelationshipBenchmark.Kind.RENAME, old("total"), new("sum"), RelationshipBenchmark.Confidence.MEDIUM,
            )
            val result = caseResult(label, listOf(actual), nodes())
            return RelationshipBenchmark.evaluate(listOf(label), listOf(result))
        }
        val withConfidence = metricsFor(RelationshipBenchmark.Confidence.HIGH)
        val without = metricsFor(null)
        assertEquals(1, withConfidence.perKind.getValue("RENAME").tp)
        assertEquals(0, withConfidence.perKind.getValue("RENAME").fp)
        assertEquals(0, withConfidence.perKind.getValue("RENAME").fn)
        assertEquals(without.perKind.getValue("RENAME").tp, withConfidence.perKind.getValue("RENAME").tp)
        assertEquals(without.perKind.getValue("RENAME").fp, withConfidence.perKind.getValue("RENAME").fp)
    }

    fun `test a cross-container move names its old container`() {
        // relationships/02-relationship-model R31
        val method = Declaration(DeclarationKind.METHOD, "replicateA_", "Int", TextRange(10, 20))
        val oldTree = DeclarationTree(
            listOf(Declaration(DeclarationKind.CLASS, "ApplicativeOps", null, TextRange(0, 100), children = listOf(method))),
            FileRegion(emptyList(), emptyList()),
            WhitespacePolicy.INSIGNIFICANT,
        )
        val moved = ChangeNode(
            Subject.Declaration(DeclarationKind.METHOD, "replicateA_", "Int"),
            TextRange(10, 20), TextRange(30, 40), Presence.MATCHED, moved = true,
        )
        val result = ComparisonResult(
            EngineId("test"), "1", Status.ANALYZED,
            changes = listOf(
                ChangeNode(
                    Subject.Declaration(DeclarationKind.CLASS, "ApplicativeByValueOps", null),
                    TextRange(0, 100), TextRange(0, 100), Presence.MATCHED, children = listOf(moved),
                ),
            ),
            relationships = listOf(
                relationship(
                    java.util.EnumSet.of(RelationshipKind.MOVE),
                    Subject.Declaration(DeclarationKind.METHOD, "replicateA_", "Int"), TextRange(10, 20),
                    Subject.Declaration(DeclarationKind.METHOD, "replicateA_", "Int"), TextRange(30, 40),
                ),
            ),
        )
        val fallback = RelationshipBenchmark.reportedRelationships(result).single { it.kind == RelationshipBenchmark.Kind.MOVE }
        assertEquals("without the old tree the new container is used", "class ApplicativeByValueOps", fallback.old.path)
        val relationship = RelationshipBenchmark.reportedRelationships(result, oldTree).single { it.kind == RelationshipBenchmark.Kind.MOVE }
        assertEquals("the old container comes from the old tree", "class ApplicativeOps", relationship.old.path)
        assertEquals("class ApplicativeByValueOps", relationship.new.path)
    }

    fun `test a throwing second parse falls back to the new side path`() {
        // relationships/01-relationship-benchmark R26; details: Before the relationship model exists (the adapter)
        ExtensionTestUtil.addExtensions(DeclarationProvider.EP_NAME, listOf(ThrowingTreeProvider()), testRootDisposable)
        val directory = Files.createTempDirectory("relationship-old-tree").toFile()
        try {
            File(directory, "old.scala").writeText("// old\n")
            File(directory, "new.scala").writeText("// new\n")
            File(directory, "label.yaml").writeText(THROWING_LABEL)
            val corpus = File(directory, "corpus.txt").apply { writeText(directory.absolutePath + "\n") }
            val report = RelationshipBenchmark.run(corpus, File(directory, "report"), minimum = 0, commitMinimum = 0)
            val case = report.cases.single()
            assertNull("a throwing second parse does not abort the run", case.exception)
            val move = case.actualRelationships.single { it.kind == RelationshipBenchmark.Kind.MOVE }
            assertEquals("without the old tree the new container is used", "class B", move.old.path)
        } finally {
            directory.deleteRecursively()
        }
    }

    fun `test an extraction keeps the declaration ranges of both ends`() {
        // relationships/02-relationship-model R27
        val source = Subject.Declaration(DeclarationKind.METHOD, "render", "()")
        val target = Subject.Declaration(DeclarationKind.METHOD, "helper", "()")
        val sourceNode = ChangeNode(source, TextRange(0, 5), TextRange(6, 11), Presence.MATCHED)
        val targetNode = ChangeNode(target, null, TextRange(20, 25), Presence.ADDED)
        val link = RelationshipBenchmark.reportedRelationships(
            ComparisonResult(
                EngineId("test"), "1", Status.ANALYZED,
                changes = listOf(sourceNode, targetNode),
                relationships = listOf(
                    relationship(java.util.EnumSet.of(RelationshipKind.EXTRACT), source, TextRange(0, 5), target, TextRange(20, 25)),
                ),
            ),
        ).single { it.kind == RelationshipBenchmark.Kind.EXTRACT }
        assertEquals("the source's declaration range is kept", TextRange(0, 5), link.old.range)
        assertEquals("the target's declaration range is kept", TextRange(20, 25), link.new.range)
    }

    fun `test two extractions that differ only by range stay two links`() {
        // relationships/01-relationship-benchmark R6; relationships/02-relationship-model R27
        val source = Subject.Declaration(DeclarationKind.METHOD, "render", "()")
        val target = Subject.Declaration(DeclarationKind.METHOD, "helper", "()")
        fun targetNode(range: TextRange) = ChangeNode(target, null, range, Presence.ADDED)
        val links = RelationshipBenchmark.reportedRelationships(
            ComparisonResult(
                EngineId("test"), "1", Status.ANALYZED,
                changes = listOf(targetNode(TextRange(20, 25)), targetNode(TextRange(30, 35))),
                relationships = listOf(
                    relationship(java.util.EnumSet.of(RelationshipKind.EXTRACT), source, TextRange(0, 5), target, TextRange(20, 25)),
                    relationship(java.util.EnumSet.of(RelationshipKind.EXTRACT), source, TextRange(10, 15), target, TextRange(30, 35)),
                ),
            ),
        )
        assertEquals(2, links.count { it.kind == RelationshipBenchmark.Kind.EXTRACT })
    }

    fun `test a no-argument signature is one identity in every language`() {
        // relationships/01-relationship-benchmark-details.md: null, "" and "()" are the same signature identity
        val none = RelationshipBenchmark.Endpoint("class Order", DeclarationKind.METHOD, "size", null)
        assertTrue(none.sameIdentity(none.copy(signature = "")))
        assertTrue(none.copy(signature = "").sameIdentity(none.copy(signature = "()")))
        assertTrue(RelationshipBenchmark.endpointsMatch(none.copy(signature = "()", range = TextRange(0, 5)), none.copy(range = TextRange(0, 5))))
    }

    fun `test a renamed file is a delete plus an add, both skipped`() {
        // relationships/01-relationship-benchmark R34
        val repository = gitRepository(
            initial = mapOf("A.java" to java("A", "total"), "B.java" to java("B", "total")),
            changed = mapOf("A.java" to null, "C.java" to java("C", "sum"), "B.java" to java("B", "sum")),
            labelled = listOf("B.java"),
        )
        try {
            val report = runRepository(repository)
            assertEquals("the modified file is compared", 1, report.cases.size)
            assertTrue("the rename is listed as a delete", report.skippedFiles.any { it.endsWith("A.java: deleted") })
            assertTrue("the rename is listed as an add", report.skippedFiles.any { it.endsWith("C.java: added") })
            assertEquals(2, report.skippedFiles.size)
        } finally {
            repository.root.deleteRecursively()
        }
    }

    fun `test a fully labelled commit counts toward R10`() {
        // relationships/01-relationship-benchmark R10
        val repository = gitRepository(labelled = listOf("A.java", "B.java"))
        try {
            val report = runRepository(repository)
            assertEquals(2, report.cases.size)
            assertTrue(report.unlabelled.isEmpty())
            assertEquals("every modified file is labelled, so the commit counts", 1, report.labelledCommits["java"] ?: 0)
        } finally {
            repository.root.deleteRecursively()
        }
    }

    fun `test a commit with an added file still counts when its modified file is labelled`() {
        // relationships/01-relationship-benchmark R10
        val repository = gitRepository(
            initial = mapOf("A.java" to java("A", "total")),
            changed = mapOf("A.java" to java("A", "sum"), "C.java" to java("C", "sum")),
            labelled = listOf("A.java"),
        )
        try {
            val report = runRepository(repository)
            assertEquals(1, report.cases.size)
            assertTrue("the added file is listed as skipped", report.skippedFiles.single().endsWith("C.java: added"))
            assertEquals("the added file does not block the count", 1, report.labelledCommits["java"] ?: 0)
            assertTrue(report.unlabelled.isEmpty())
        } finally {
            repository.root.deleteRecursively()
        }
    }

    fun `test a non-ASCII path is read unquoted`() {
        // relationships/01-relationship-benchmark R34
        val repository = gitRepository(
            initial = mapOf("A.java" to java("A", "total"), "café.java" to java("Cafe", "total")),
            changed = mapOf("A.java" to java("A", "sum"), "café.java" to java("Cafe", "sum")),
            labelled = listOf("A.java"),
        )
        try {
            val report = runRepository(repository)
            assertEquals("café.java", report.unlabelled.single().path)
        } finally {
            repository.root.deleteRecursively()
        }
    }

    fun `test an unlabelled Scala file is filed under its dialect`() {
        // relationships/01-relationship-benchmark R33
        assertEquals("scala3", RelationshipBenchmark.scalaCorpusFor("Scala 3"))
        assertEquals("scala2", RelationshipBenchmark.scalaCorpusFor("Scala 2"))
        assertNull("an unknown dialect is not filed as Scala 2", RelationshipBenchmark.scalaCorpusFor(null))
        assertNull(RelationshipBenchmark.scalaCorpusFor(""))
        val repository = gitRepository(
            initial = mapOf("S.scala" to scala3("total")),
            changed = mapOf("S.scala" to scala3("sum")),
            labelled = emptyList(),
        )
        try {
            val report = runRepository(repository)
            val unlabelled = report.unlabelled.single()
            assertEquals("a Scala 3 file is counted under scala3", "scala3", unlabelled.corpus)
            assertEquals("no label", unlabelled.reason)
        } finally {
            repository.root.deleteRecursively()
        }
    }

    fun `test the report gives per category metrics and shows n a for nothing to divide`() {
        // relationships/01-relationship-benchmark R12, R20
        val directory = Files.createTempDirectory("relationship-category-report").toFile()
        try {
            val inline = RelationshipBenchmark.PairCounts(tp = 0, fp = 0, fn = 3)
            val metrics = RelationshipBenchmark.Metrics(mapOf("INLINE" to inline), 0, 0, 0, 0, 0, listOf(5))
            val report = RelationshipBenchmark.Report(
                emptyList(), mapOf("java" to metrics), mapOf(RelationshipBenchmark.Split.TUNING to metrics), metrics,
                "no-new-wrong-pairs", emptyList(), "abc", directory,
                metricsByCategory = mapOf("inlining" to metrics),
            )
            RelationshipBenchmark.write(report)
            val markdown = File(directory, "report.md").readText()
            assertTrue("the report has per-category rows", markdown.contains("## Per category"))
            assertTrue(markdown.contains("| inlining | n/a | 0.000 |"))
            assertTrue("a kind with no reported pair shows n/a", markdown.contains("| java | INLINE | 0 | 0 | 3 | n/a | 0.000 |"))
            val tsv = File(directory, "summary.tsv").readText()
            assertTrue("summary.tsv shows n/a", tsv.contains("java\tINLINE\t0\t0\t3\tn/a\t0.000"))
            val json = File(directory, "report.json").readText()
            assertTrue("report.json has per-category metrics", json.contains("\"perCategory\""))
        } finally {
            directory.deleteRecursively()
        }
    }

    fun `test a label path not in the commit fails with the label path`() {
        // relationships/01-relationship-benchmark R35
        val repository = gitRepository()
        try {
            val bad = writeCommitLabel(repository, "A", reviewed = true, path = "Missing.java")
            val error = runCatching { runRepository(repository) }.exceptionOrNull()
            assertNotNull("a missing path fails the run", error)
            assertTrue("the failure names the label path", error!!.message.orEmpty().contains(bad.path))
        } finally {
            repository.root.deleteRecursively()
        }
    }

    fun `test a commit with no first parent fails with the label path`() {
        // relationships/01-relationship-benchmark R35
        val repository = gitRepository()
        try {
            val bad = writeCommitLabel(
                repository, "A", reviewed = true,
                short = repository.rootCommit.take(7), commit = repository.rootCommit,
            )
            val error = runCatching { runRepository(repository) }.exceptionOrNull()
            assertNotNull("a root commit fails the run", error)
            assertTrue("the failure names the label path", error!!.message.orEmpty().contains("has no first parent"))
            assertTrue(error.message.orEmpty().contains(bad.path))
        } finally {
            repository.root.deleteRecursively()
        }
    }

    fun `test a label from another repository fails with the label path`() {
        // relationships/01-relationship-benchmark R35
        val repository = gitRepository()
        try {
            val bad = writeCommitLabel(repository, "A", reviewed = true, sourceRepository = "other-clone")
            val error = runCatching { runRepository(repository) }.exceptionOrNull()
            assertNotNull("a repository mismatch fails the run", error)
            assertTrue("the failure names the label path", error!!.message.orEmpty().contains(bad.path))
        } finally {
            repository.root.deleteRecursively()
        }
    }

    fun `test a label in the wrong corpus folder fails with the label path`() {
        // relationships/01-relationship-benchmark R35
        val repository = gitRepository()
        try {
            val bad = writeCommitLabel(repository, "A", reviewed = true, corpusFolder = "scala2", corpus = "java")
            val error = runCatching { runRepository(repository) }.exceptionOrNull()
            assertNotNull("a corpus mismatch fails the run", error)
            assertTrue("the failure names the label path", error!!.message.orEmpty().contains(bad.path))
        } finally {
            repository.root.deleteRecursively()
        }
    }

    private class TestRepository(
        val root: File,
        val repository: File,
        val commit: String,
        val short: String,
        val rootCommit: String,
        val labels: File,
        val corpus: File,
        val output: File,
    )

    /**
     * A clone with an initial commit that sets [initial] and a second commit that applies [changed]; a null value deletes
     * the file. Every file changed in the second commit has to have `total` before and `sum` after, so [labelled] names
     * the files whose labels are written. The default changes two Java files, so one can be labelled and one left
     * unlabelled.
     */
    private fun gitRepository(
        initial: Map<String, String> = mapOf("A.java" to java("A", "total"), "B.java" to java("B", "total")),
        changed: Map<String, String?> = mapOf("A.java" to java("A", "sum"), "B.java" to java("B", "sum")),
        labelled: List<String> = listOf("A.java"),
    ): TestRepository {
        val root = Files.createTempDirectory("relationship-git").toFile()
        val repository = File(root, "sample-repo").apply { mkdirs() }
        git(repository, "init", "-q")
        git(repository, "config", "user.email", "test@example.com")
        git(repository, "config", "user.name", "SMART Diff test")
        git(repository, "config", "commit.gpgsign", "false")
        writeFiles(repository, initial)
        git(repository, "add", "-A")
        git(repository, "commit", "-q", "-m", "initial")
        val rootCommit = git(repository, "rev-parse", "HEAD").trim()
        writeFiles(repository, changed)
        git(repository, "add", "-A")
        git(repository, "commit", "-q", "-m", "change")
        val commit = git(repository, "rev-parse", "HEAD").trim()
        val labels = File(root, "labels")
        val corpus = File(root, "corpus.txt").apply { writeText("${repository.absolutePath} --max-count=1 HEAD\n") }
        val output = File(root, "output")
        val test = TestRepository(root, repository, commit, commit.take(7), rootCommit, labels, corpus, output)
        for (name in labelled) {
            val className = name.substringBeforeLast('.')
            writeCommitLabel(test, className, reviewed = true, path = name)
        }
        return test
    }

    private fun writeFiles(directory: File, files: Map<String, String?>) {
        for ((path, text) in files) {
            val file = File(directory, path)
            if (text == null) {
                file.delete()
            } else {
                file.parentFile.mkdirs()
                file.writeText(text)
            }
        }
    }

    private fun java(className: String, method: String): String = "class $className {\n  int $method() { return 1; }\n}\n"

    private fun scala3(method: String): String = "class Order:\n  def $method: Int =\n    val x = 42\n    x + 1\n"

    private fun writeCommitLabel(
        test: TestRepository,
        name: String,
        reviewed: Boolean,
        repositoryName: String = "sample-repo",
        short: String = test.short,
        commit: String = test.commit,
        path: String = "$name.java",
        corpus: String = "java",
        corpusFolder: String = "java",
        sourceRepository: String = repositoryName,
    ): File {
        val file = File(test.labels, "$corpusFolder/relationships/commits/$repositoryName/$short/$name.yaml")
        file.parentFile.mkdirs()
        file.writeText(
            """case: $repositoryName-$short-$name
language: JAVA
corpus: $corpus
category: real-commit
split: held-out
reviewed: $reviewed
source:
  repository: $sourceRepository
  commit: $commit
  path: $path
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
        val process = ProcessBuilder(listOf("git", "-c", "core.quotePath=false", "-C", repository.absolutePath) + arguments)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed in $repository" }
        return output
    }

    /** Every checked-in case folder, which is a folder under a corpus's relationships folder with a label beside its texts. */
    private fun checkedInCaseFolders(): List<File> =
        File("src/test/testData").walkTopDown()
            .filter { it.isFile && it.name == "label.yaml" && !it.path.replace(File.separatorChar, '/').contains("/commits/") }
            .map { it.parentFile }
            .sortedBy { it.path }
            .toList()

    /** Every checked-in labelled commit, one folder per repository and commit. */
    private fun checkedInCommitFolders(): List<File> =
        RelationshipBenchmark.CORPORA.flatMap { corpus ->
            val root = File("src/test/testData/$corpus/relationships/commits")
            root.listFiles().orEmpty().filter { it.isDirectory }
                .flatMap { repository -> repository.listFiles().orEmpty().filter { it.isDirectory } }
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

    private fun relationship(
        kinds: java.util.EnumSet<RelationshipKind>,
        old: Subject,
        oldRange: TextRange,
        new: Subject,
        newRange: TextRange,
    ) = Relationship(
        kinds = kinds,
        old = listOf(RelationshipEndpoint(old, oldRange)),
        new = listOf(RelationshipEndpoint(new, newRange)),
        confidence = EngineConfidence.HIGH,
        evidence = emptyList(),
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

    /**
     * A provider whose first comparison succeeds with a move and whose second old-side parse throws, as a provider error
     * on the old tree does after the comparison (R26).
     */
    private class ThrowingTreeProvider : DeclarationProvider {
        private var builds = 0

        override fun supports(languageId: String): Boolean = languageId == THROWING_LANGUAGE

        override fun parse(project: Project, text: CharSequence, fileName: String?, dialect: Language?): PsiFile =
            JavaDeclarationProvider().parse(project, text, fileName, dialect)

        override fun build(file: PsiFile, cancellation: Cancellation): DeclarationTree {
            builds++
            if (builds > 2) error("the second parse of the old side threw")
            return tree(if (builds == 1) "A" else "B")
        }

        private fun tree(container: String): DeclarationTree {
            val method = Declaration(DeclarationKind.METHOD, "run", "()", TextRange(10, 20))
            val first = Declaration(
                DeclarationKind.CLASS, "A", null, TextRange(0, 40),
                children = if (container == "A") listOf(method) else emptyList(),
            )
            val second = Declaration(
                DeclarationKind.CLASS, "B", null, TextRange(40, 80),
                children = if (container == "B") listOf(method) else emptyList(),
            )
            return DeclarationTree(listOf(first, second), FileRegion(emptyList(), emptyList()), WhitespacePolicy.INSIGNIFICANT)
        }
    }

    private companion object {
        const val THROWING_LANGUAGE = "THROWING_SECOND_PARSE"
        const val THROWING_LABEL = """case: throwing-second-parse
language: THROWING_SECOND_PARSE
corpus: java
category: move-in-file
split: tuning
expected:
  relationships:
    - kinds: [MOVE]
      old: { path: "class A", kind: METHOD, name: run, signature: "()" }
      new: { path: "class B", kind: METHOD, name: run, signature: "()" }
  changes: []
  ambiguous: []
"""

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
