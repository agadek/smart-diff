package io.github.agadek.smartdiff.merge

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
import io.github.agadek.smartdiff.ui.merge.MergeWindowSupport
import java.io.File
import java.nio.file.Files

/** The merge corpus mode (merge/06-merge-validation), its flags, report and synthetic corpus. */
class MergeValidationTest : BasePlatformTestCase() {

    fun `test merge mode uses the documented property and skips without it`() {
        // merge/06-merge-validation R1, R3
        assertEquals("smartdiff.validation.merges", MergeValidation.PROPERTY)
        assertNull(MergeValidation.listFile(null))
        assertNull(MergeValidation.listFile("  "))
        assertNotNull(MergeValidation.listFile("/tmp/merges.txt"))
    }

    fun `test each corpus line is a repository and rev-list arguments`() {
        // merge/06-merge-validation R2
        val file = Files.createTempFile("merge-corpus", ".txt").toFile()
        try {
            file.writeText("# a comment\n\n/src/okhttp --max-count=40 HEAD\n/src/cats --merges --since=2024-01-01 main\n")
            val lines = MergeValidation.parseList(file)
            assertEquals(2, lines.size)
            assertEquals(File("/src/okhttp"), lines[0].repository)
            assertEquals(listOf("--max-count=40", "HEAD"), lines[0].arguments)
            assertEquals("/src/cats", lines[1].repository.path)
        } finally {
            file.delete()
        }
    }

    fun `test a join mismatch is flagged`() {
        // merge/06-merge-validation R7
        val joined = JoinedResult(result(), result(), baseMismatch = true)
        assertTrue("join-mismatch" in flags(row(joined = joined)))
    }

    fun `test changed on both sides without a conflict is both-sides-clean`() {
        // merge/06-merge-validation R8
        val node = MergeNode(decl(), baseRange(), changed(), changed(), MergeGroup.CHANGED_ON_BOTH_SIDES)
        val joined = JoinedResult(result(node), result(node), nodes = listOf(node))
        assertTrue("both-sides-clean" in flags(row(joined = joined, conflicts = emptyList())))

        val added = MergeNode(decl(), null, changed(), changed(), MergeGroup.CHANGED_ON_BOTH_SIDES)
        val addBoth = JoinedResult(result(added), result(added), nodes = listOf(added))
        assertFalse("an add/add row is not a base declaration", "both-sides-clean" in flags(row(joined = addBoth, conflicts = emptyList())))
    }

    fun `test a conflict inside a one-side content change is conflict-one-side`() {
        // merge/06-merge-validation R9
        val left = changed()
        val right = matched()
        val node = MergeNode(decl(), baseRange(), left, right, MergeGroup.CHANGED_ON_BOTH_SIDES)
        val joined = JoinedResult(result(node), result(node), nodes = listOf(node))
        val flags = flags(row(joined = joined, conflicts = listOf(baseRange())))
        assertTrue(flags.toString(), "conflict-one-side" in flags)
        assertFalse("both-sides-clean" in flags)

        val removed = ChangeNode(decl(), baseRange(), null, Presence.REMOVED)
        val removedOneSide = MergeNode(decl(), baseRange(), removed, changed(), MergeGroup.CHANGED_ON_BOTH_SIDES)
        assertFalse("a removed side is not a content change", "conflict-one-side" in flags(
            row(joined = JoinedResult(result(removedOneSide), result(removedOneSide), nodes = listOf(removedOneSide)), conflicts = listOf(baseRange())),
        ))

        val formatting = ChangeNode(decl(), baseRange(), baseRange(), Presence.MATCHED, content = Content.FORMATTING_ONLY)
        val formattingOneSide = MergeNode(decl(), baseRange(), changed(), formatting, MergeGroup.CHANGED_ON_BOTH_SIDES)
        assertTrue("a formatting-only other side is not a content change", "conflict-one-side" in flags(
            row(joined = JoinedResult(result(formattingOneSide), result(formattingOneSide), nodes = listOf(formattingOneSide)), conflicts = listOf(baseRange())),
        ))
    }

    fun `test conflicts come from the merge window computation`() {
        // merge/06-merge-validation R10
        val base = "class A {\n  int x = 0;\n}\n"
        val left = base.replace("= 0", "= 1")
        val right = base.replace("= 0", "= 2")
        val conflicts = MergeWindowSupport.conflictRanges(left, base, right)
        assertNotNull("conflict ranges", conflicts)
        assertTrue("a conflict", conflicts!!.isNotEmpty())
    }

