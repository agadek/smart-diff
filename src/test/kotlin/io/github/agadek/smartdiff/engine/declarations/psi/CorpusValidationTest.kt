package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.compareSafely
import io.github.agadek.smartdiff.engine.api.contract.NothingHidden
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * Runs the declaration engine over real commits and writes what it reports, to find wrong and noisy results before a
 * release: every modified Java or Kotlin file of every listed commit is compared, parent revision against commit, with
 * the default limits, and results that look wrong or noisy are flagged for review.
 *
 * The commits come from the file named by `-Psmartdiff.validation.corpus=<list>`; without it the test only says it was
 * skipped, so CI does not need the corpus. Each line of the list is a local Git repository followed by `git rev-list`
 * arguments, for example `/src/okhttp --no-merges --max-count=40 HEAD`; lines starting with `#` are comments.
 *
 * The report goes to `build/reports/smartdiff-validation/`: `summary.tsv` with one row per file and its flags, and
 * `report.md` with the outline and the native hunk headers of each flagged file. Flags:
 * - `status`: the outline falls back to the native diff (anything but ANALYZED).
 * - `hidden`: a difference lies outside every reported change (technical specification §3.1 rule 6).
 * - `empty`: the texts differ but nothing is reported.
 * - `same-name`: a declaration is reported as removed and added under the same kind and name, so a signature change
 *   was not paired.
 * - `rename`: a removed and an added declaration of the same kind share a parent, a likely literal rename.
 * - `rename-candidate`: a rename candidate, to check that it is right.
 * - `uncertain`: an uncertain match.
 * - `noisy`: more reported changes than twice the native hunks plus two.
 * - `slow`: the comparison took more than a second.
 */
class CorpusValidationTest : BasePlatformTestCase() {

    fun `test real commits`() {
        val list = System.getProperty("smartdiff.validation.corpus")?.takeIf { it.isNotBlank() }
        if (list == null) {
            println("VALIDATION skipped: no corpus (run with -Psmartdiff.validation.corpus=<file listing repositories and commits>)")
            return
        }
        val engine = DeclarationEngine(null)
        val rows = mutableListOf<Row>()
        for (line in File(list).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val words = line.split(Regex("\\s+"))
            val repository = File(words.first())
            val commits = git(repository, listOf("rev-list") + words.drop(1) + listOf("--", "*.java", "*.kt")).lines().filter { it.isNotBlank() }
            for ((index, commit) in commits.withIndex()) {
                println("VALIDATION ${repository.name} commit ${index + 1} of ${commits.size}: ${commit.take(10)}")
                val parent = git(repository, listOf("rev-parse", "--verify", "--quiet", "$commit^"), allowFailure = true).trim()
                if (parent.isEmpty()) continue
                val paths = git(repository, listOf("diff", "--name-only", "--diff-filter=M", parent, commit, "--", "*.java", "*.kt"))
                    .lines().filter { it.isNotBlank() }
                for (path in paths) {
                    val old = git(repository, listOf("show", "$parent:$path")).replace("\r\n", "\n")
                    val new = git(repository, listOf("show", "$commit:$path")).replace("\r\n", "\n")
                    val hunks = git(repository, listOf("diff", "-U0", parent, commit, "--", path)).lines().filter { it.startsWith("@@") }
                    val languageId = if (path.endsWith(".kt")) "kotlin" else "JAVA"
                    val request = ComparisonRequest(old, new, languageId, File(path).name)
                    val start = System.nanoTime()
                    val result = onPooledThread { compareSafely(engine, request, Cancellation.NONE) }
                    val millis = (System.nanoTime() - start) / 1_000_000
                    rows += Row(repository.name, commit.take(10), path, old, new, hunks, result, millis, flags(old, new, hunks.size, result, millis))
                }
            }
        }
        write(rows)
    }

    private class Row(
        val repository: String, val commit: String, val path: String, val old: String, val new: String,
        val hunks: List<String>, val result: ComparisonResult, val millis: Long, val flags: List<String>,
    ) {
        /** Whether the report lists this file. */
        val flagged: Boolean get() = flags.isNotEmpty()
    }

