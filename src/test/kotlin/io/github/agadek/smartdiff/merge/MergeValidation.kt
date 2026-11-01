package io.github.agadek.smartdiff.merge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.compareSafely
import io.github.agadek.smartdiff.engine.api.contract.NothingHidden
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationProvider
import io.github.agadek.smartdiff.ui.merge.MergeWindowSupport
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * The merge corpus mode (merge/06-merge-validation): replays merge commits, checks every file changed on both sides
 * since the merge base, and writes a report of the joined rows, conflicts and flags for review.
 */
object MergeValidation {

    const val PROPERTY = "smartdiff.validation.merges"
    const val MIN_FILES_PER_LANGUAGE = 30
    private const val SLOW_NANOS = 1_000_000_000L

    data class CorpusLine(val repository: File, val arguments: List<String>)

    data class Row(
        val repository: String,
        val commit: String,
        val path: String,
        val languageId: String,
        val dialect: String?,
        val base: String,
        val left: String,
        val right: String,
        val joined: JoinedResult,
        val conflicts: List<TextRange>?,
        val millis: Long,
        val sideMillis: Long,
        val flags: List<String>,
        val nanos: Long = millis * 1_000_000,
    ) {
        val id: String get() = "$repository ${commit.take(10)} $path"
        val flagged: Boolean get() = flags.isNotEmpty()
        val language: String
            get() = when (languageId) {
                "JAVA" -> "Java"
                "kotlin" -> "Kotlin"
                "Scala" -> when (dialect) {
                    "Scala 3" -> "Scala 3"
                    "Scala 2" -> "Scala 2"
                    else -> "Scala"
                }
                else -> languageId
            }
    }

    data class Report(
        val rows: List<Row>,
        val skippedCommits: Int,
        val skippedNoBase: Int,
        val skippedFiles: Int,
        val mergeMedianMillis: Long,
        val sideMedianMillis: Long,
        val coverage: Map<String, Int>,
        val readiness: Readiness,
        val directory: File,
    ) {
        val flagCounts: Map<String, Int>
            get() = rows.flatMap { it.flags }.groupingBy { it }.eachCount().toSortedMap()
        val coverageOk: Boolean
            get() = LANGUAGE_KEYS.all { (coverage[it] ?: 0) >= MIN_FILES_PER_LANGUAGE }
    }

    data class Readiness(
        val hasFiles: Boolean,
        val noJoinMismatch: Boolean,
        val filesToReview: Int,
        val fastEnough: Boolean,
    ) {
        val reviewed: Boolean get() = filesToReview == 0
        val ready: Boolean get() = hasFiles && noJoinMismatch && reviewed && fastEnough
    }