    fun `test a slow file is flagged only above one second`() {
        // merge/06-merge-validation R11
        assertFalse("slow" in flags(row(millis = 1000, nanos = 1_000_000_000)))
        assertTrue("slow" in flags(row(millis = 1000, nanos = 1_000_000_001)))
        assertTrue("slow" in flags(row(millis = 1001)))
    }

    fun `test status and hidden flags follow the two-sided mode`() {
        // merge/06-merge-validation R12
        val failed = JoinedResult(result(Status.FAILED), result())
        assertTrue("status" in flags(row(joined = failed)))

        val hidden = JoinedResult(result(), result())
        val row = row(joined = hidden, base = "abc", left = "aXc", right = "aXc")
        assertTrue("hidden" in flags(row))
    }

    fun `test the report goes to the merge folder with counts and conflicts`() {
        // merge/06-merge-validation R6, R13, R22, R23
        val directory = Files.createTempDirectory("merge-validation-report").toFile()
        try {
            val joined = JoinedResult(result(), result())
            val row = row(joined = joined, conflicts = listOf(TextRange(0, 1)), flags = listOf("both-sides-clean"))
            val report = report(listOf(row.copy(millis = 20, sideMillis = 5)), directory)
            MergeValidation.write(report)

            assertTrue(File(directory, "summary.tsv").isFile)
            val markdown = File(directory, "report.md").readText()
            assertTrue(markdown, markdown.contains("both-sides-clean: 1"))
            assertTrue(markdown, markdown.contains("Merge median: 20 ms"))
            assertTrue(markdown, markdown.contains("one base-side comparison median: 5 ms"))
            assertTrue(markdown, markdown.contains("Conflicts: base 1"))
            assertTrue(markdown, markdown.contains("```"))
        } finally {
            directory.deleteRecursively()
        }
    }

    fun `test coverage counts each language and needs thirty files`() {
        // merge/06-merge-validation R14, R24
        val rows = mutableListOf<MergeValidation.Row>()
        for ((languageId, dialect) in listOf("JAVA" to null, "kotlin" to null, "Scala" to "Scala 2", "Scala" to "Scala 3")) {
            repeat(30) { rows += row(languageId = languageId, dialect = dialect) }
        }
        val coverage = MergeValidation.coverage(rows)
        assertEquals(listOf(30, 30, 30, 30), listOf("Java", "Kotlin", "Scala 2", "Scala 3").map { coverage[it] })
        assertTrue(report(rows, Files.createTempDirectory("coverage").toFile()).coverageOk)
        assertFalse(report(rows.drop(1), Files.createTempDirectory("coverage").toFile()).coverageOk)
    }

    fun `test readiness checks join mismatches, review and the median ratio`() {
        // merge/06-merge-validation R15, R16, R17
        val clean = row(millis = 10, sideMillis = 6)
        assertTrue(MergeValidation.readiness(listOf(clean), 10, 6).ready)
        assertFalse("an empty run is not ready", MergeValidation.readiness(emptyList(), 0, 0).ready)
        assertFalse(MergeValidation.readiness(listOf(clean.copy(flags = listOf("join-mismatch"))), 10, 6).noJoinMismatch)

        val review = clean.copy(flags = listOf("both-sides-clean"))
        assertFalse(MergeValidation.readiness(listOf(review), 10, 6).reviewed)
        assertTrue(MergeValidation.readiness(listOf(review), 10, 6, setOf(review.id)).reviewed)

        assertTrue(MergeValidation.readiness(listOf(clean), 12, 6).fastEnough)
        assertFalse(MergeValidation.readiness(listOf(clean), 13, 6).fastEnough)
    }

    fun `test a commit with other than two parents is skipped`() {
        // merge/06-merge-validation R19
        assertEquals(listOf("p1", "p2"), MergeValidation.parents("commit p1 p2"))
        assertEquals(listOf("p1", "p2", "p3"), MergeValidation.parents("commit p1 p2 p3"))
    }

    fun `test only files changed on both sides are selected`() {
        // merge/06-merge-validation R20
        assertEquals(setOf("both.java"), MergeValidation.changedBothSides(setOf("both.java", "left.java"), setOf("both.java", "right.java")))
    }

    fun `test Scala files record the dialect in the language`() {
        // merge/06-merge-validation R21
        assertEquals("Scala 2", row(languageId = "Scala", dialect = "Scala 2").language)
        assertEquals("Scala 3", row(languageId = "Scala", dialect = "Scala 3").language)
        assertEquals("Scala", row(languageId = "Scala", dialect = null).language)
        assertEquals("Java", row(languageId = "JAVA").language)
    }