    private fun flags(old: String, new: String, hunks: Int, result: ComparisonResult, millis: Long): List<String> = buildList {
        if (result.status != Status.ANALYZED) add("status")
        if (result.status == Status.ANALYZED || result.status == Status.PARTIAL) {
            if (NothingHidden.violation(old, new, result) != null) add("hidden")
            if (old != new && !result.hasReportedChanges) add("empty")
        }
        val nodes = flatten(result.changes)
        val removed = nodes.filter { it.node.presence == Presence.REMOVED }
        val added = nodes.filter { it.node.presence == Presence.ADDED }
        if (removed.any { r -> added.any { a -> sameKindAndName(r.node, a.node) } }) add("same-name")
        if (removed.any { r -> added.any { a -> r.parent == a.parent && sameKind(r.node, a.node) && !sameKindAndName(r.node, a.node) } }) add("rename")
        if (nodes.any { it.node.renamedFrom != null }) add("rename-candidate")
        if (nodes.any { it.node.presence == Presence.UNCERTAIN }) add("uncertain")
        if (reported(result) > 2 * hunks + 2) add("noisy")
        if (millis > 1000) add("slow")
    }

    private class Flat(val node: ChangeNode, val parent: ChangeNode?)

    private fun flatten(nodes: List<ChangeNode>, parent: ChangeNode? = null): List<Flat> =
        nodes.flatMap { listOf(Flat(it, parent)) + flatten(it.children, it) }

    private fun sameKind(a: ChangeNode, b: ChangeNode): Boolean {
        val x = a.subject as? Subject.Declaration ?: return false
        val y = b.subject as? Subject.Declaration ?: return false
        return x.kind == y.kind
    }

    private fun sameKindAndName(a: ChangeNode, b: ChangeNode): Boolean =
        sameKind(a, b) && (a.subject as Subject.Declaration).name == (b.subject as Subject.Declaration).name

    /** Lines the outline shows for their own sake: nodes that are changes themselves, the file region and unanalyzed ranges. */
    private fun reported(result: ComparisonResult): Int =
        flatten(result.changes).count { it.node.isChangedItself } + (if (result.fileRegion?.isChangedItself == true) 1 else 0) + result.unanalyzed.size

    private fun formattingOnly(result: ComparisonResult): Int =
        flatten(result.changes).count { it.node.content == Content.FORMATTING_ONLY && !it.node.moved && !it.node.signatureChanged }

    private fun write(rows: List<Row>) {
        val directory = File("build/reports/smartdiff-validation").apply { mkdirs() }
        File(directory, "summary.tsv").writeText(buildString {
            appendLine("repository\tcommit\tpath\tstatus\thunks\treported\tformatting-only\tms\tflags")
            for (row in rows) {
                appendLine(listOf(row.repository, row.commit, row.path, row.result.status, row.hunks.size, reported(row.result),
                    formattingOnly(row.result), row.millis, row.flags.joinToString(",")).joinToString("\t"))
            }
        })
        File(directory, "report.md").writeText(buildString {
            appendLine("# SMART Diff corpus validation")
            appendLine()
            appendLine("${rows.size} files from ${rows.map { it.repository to it.commit }.distinct().size} commits; ${rows.count { it.flagged }} flagged.")
            appendLine()
            for (flag in rows.flatMap { it.flags }.distinct().sorted()) appendLine("- $flag: ${rows.count { flag in it.flags }}")
            for (row in rows.filter { it.flagged }) {
                appendLine()
                appendLine("## ${row.repository} ${row.commit} ${row.path}")
                appendLine()
                appendLine("Flags: ${row.flags.joinToString(", ")}; ${row.millis} ms")
                appendLine()
                appendLine("```")
                append(Rendering.result(row.result, row.old, row.new))
                appendLine("--- native hunks")
                row.hunks.forEach { appendLine(it) }
                appendLine("```")
            }
        })
        println("VALIDATION ${rows.size} files, ${rows.count { it.flagged }} flagged; report in ${directory.absolutePath}")
    }

    private fun git(repository: File, arguments: List<String>, allowFailure: Boolean = false): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.absolutePath) + arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0 && !allowFailure) error("git ${arguments.joinToString(" ")} failed in $repository with exit code $exit")
        return output
    }

    private fun <T> onPooledThread(task: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread(Callable { task() }).get(10, TimeUnit.MINUTES)
}