    /** The corpus list, or null when the property is absent or blank (R3). */
    fun listFile(value: String?): File? = value?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)

    /** One non-comment line: a local repository followed by `git rev-list` arguments (R2). */
    fun parseList(file: File): List<CorpusLine> = file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val words = line.split(Regex("\\s+"))
            CorpusLine(File(words.first()), words.drop(1))
        }

    /** The parents of a commit from `git rev-list --parents -n 1`, excluding the commit itself. */
    fun parents(revListLine: String): List<String> = revListLine.trim().split(Regex("\\s+")).drop(1).filter { it.isNotEmpty() }

    /** Files changed on both sides since the merge base. */
    fun changedBothSides(leftPaths: Set<String>, rightPaths: Set<String>): Set<String> = leftPaths intersect rightPaths

    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }

    fun flags(row: Row): List<String> = buildList {
        if (row.joined.baseMismatch) add("join-mismatch")
        if (row.conflicts != null && changedOnBothWithoutConflict(row.joined, row.conflicts)) add("both-sides-clean")
        if (row.conflicts != null && conflictInsideOneSidedChange(row.joined, row.conflicts)) add("conflict-one-side")
        if (row.joined.left.status != Status.ANALYZED || row.joined.right.status != Status.ANALYZED) add("status")
        if (leftHidden(row.base, row.left, row.joined) || rightHidden(row.base, row.right, row.joined)) add("hidden")
        if (row.nanos > SLOW_NANOS) add("slow")
    }

    fun readiness(rows: List<Row>, mergeMedian: Long, sideMedian: Long): Readiness = Readiness(
        hasFiles = rows.isNotEmpty(),
        noJoinMismatch = rows.none { "join-mismatch" in it.flags },
        filesToReview = rows.count { row -> row.flags.any { it == "both-sides-clean" || it == "conflict-one-side" } },
        fastEnough = if (sideMedian <= 0) mergeMedian <= 0 else mergeMedian <= sideMedian * 2,
    )

    fun coverage(rows: List<Row>): Map<String, Int> = LANGUAGE_KEYS.associateWith { key -> rows.count { it.language == key } }

    /** Runs the list and writes the report; without a list this is not called, so CI needs no corpus (R3). */
    fun run(list: File, output: File = File("build/reports/smartdiff-validation/merge"), enforceCoverage: Boolean = false): Report {
        val engine = DeclarationEngine(null)
        val rows = mutableListOf<Row>()
        var skippedCommits = 0
        var skippedNoBase = 0
        var skippedFiles = 0
        for (corpus in parseList(list)) {
            val commits = git(corpus.repository, listOf("rev-list", "--merges") + corpus.arguments)
                .lines().filter { it.isNotBlank() }
            for ((index, commit) in commits.withIndex()) {
                println("MERGE VALIDATION ${corpus.repository.name} commit ${index + 1} of ${commits.size}: ${commit.take(10)}")
                val parentLine = git(corpus.repository, listOf("rev-list", "--parents", "-n", "1", commit))
                val parents = parents(parentLine)
                if (parents.size != 2) {
                    skippedCommits++
                    continue
                }
                val base = git(corpus.repository, listOf("merge-base", parents[0], parents[1]), allowFailure = true)
                    .lineSequence().firstOrNull().orEmpty()
                if (base.isEmpty()) {
                    skippedNoBase++
                    continue
                }
                val leftPaths = paths(corpus.repository, base, parents[0])
                val rightPaths = paths(corpus.repository, base, parents[1])
                for (path in changedBothSides(leftPaths, rightPaths).sorted()) {
                    val baseText = fileAt(corpus.repository, base, path)
                    val leftText = fileAt(corpus.repository, parents[0], path)
                    val rightText = fileAt(corpus.repository, parents[1], path)
                    if (baseText == null || leftText == null || rightText == null) {
                        skippedFiles++
                        continue
                    }
                    val languageId = languageId(path)
                    val started = System.nanoTime()
                    val joined = onPooledThread {
                        MergeComparison.compare(
                            engine,
                            MergeTexts(baseText, leftText, rightText, languageId, File(path).name),
                            Cancellation.NONE,
                            AppExecutorUtil.getAppExecutorService(),
                        )
                    }
                    val nanos = System.nanoTime() - started
                    val millis = nanos / 1_000_000
                    // R13 asks for one comparison of the base with a side; use the left side for every file.
                    val leftRequest = MergeComparison.sideRequests(MergeTexts(baseText, leftText, rightText, languageId, File(path).name)).first
                    val sideMillis = sideMillis(engine, leftRequest)
                    val conflicts = MergeWindowSupport.conflictRanges(leftText, baseText, rightText)
                    val row = Row(
                        corpus.repository.name,
                        commit,
                        path,
                        languageId,
                        dialect(languageId, baseText, leftText, rightText),
                        baseText,
                        leftText,
                        rightText,
                        joined,
                        conflicts,
                        millis,
                        sideMillis,
                        emptyList(),
                        nanos,
                    )
                    rows += row.copy(flags = flags(row))
                }
            }
        }
        val mergeMedian = median(rows.map { it.millis })
        val sideMedian = median(rows.map { it.sideMillis })
        val readiness = readiness(rows, mergeMedian, sideMedian)
        val report = Report(rows, skippedCommits, skippedNoBase, skippedFiles, mergeMedian, sideMedian, coverage(rows), readiness, output)
        write(report)
        if (enforceCoverage && !report.coverageOk) {
            error("merge corpus coverage is below $MIN_FILES_PER_LANGUAGE files per language: ${report.coverage}")
        }
        return report
    }

    internal fun write(report: Report) {
        report.directory.mkdirs()
        File(report.directory, "summary.tsv").writeText(buildString {
            appendLine("repository\tcommit\tpath\tlanguage\tdialect\tmerge-ms\tside-ms\tconflicts\tflags")
            for (row in report.rows) {
                appendLine(
                    listOf(
                        row.repository, row.commit.take(10), row.path, row.language, row.dialect.orEmpty(),
                        row.millis, row.sideMillis, row.conflicts?.size ?: "?", row.flags.joinToString(","),
                    ).joinToString("\t"),
                )
            }
        })
        File(report.directory, "report.md").writeText(buildString {
            appendLine("# SMART Diff merge validation")
            appendLine()
            appendLine("${report.rows.size} files from ${report.rows.map { it.repository to it.commit }.distinct().size} merge commits.")
            appendLine("Skipped ${report.skippedCommits} commits with other than two parents, ${report.skippedNoBase} without a merge base, and ${report.skippedFiles} files missing from a parent or the base.")
            appendLine()
            appendLine("## Flags")
            for (flag in report.flagCounts) appendLine("- ${flag.key}: ${flag.value}")
            if (report.flagCounts.isEmpty()) appendLine("- none")
            appendLine()
            appendLine("## Coverage")
            for (key in LANGUAGE_KEYS) appendLine("- $key: ${report.coverage[key] ?: 0} files (minimum $MIN_FILES_PER_LANGUAGE)")
            appendLine()
            appendLine("## Time")
            appendLine("Merge median: ${report.mergeMedianMillis} ms; one base-side comparison median: ${report.sideMedianMillis} ms.")
            appendLine()
            appendLine("## Readiness")
            appendLine("- has files: ${report.readiness.hasFiles}")
            appendLine("- no join mismatch: ${report.readiness.noJoinMismatch}")
            appendLine("- files needing review: ${report.readiness.filesToReview}")
            appendLine("- median within twice the side comparison: ${report.readiness.fastEnough}")
            for (row in report.rows.filter { it.flagged }) {
                appendLine()
                appendLine("## ${row.repository} ${row.commit.take(10)} ${row.path}")
                appendLine()
                appendLine("Flags: ${row.flags.joinToString(", ")}; ${row.millis} ms, side ${row.sideMillis} ms" + (row.dialect?.let { "; $it" } ?: ""))
                appendLine()
                appendLine("Conflicts: ${conflictLines(row.conflicts, row.base)}")
                appendLine()
                appendLine("```")
                append(MergeRendering.joined(row.joined, row.base, row.left, row.right))
                appendLine("```")
            }
        })
        println("MERGE VALIDATION ${report.rows.size} files, ${report.rows.count { it.flagged }} flagged; report in ${report.directory.absolutePath}")
    }

    private fun changedOnBothWithoutConflict(joined: JoinedResult, conflicts: List<TextRange>): Boolean {
        fun visit(nodes: List<MergeNode>): Boolean = nodes.any { node ->
            val range = node.baseRange
            (range != null && node.group == MergeGroup.CHANGED_ON_BOTH_SIDES && conflicts.none { it.intersects(range) }) || visit(node.children)
        }
        return visit(joined.nodes)
    }

    private fun conflictInsideOneSidedChange(joined: JoinedResult, conflicts: List<TextRange>): Boolean {
        fun visit(nodes: List<MergeNode>): Boolean = nodes.any { node ->
            val range = node.baseRange
            val changedLeft = node.left?.content == Content.CHANGED
            val changedRight = node.right?.content == Content.CHANGED
            val otherLeft = node.left != null && node.left.presence == Presence.MATCHED && node.left.content != Content.CHANGED
            val otherRight = node.right != null && node.right.presence == Presence.MATCHED && node.right.content != Content.CHANGED
            (range != null && conflicts.any { it.intersects(range) } &&
                changedLeft != changedRight && (if (changedLeft) otherRight else otherLeft)) || visit(node.children)
        }
        return visit(joined.nodes)
    }

    private fun leftHidden(base: String, left: String, joined: JoinedResult): Boolean =
        (joined.left.status == Status.ANALYZED || joined.left.status == Status.PARTIAL) &&
            NothingHidden.violation(base, left, joined.left) != null

    private fun rightHidden(base: String, right: String, joined: JoinedResult): Boolean =
        (joined.right.status == Status.ANALYZED || joined.right.status == Status.PARTIAL) &&
            NothingHidden.violation(base, right, joined.right) != null

    private fun TextRange.intersects(other: TextRange): Boolean =
        if (start == end) start >= other.start && start <= other.end
        else if (other.start == other.end) other.start >= start && other.start <= end
        else start < other.end && end > other.start

    private fun conflictLines(conflicts: List<TextRange>?, base: String): String {
        if (conflicts == null) return "unreadable"
        if (conflicts.isEmpty()) return "none"
        fun line(offset: Int) = 1 + (0 until offset).count { base[it] == '\n' }
        return conflicts.joinToString(", ") { range ->
            val first = line(range.start)
            val last = if (range.length == 0) first else line(range.end - 1)
            if (first == last) "base $first" else "base $first-$last"
        }
    }

    private fun sideMillis(engine: DeclarationEngine, request: ComparisonRequest): Long {
        val started = System.nanoTime()
        onPooledThread { compareSafely(engine, request, Cancellation.NONE) }
        return (System.nanoTime() - started) / 1_000_000
    }

    private fun dialect(languageId: String, base: String, left: String, right: String): String? {
        if (languageId != "Scala") return null
        val provider = DeclarationProvider.EP_NAME.extensionList.firstOrNull { it.supports(languageId) } ?: return null
        return ReadAction.compute(ThrowableComputable<String?, RuntimeException> {
            when (val id = provider.dialect(ProjectManager.getInstance().defaultProject, listOf(base, left, right), null)?.id) {
                "Scala" -> "Scala 2"
                else -> id
            }
        })
    }

    private fun paths(repository: File, from: String, to: String): Set<String> =
        git(repository, listOf("diff", "--name-only", "--diff-filter=ACDMRT", from, to))
            .lines()
            .filter { it.isNotBlank() && isSource(it) }
            .toSet()

    private fun isSource(path: String): Boolean = path.endsWith(".java") || path.endsWith(".kt") || path.endsWith(".scala")

    private fun languageId(path: String): String = when {
        path.endsWith(".kt") -> "kotlin"
        path.endsWith(".scala") -> "Scala"
        else -> "JAVA"
    }

    private fun fileAt(repository: File, revision: String, path: String): String? {
        val content = git(repository, listOf("show", "$revision:$path"), allowFailure = true)
        return if (content.isEmpty() && !exists(repository, revision, path)) null else content.replace("\r\n", "\n")
    }

    private fun exists(repository: File, revision: String, path: String): Boolean =
        ProcessBuilder(listOf("git", "-C", repository.absolutePath, "cat-file", "-e", "$revision:$path"))
            .redirectErrorStream(true)
            .start()
            .let { it.inputStream.readBytes(); it.waitFor() == 0 }

    private fun git(repository: File, arguments: List<String>, allowFailure: Boolean = false): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.absolutePath) + arguments)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0 && !allowFailure) error("git ${arguments.joinToString(" ")} failed in $repository with exit code $exit")
        return output
    }

    private fun <T> onPooledThread(task: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread(Callable { task() }).get(10, TimeUnit.MINUTES)

    private val LANGUAGE_KEYS = listOf("Java", "Kotlin", "Scala 2", "Scala 3")
}
