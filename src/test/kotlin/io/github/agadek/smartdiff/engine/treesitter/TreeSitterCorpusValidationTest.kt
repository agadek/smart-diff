package io.github.agadek.smartdiff.engine.treesitter

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.LanguageIds
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.compareSafely
import io.github.agadek.smartdiff.engine.api.contract.NothingHidden
import io.github.agadek.smartdiff.engine.declarations.psi.CorpusSupport
import io.github.agadek.smartdiff.engine.declarations.psi.Rendering
import java.io.File
import java.nio.file.Path

/**
 * Runs the Tree-sitter engine over real Swift, C#, C and C++ commits (engines/05 R30, phase T3). Skipped without
 * `-Psmartdiff.validation.treesitter.corpus=<list>`, so CI needs no corpus. Each line of the list is a local Git
 * repository followed by `git rev-list` arguments; every modified file with a Tree-sitter extension (R3) is compared,
 * parent revision against commit, with the default limits.
 *
 * The report goes to `build/reports/smartdiff-treesitter-validation/`: `summary.tsv` with one row per file and
 * `report.md` with the outline of every flagged file. Flags: `status` (anything but ANALYZED), `hidden` (a difference
 * outside every reported change), `empty` (the texts differ and nothing is reported), `same-name` (a declaration
 * removed and added under the same kind and name), `unanalyzed` (a range the grammar could not parse), `noisy` (more
 * reported changes than twice the native hunks plus two) and `slow` (over a second).
 */
class TreeSitterCorpusValidationTest : BasePlatformTestCase() {

    private val engine = TreeSitterEngine(
        TreeSitterHelpers { Path.of(System.getProperty(TreeSitterHelpers.HELPER_PROPERTY)) },
        enabled = { true },
    )

