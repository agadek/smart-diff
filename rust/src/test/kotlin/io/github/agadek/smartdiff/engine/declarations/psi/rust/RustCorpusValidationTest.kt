package io.github.agadek.smartdiff.engine.declarations.psi.rust

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.compareSafely
import io.github.agadek.smartdiff.engine.api.contract.NothingHidden
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the declaration engine over real Rust commits from a corpus list (languages/05-rust R17). Skipped without
 * `-Psmartdiff.validation.rust.corpus=<list>`, so CI does not need the corpus.
 *
 * Each line of the list is a local Git repository followed by `git rev-list` arguments.
 */
class RustCorpusValidationTest : BasePlatformTestCase() {

    fun `test real Rust commits`() {
        // languages/05-rust R17
        val list = System.getProperty("smartdiff.validation.rust.corpus")?.takeIf { it.isNotBlank() }
        if (list == null) {
            println("RUST VALIDATION skipped: no corpus (run with -Psmartdiff.validation.rust.corpus=<file>)")
            return
        }
        val engine = DeclarationEngine(ProjectManager.getInstance().defaultProject)
        val rows = mutableListOf<Row>()
        val listFile = File(list)
        for (line in listFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val words = line.split(Regex("\\s+"))
            val repository = File(words.first())
            val commits = git(repository, listOf("rev-list") + words.drop(1) + listOf("--", "*.rs")).lines().filter { it.isNotBlank() }
            for ((index, commit) in commits.withIndex()) {
                println("RUST VALIDATION ${repository.name} commit ${index + 1} of ${commits.size}: ${commit.take(10)}")
                val parent = git(repository, listOf("rev-parse", "--verify", "--quiet", "$commit^"), allowFailure = true).trim()
                if (parent.isEmpty()) continue
                val paths = git(repository, listOf("diff", "--no-renames", "--name-only", "--diff-filter=M", parent, commit, "--", "*.rs"))
                    .lines().filter { it.isNotBlank() }
                for (path in paths) {
                    val old = git(repository, listOf("show", "$parent:$path")).replace("\r\n", "\n")
                    val new = git(repository, listOf("show", "$commit:$path")).replace("\r\n", "\n")
                    val request = ComparisonRequest(old, new, "Rust", File(path).name)
                    val start = System.nanoTime()
                    val result = ReadAction.compute(ThrowableComputable {
                        compareSafely(engine, request, Cancellation.NONE)
                    })
                    val millis = (System.nanoTime() - start) / 1_000_000
                    val flags = flags(old, new, result, millis)
                    rows += Row(repository.name, commit.take(10), path, result.status, result.changes.isNotEmpty(), flags, millis)
                }
            }
        }
        val report = File("build/reports/smartdiff-rust-validation")
        report.mkdirs()
        val summary = buildString {
            appendLine("repository\tcommit\tpath\tstatus\thasChanges\tmillis\tflags")
            for (row in rows) {
                appendLine(listOf(row.repository, row.commit, row.path, row.status, row.hasChanges, row.millis, row.flags.joinToString(",")).joinToString("\t"))
            }
        }
        File(report, "summary.tsv").writeText(summary)
        val analyzed = rows.count { it.status == Status.ANALYZED }
        val partial = rows.count { it.status == Status.PARTIAL }
        val fallback = rows.count { it.status != Status.ANALYZED && it.status != Status.PARTIAL }
        val flagged = rows.count { it.flags.isNotEmpty() }
        val hidden = rows.count { "hidden" in it.flags }
        val empty = rows.count { "empty" in it.flags }
        println("RUST VALIDATION files=${rows.size} commits=${rows.map { it.repository + it.commit }.toSet().size} analyzed=$analyzed partial=$partial fallback=$fallback flagged=$flagged hidden=$hidden empty=$empty")
    }

    private data class Row(
        val repository: String,
        val commit: String,
        val path: String,
        val status: Status,
        val hasChanges: Boolean,
        val flags: List<String>,
        val millis: Long,
    )

    private fun flags(old: String, new: String, result: io.github.agadek.smartdiff.engine.api.ComparisonResult, millis: Long): List<String> = buildList {
        if (result.status != Status.ANALYZED) add("status")
        if (result.status == Status.ANALYZED || result.status == Status.PARTIAL) {
            if (NothingHidden.violation(old, new, result) != null) add("hidden")
            if (old != new && !result.hasReportedChanges) add("empty")
        }
        if (millis > 1000) add("slow")
    }

    private fun git(directory: File, arguments: List<String>, allowFailure: Boolean = false): String {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        val code = if (finished) process.exitValue() else run { process.destroyForcibly(); -1 }
        if (!allowFailure && code != 0) error("git ${arguments.joinToString(" ")} failed in ${directory.path}: $output")
        return output
    }
}