    fun `test a real merge commit runs through the corpus mode`() {
        // merge/06-merge-validation R4, R5, R6, R19, R20, R22, R23
        val repository = Files.createTempDirectory("merge-corpus").toFile()
        try {
            buildCorpus(repository)
            val list = File(repository, "merges.txt").apply { writeText("${repository.absolutePath} --max-count=5 HEAD\n") }
            val output = File(repository, "report")
            val report = MergeValidation.run(list, output)

            assertTrue("files found", report.rows.size >= 4)
            assertEquals(listOf(2, 1, 1, 1), listOf("Java", "Kotlin", "Scala 2", "Scala 3").map { report.coverage[it] })
            assertTrue("both-sides-clean", (report.flagCounts["both-sides-clean"] ?: 0) > 0)
            assertTrue("skipped octopus", report.skippedCommits > 0)
            assertTrue("skipped add/add file", report.skippedFiles > 0)
            assertTrue("conflicts listed", File(output, "report.md").readText().contains("Conflicts:"))
            assertTrue("run computes conflicts", report.rows.first { it.path == "Conflict.java" }.conflicts!!.isNotEmpty())
            assertTrue(File(output, "summary.tsv").isFile)
        } finally {
            repository.deleteRecursively()
        }
    }

    fun `test the dated validation section records the merge corpus`() {
        // merge/06-merge-validation R18
        val validation = File("docs/VALIDATION.md").readText()
        assertTrue("dated merge validation section", validation.contains("## 2026-11-01: merge validation"))
        assertTrue("the merge corpus is recorded", validation.contains("**Corpus:**"))
    }

    private fun flags(row: MergeValidation.Row): List<String> = MergeValidation.flags(row)

    private fun row(
        joined: JoinedResult = JoinedResult(result(), result()),
        conflicts: List<TextRange>? = emptyList(),
        millis: Long = 0,
        sideMillis: Long = 0,
        languageId: String = "JAVA",
        dialect: String? = null,
        flags: List<String> = emptyList(),
        base: String = "abc",
        left: String = "abc",
        right: String = "abc",
        nanos: Long = millis * 1_000_000,
    ) = MergeValidation.Row(
        "repo", "0123456789abcdef", "A.java", languageId, dialect, base, left, right, joined, conflicts,
        millis, sideMillis, flags, nanos,
    )

    private fun report(rows: List<MergeValidation.Row>, directory: File): MergeValidation.Report {
        val mergeMedian = MergeValidation.median(rows.map { it.millis })
        val sideMedian = MergeValidation.median(rows.map { it.sideMillis })
        return MergeValidation.Report(
            rows, 0, 0, 0, mergeMedian, sideMedian, MergeValidation.coverage(rows),
            MergeValidation.readiness(rows, mergeMedian, sideMedian), directory,
        )
    }

    private fun result(status: Status = Status.ANALYZED, vararg nodes: MergeNode): ComparisonResult =
        ComparisonResult(EngineId("test"), "1", status, changes = nodes.map { ChangeNode(it.subject, it.baseRange, it.baseRange, Presence.MATCHED) })

    private fun result(node: MergeNode): ComparisonResult = result(Status.ANALYZED, node)

    private fun changed() = ChangeNode(decl(), baseRange(), baseRange(), Presence.MATCHED, content = Content.CHANGED)

    private fun matched() = ChangeNode(decl(), baseRange(), baseRange(), Presence.MATCHED)

    private fun decl() = Subject.Declaration(DeclarationKind.METHOD, "f", "()")

    private fun baseRange() = TextRange(0, 5)