    fun `test real Tree-sitter commits`() {
        // engines/05 R30
        val list = System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }
        if (list == null) {
            println("TREE-SITTER VALIDATION skipped: no corpus (run with -P$PROPERTY=<file>)")
            return
        }
        val rows = mutableListOf<Row>()
        for (words in CorpusSupport.listLines(File(list))) {
            val repository = File(words.first())
            val commits = CorpusSupport.git(repository, listOf("rev-list") + words.drop(1) + listOf("--") + GLOBS)
                .lines().filter { it.isNotBlank() }
            for ((index, commit) in commits.withIndex()) {
                println("TREE-SITTER VALIDATION ${repository.name} commit ${index + 1} of ${commits.size}: ${commit.take(10)}")
                val parent = CorpusSupport.git(repository, listOf("rev-parse", "--verify", "--quiet", "$commit^"), allowFailure = true).trim()
                if (parent.isEmpty()) continue
                // --no-renames: a rename is a delete plus an add, so it is skipped instead of vanishing from the run.
                val paths = CorpusSupport.git(repository, listOf("diff", "--no-renames", "--name-only", "--diff-filter=M", parent, commit, "--") + GLOBS)
                    .lines().filter { it.isNotBlank() }
                for (path in paths) {
                    val languageId = languageId(path) ?: continue
                    val old = CorpusSupport.git(repository, listOf("show", "$parent:$path")).replace("\r\n", "\n")
                    val new = CorpusSupport.git(repository, listOf("show", "$commit:$path")).replace("\r\n", "\n")
                    val hunks = CorpusSupport.git(repository, listOf("diff", "-U0", parent, commit, "--", path)).lines().count { it.startsWith("@@") }
                    val request = ComparisonRequest(old, new, languageId, File(path).name)
                    val start = System.nanoTime()
                    val result = CorpusSupport.onPooledThread { compareSafely(engine, request, Cancellation.NONE) }
                    val millis = (System.nanoTime() - start) / 1_000_000
                    rows += Row(repository.name, commit.take(10), path, languageId, old, new, hunks, result, millis, flags(old, new, hunks, result, millis))
                }
            }
        }
        write(rows)
    }

    private class Row(
        val repository: String, val commit: String, val path: String, val languageId: String, val old: String, val new: String,
        val hunks: Int, val result: ComparisonResult, val millis: Long, val flags: List<String>,
    )

    private fun flags(old: String, new: String, hunks: Int, result: ComparisonResult, millis: Long): List<String> = buildList {
        if (result.status != Status.ANALYZED) add("status")
        if (result.status == Status.ANALYZED || result.status == Status.PARTIAL) {
            if (NothingHidden.violation(old, new, result) != null) add("hidden")
            if (old != new && !result.hasReportedChanges) add("empty")
        }
        val nodes = flatten(result.changes)
        val removed = nodes.filter { it.presence == Presence.REMOVED }.mapNotNull { it.subject as? Subject.Declaration }
        val added = nodes.filter { it.presence == Presence.ADDED }.mapNotNull { it.subject as? Subject.Declaration }
        if (removed.any { r -> added.any { a -> a.kind == r.kind && a.name == r.name } }) add("same-name")
        if (result.unanalyzed.isNotEmpty()) add("unanalyzed")
        if (reported(result) > 2 * hunks + 2) add("noisy")
        if (millis > 1000) add("slow")
    }

    private fun flatten(nodes: List<ChangeNode>): List<ChangeNode> = nodes.flatMap { listOf(it) + flatten(it.children) }

    private fun reported(result: ComparisonResult): Int =
        flatten(result.changes).count { it.isChangedItself } + (if (result.fileRegion?.isChangedItself == true) 1 else 0) + result.unanalyzed.size

    private fun write(rows: List<Row>) {
        val directory = File("build/reports/smartdiff-treesitter-validation").apply { mkdirs() }
        File(directory, "summary.tsv").writeText(buildString {
            appendLine("repository\tcommit\tpath\tlanguage\tstatus\thunks\treported\tms\tflags")
            for (row in rows) {
                appendLine(listOf(row.repository, row.commit, row.path, row.languageId, row.result.status, row.hunks,
                    reported(row.result), row.millis, row.flags.joinToString(",")).joinToString("\t"))
            }
        })
        val byLanguage = rows.groupBy { it.languageId }.toSortedMap()
        File(directory, "report.md").writeText(buildString {
            appendLine("# SMART Diff Tree-sitter corpus validation")
            appendLine()
            appendLine("Engine: ${TreeSitterEngine.ID} ${TreeSitterEngine.VERSION}")
            appendLine()
            appendLine("| Language | Files | Commits | ANALYZED | PARTIAL | Fallback | Flagged |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- |")
            for ((language, group) in byLanguage) {
                appendLine("| $language | ${group.size} | ${group.map { it.repository + it.commit }.distinct().size} | " +
                    "${group.count { it.result.status == Status.ANALYZED }} | ${group.count { it.result.status == Status.PARTIAL }} | " +
                    "${group.count { it.result.status != Status.ANALYZED && it.result.status != Status.PARTIAL }} | ${group.count { it.flags.isNotEmpty() }} |")
            }
            appendLine()
            for (flag in rows.flatMap { it.flags }.distinct().sorted()) appendLine("- $flag: ${rows.count { flag in it.flags }}")
            for (row in rows.filter { it.flags.isNotEmpty() }) {
                appendLine()
                appendLine("## ${row.repository} ${row.commit} ${row.path}")
                appendLine()
                appendLine("Flags: ${row.flags.joinToString(", ")}; ${row.millis} ms; ${row.hunks} native hunks")
                appendLine()
                appendLine("```")
                append(Rendering.result(row.result, row.old, row.new))
                appendLine("```")
            }
        })
        for ((language, group) in byLanguage) {
            println(
                "TREE-SITTER VALIDATION $language files=${group.size} analyzed=${group.count { it.result.status == Status.ANALYZED }} " +
                    "partial=${group.count { it.result.status == Status.PARTIAL }} " +
                    "fallback=${group.count { it.result.status != Status.ANALYZED && it.result.status != Status.PARTIAL }} " +
                    "hidden=${group.count { "hidden" in it.flags }} empty=${group.count { "empty" in it.flags }} " +
                    "flagged=${group.count { it.flags.isNotEmpty() }}",
            )
        }
        println("TREE-SITTER VALIDATION ${rows.size} files; report in ${directory.absolutePath}")
    }

    private fun languageId(path: String): String? {
        val name = path.lowercase()
        return when {
            name.endsWith(".swift") -> LanguageIds.TREE_SITTER_SWIFT
            name.endsWith(".cs") -> LanguageIds.TREE_SITTER_C_SHARP
            name.endsWith(".c") -> LanguageIds.TREE_SITTER_C
            CPP.any { name.endsWith(it) } -> LanguageIds.TREE_SITTER_CPP
            else -> null
        }
    }

    private companion object {
        const val PROPERTY = "smartdiff.validation.treesitter.corpus"
        val CPP = listOf(".cpp", ".cc", ".cxx", ".c++", ".h", ".hpp", ".hh", ".hxx")
        val GLOBS = listOf("*.swift", "*.cs", "*.c") + CPP.map { "*$it" }
    }
}