    private fun buildCorpus(repository: File) {
        git(repository, "init", "-b", "main")
        git(repository, "config", "user.email", "test@example.com")
        git(repository, "config", "user.name", "Test")
        writeFile(repository, "A.java", "class A {\n    int total() {\n        int x = 1;\n        int s1 = 0;\n        int s2 = 0;\n        int s3 = 0;\n        int y = 2;\n        return x + y;\n    }\n    int label() { return 2; }\n}\n")
        writeFile(repository, "B.kt", "class B {\n    fun total(): Int {\n        val x = 1\n        val s1 = 0\n        val s2 = 0\n        val s3 = 0\n        val y = 2\n        return x + y\n    }\n    fun label(): Int = 2\n}\n")
        writeFile(repository, "C.scala", "class C {\n  def total(): Int = {\n    val x = 1\n    val s1 = 0\n    val s2 = 0\n    val s3 = 0\n    val y = 2\n    x + y\n  }\n  def label(): Int = 2\n}\n")
        writeFile(repository, "D.scala", "class D:\n  def total(): Int =\n    val x = 1\n    val s1 = 0\n    val s2 = 0\n    val s3 = 0\n    val y = 2\n    x + y\n  def label(): Int = 2\n")
        writeFile(repository, "Conflict.java", "class Conflict {\n    int value() { return 0; }\n}\n")
        git(repository, "add", ".")
        git(repository, "commit", "-m", "base")

        git(repository, "checkout", "-b", "side")
        writeFile(repository, "A.java", "class A {\n    int total() {\n        int x = 10;\n        int s1 = 0;\n        int s2 = 0;\n        int s3 = 0;\n        int y = 2;\n        return x + y;\n    }\n    int label() { return 2; }\n}\n")
        writeFile(repository, "B.kt", "class B {\n    fun total(): Int {\n        val x = 10\n        val s1 = 0\n        val s2 = 0\n        val s3 = 0\n        val y = 2\n        return x + y\n    }\n    fun label(): Int = 2\n}\n")
        writeFile(repository, "C.scala", "class C {\n  def total(): Int = {\n    val x = 10\n    val s1 = 0\n    val s2 = 0\n    val s3 = 0\n    val y = 2\n    x + y\n  }\n  def label(): Int = 2\n}\n")
        writeFile(repository, "D.scala", "class D:\n  def total(): Int =\n    val x = 10\n    val s1 = 0\n    val s2 = 0\n    val s3 = 0\n    val y = 2\n    x + y\n  def label(): Int = 2\n")
        writeFile(repository, "Conflict.java", "class Conflict {\n    int value() { return 1; }\n}\n")
        writeFile(repository, "Added.java", "class Added { int side() { return 1; } }\n")
        git(repository, "add", ".")
        git(repository, "commit", "-m", "side")

        git(repository, "checkout", "main")
        writeFile(repository, "A.java", "class A {\n    int total() {\n        int x = 1;\n        int s1 = 0;\n        int s2 = 0;\n        int s3 = 0;\n        int y = 20;\n        return x + y;\n    }\n    int label() { return 2; }\n}\n")
        writeFile(repository, "B.kt", "class B {\n    fun total(): Int {\n        val x = 1\n        val s1 = 0\n        val s2 = 0\n        val s3 = 0\n        val y = 20\n        return x + y\n    }\n    fun label(): Int = 2\n}\n")
        writeFile(repository, "C.scala", "class C {\n  def total(): Int = {\n    val x = 1\n    val s1 = 0\n    val s2 = 0\n    val s3 = 0\n    val y = 20\n    x + y\n  }\n  def label(): Int = 2\n}\n")
        writeFile(repository, "D.scala", "class D:\n  def total(): Int =\n    val x = 1\n    val s1 = 0\n    val s2 = 0\n    val s3 = 0\n    val y = 20\n    x + y\n  def label(): Int = 2\n")
        writeFile(repository, "Conflict.java", "class Conflict {\n    int value() { return 2; }\n}\n")
        writeFile(repository, "Added.java", "class Added { int main() { return 2; } }\n")
        git(repository, "add", ".")
        git(repository, "commit", "-m", "main")

        git(repository, "merge", "--no-ff", "side", "-m", "merge", allowFailure = true)
        writeFile(repository, "Conflict.java", "class Conflict {\n    int value() { return 3; }\n}\n")
        writeFile(repository, "Added.java", "class Added { int side() { return 1; } int main() { return 2; } }\n")
        git(repository, "add", ".")
        git(repository, "commit", "-m", "merge resolved")

        git(repository, "checkout", "-b", "o1")
        writeFile(repository, "O1.java", "class O1 {}\n")
        git(repository, "add", ".")
        git(repository, "commit", "-m", "o1")
        git(repository, "checkout", "main")
        git(repository, "checkout", "-b", "o2")
        writeFile(repository, "O2.java", "class O2 {}\n")
        git(repository, "add", ".")
        git(repository, "commit", "-m", "o2")
        git(repository, "checkout", "main")
        git(repository, "merge", "--no-ff", "o1", "o2", "-m", "octopus")
    }

    private fun writeFile(repository: File, path: String, text: String) {
        File(repository, path).writeText(text)
    }

    private fun git(repository: File, vararg arguments: String, allowFailure: Boolean = false) {
        val process = ProcessBuilder(listOf("git", "-C", repository.absolutePath) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0 && !allowFailure) error("git ${arguments.joinToString(" ")} failed: $output")
    }
}
