package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.compareSafely
import java.io.File
import java.nio.file.Files
import java.util.BitSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The validation of fine-grained differencing against Difftastic and GumTree (declarations/10 R21, R22): for every
 * modified Java, Kotlin, Scala and Python file of the listed commits, every declaration the declaration engine compared
 * by tokens and found changed is measured on its own characters (its range minus its children's) on both sides:
 * the characters inside our changed runs, inside the native word highlights (ComparisonManager by words with the
 * default policy over the whole file), inside Difftastic's changed tokens and inside GumTree's changed nodes. Only
 * non-whitespace characters count.
 *
 * The commits come from `-Psmartdiff.fineGrained.corpus=<list>`, in the list format of [CorpusValidationTest]; without
 * it the test only says it was skipped, so CI needs neither the corpus nor the tools. The `fineGrainedBaselines` Gradle
 * task runs it and passes `smartdiff.fineGrained.difft` and `smartdiff.fineGrained.gumtree`, the tools' paths.
 * GumTree has no Scala parser, so Scala declarations are measured against Difftastic alone.
 *
 * The report goes to `build/reports/smartdiff-fine-grained/`: `declarations.tsv` with one row per declaration,
 * `report.md` with the medians per language, the declarations our runs highlight more of than every baseline and every
 * declaration with missed characters, and `review.md` with the 30 declarations per language the tools disagree on most.
 * A missed character is one every available baseline (Difftastic and GumTree) marks changed and our runs do not.
 */
class FineGrainedBaselinesTest : BasePlatformTestCase() {

    fun `test fine-grained baselines`() {
        val list = System.getProperty("smartdiff.fineGrained.corpus")?.takeIf { it.isNotBlank() }
        if (list == null) {
            println("VALIDATION fine-grained skipped: no corpus (run ./gradlew fineGrainedBaselines -Psmartdiff.fineGrained.corpus=<list>)")
            return
        }
        val difft = tool("smartdiff.fineGrained.difft", "difft")
        val gumtree = tool("smartdiff.fineGrained.gumtree", "gumtree")
        val engine = DeclarationEngine(null)
        val jobs = mutableListOf<FileJob>()
        for (words in CorpusSupport.listLines(File(list))) {
            val repository = File(words.first())
            val commits = CorpusSupport.git(repository, listOf("rev-list") + words.drop(1) + listOf("--") + PATTERNS).lines().filter { it.isNotBlank() }
            for ((index, commit) in commits.withIndex()) {
                println("VALIDATION fine-grained ${repository.name} commit ${index + 1} of ${commits.size}: ${commit.take(10)}")
                val parent = CorpusSupport.git(repository, listOf("rev-parse", "--verify", "--quiet", "$commit^"), allowFailure = true).trim()
                if (parent.isEmpty()) continue
                val paths = CorpusSupport.git(repository, listOf("diff", "--no-renames", "--name-only", "--diff-filter=M", parent, commit, "--") + PATTERNS)
                    .lines().filter { it.isNotBlank() }
                for (path in paths) {
                    val old = CorpusSupport.git(repository, listOf("show", "$parent:$path")).replace("\r\n", "\n")
                    val new = CorpusSupport.git(repository, listOf("show", "$commit:$path")).replace("\r\n", "\n")
                    val languageId = when {
                        path.endsWith(".kt") -> "kotlin"
                        path.endsWith(".py") -> "Python"
                        path.endsWith(".scala") -> "Scala"
                        else -> "JAVA"
                    }
                    val language = when (languageId) {
                        "kotlin" -> "Kotlin"
                        "Python" -> "Python"
                        "JAVA" -> "Java"
                        else -> if (CorpusSupport.dialect(languageId, old, new)?.contains("3") == true) "Scala 3" else "Scala 2"
                    }
                    val result = CorpusSupport.onPooledThread {
                        compareSafely(engine, ComparisonRequest(old, new, languageId, File(path).name), Cancellation.NONE)
                    }
                    val nodes = compared(result.changes, emptyList())
                    if (nodes.isEmpty()) continue
                    val all = flatten(result.changes)
                    jobs += FileJob(repository.name, commit.take(10), path, language, old, new, nodes, native(old, new),
                        all.mapNotNull { it.oldRange }.distinct(), all.mapNotNull { it.newRange }.distinct())
                }
            }
        }
        println("VALIDATION fine-grained running the tools on ${jobs.size} files")
        val pool = Executors.newFixedThreadPool(4)
        val futures = jobs.map { job -> pool.submit { job.runTools(difft, gumtree) } }
        futures.forEachIndexed { index, future ->
            future.get(30, TimeUnit.MINUTES)
            if ((index + 1) % 50 == 0) println("VALIDATION fine-grained tools done for ${index + 1} of ${jobs.size} files")
        }
        pool.shutdown()
        val rows = jobs.flatMap { it.rows() }
        write(rows, jobs, versions(difft, gumtree))
    }

    private fun tool(property: String, name: String): String {
        val path = System.getProperty(property)?.takeIf { it.isNotBlank() }
            ?: System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, name) }.firstOrNull { it.canExecute() }?.path
        check(path != null && File(path).canExecute()) { "$name not found: install it and put it on the PATH, or run ./gradlew fineGrainedBaselines" }
        return path
    }

    private fun versions(difft: String, gumtree: String): String {
        val dft = runCatching { run(listOf(difft, "--version"), null, 60).lines().first() }.getOrDefault("?")
        val gum = File(gumtree).canonicalFile.parentFile?.parentFile?.name ?: gumtree
        return "$dft; GumTree from $gum"
    }

    /** A changed declaration compared by tokens, with its ancestors' names for the report. */
    private class Compared(val node: ChangeNode, val label: String)

    private fun compared(nodes: List<ChangeNode>, path: List<String>): List<Compared> = nodes.flatMap { node ->
        val name = (node.subject as? Subject.Declaration)?.let { "${it.kind.label()} ${it.name}" } ?: node.subject.toString()
        val here = path + name
        val self = node.takeIf {
            it.presence == Presence.MATCHED && it.content == Content.CHANGED && it.comparedByTokens && it.oldRange != null && it.newRange != null
        }?.let { Compared(it, here.joinToString(" > ")) }
        listOfNotNull(self) + compared(node.children, here)
    }

    private fun flatten(nodes: List<ChangeNode>): List<ChangeNode> = nodes.flatMap { listOf(it) + flatten(it.children) }

    /** The native word highlights: inner fragments of each line fragment, or its whole lines when it has none. */
    private fun native(old: String, new: String): BaselineMarks {
        val marks = BaselineMarks()
        val fragments = ComparisonManager.getInstance().compareLinesInner(old, new, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
        for (fragment in fragments) {
            val inner = fragment.innerFragments
            if (inner == null) {
                marks.old.set(fragment.startOffset1, fragment.endOffset1)
                marks.new.set(fragment.startOffset2, fragment.endOffset2)
            } else {
                inner.forEach {
                    marks.old.set(fragment.startOffset1 + it.startOffset1, fragment.startOffset1 + it.endOffset1)
                    marks.new.set(fragment.startOffset2 + it.startOffset2, fragment.startOffset2 + it.endOffset2)
                }
            }
        }
        return marks
    }

    private class FileJob(
        val repository: String, val commit: String, val path: String, val language: String,
        val old: String, val new: String, val nodes: List<Compared>, val native: BaselineMarks,
        /** Every node's range on each side: a declaration's own characters leave out every declaration inside it. */
        val allOld: List<TextRange>, val allNew: List<TextRange>,
    ) {
        var difftastic: BaselineMarks? = null
        var difftasticLanguage: String = ""
        var gumtree: BaselineMarks? = null
        var gumtreeNote: String = ""

        fun runTools(difft: String, gumtreePath: String) {
            val directory = Files.createTempDirectory("smartdiff-fine-grained").toFile()
            try {
                val name = File(path).name
                val oldFile = File(directory, "old/$name").apply { parentFile.mkdirs(); writeText(old) }
                val newFile = File(directory, "new/$name").apply { parentFile.mkdirs(); writeText(new) }
                runCatching {
                    val json = run(listOf(difft, "--display", "json", oldFile.path, newFile.path), mapOf("DFT_UNSTABLE" to "yes"), 300)
                    val parsed = DifftasticJson.parse(json, old, new)
                    difftasticLanguage = parsed.language
                    if (parsed.structural) difftastic = parsed.marks
                }.onFailure { difftasticLanguage = "failed: ${it.message?.take(80)}" }
                if (language.startsWith("Scala")) {
                    gumtreeNote = "not supported by GumTree"
                } else {
                    runCatching {
                        val json = run(listOf(gumtreePath, "textdiff", "-f", "JSON", oldFile.path, newFile.path), mapOf("JAVA_OPTS" to "-Xmx2g"), 300)
                        gumtree = GumTreeJson.parse(json, old, new, endIsByteLength = language != "Java")
                    }.onFailure { gumtreeNote = "failed: ${it.message?.take(80)}" }
                }
            } finally {
                directory.deleteRecursively()
            }
        }

        private fun inside(range: TextRange, all: List<TextRange>): List<TextRange> =
            all.filter { it != range && it.start >= range.start && it.end <= range.end }

        fun rows(): List<Row> = nodes.map { compared ->
            val node = compared.node
            // A child moved to another parent is reported under that parent, so every node inside the range counts.
            val ownOld = CharSets.own(node.oldRange!!, inside(node.oldRange, allOld))
            val ownNew = CharSets.own(node.newRange!!, inside(node.newRange, allNew))
            fun sides(old: BitSet, new: BitSet) = Pair(CharSets.restrict(old, ownOld, this.old), CharSets.restrict(new, ownNew, this.new))
            val ours = sides(CharSets.of(node.changedRuns.mapNotNull { it.old }), CharSets.of(node.changedRuns.mapNotNull { it.new }))
            val native = sides(native.old, native.new)
            val difft = difftastic?.let { sides(it.old, it.new) }
            val gum = gumtree?.let { sides(it.old, it.new) }
            val baselines = listOfNotNull(difft, gum)
            val missed = Pair(CharSets.missed(ours.first, baselines.map { it.first }), CharSets.missed(ours.second, baselines.map { it.second }))
            val renamed = (node.renamedFrom as? Subject.Declaration)?.name
            val name = (node.subject as? Subject.Declaration)?.name
            val names = listOfNotNull(renamed, name).takeIf { renamed != null }.orEmpty()
            val outsideOld = CharSets.outside(this.old, ownOld, ours.first, names)
            val outsideNew = CharSets.outside(this.new, ownNew, ours.second, names)
            Row(this, compared.label, ownOld, ownNew, ours, native, difft, gum, missed, outsideOld == outsideNew, difference(outsideOld, outsideNew))
        }
    }

    private class Row(
        val file: FileJob, val declaration: String, val ownOld: BitSet, val ownNew: BitSet,
        val ours: Pair<BitSet, BitSet>, val native: Pair<BitSet, BitSet>,
        val difft: Pair<BitSet, BitSet>?, val gumtree: Pair<BitSet, BitSet>?, val missed: Pair<BitSet, BitSet>,
        /** The text outside our runs is the same on both sides, so every edit lies in a run. */
        val consistent: Boolean,
        /** Where the texts outside the runs first differ, for the report. */
        val difference: String,
    ) {
        val ownChars: Int = CharSets.restrict(ownOld, ownOld, file.old).cardinality() + CharSets.restrict(ownNew, ownNew, file.new).cardinality()
        fun count(sets: Pair<BitSet, BitSet>?): Int? = sets?.let { it.first.cardinality() + it.second.cardinality() }
        fun share(sets: Pair<BitSet, BitSet>?): Double? = count(sets)?.let { if (ownChars == 0) 0.0 else it.toDouble() / ownChars }
        val missedChars: Int get() = count(missed)!!
        val baselineShares: List<Double> get() = listOfNotNull(share(difft), share(gumtree))

        /** Our runs highlight more than every available baseline. */
        val overHighlights: Boolean get() = baselineShares.isNotEmpty() && baselineShares.all { share(ours)!! > it }

        /** How far our share is from the nearer baseline: both disagree with us by at least this much. */
        val gap: Double get() = baselineShares.minOfOrNull { kotlin.math.abs(share(ours)!! - it) } ?: 0.0
    }

    private fun write(rows: List<Row>, jobs: List<FileJob>, versions: String) {
        val directory = File("build/reports/smartdiff-fine-grained").apply { mkdirs() }
        File(directory, "declarations.tsv").writeText(buildString {
            appendLine("repository\tcommit\tpath\tlanguage\tdeclaration\town_old\town_new\tours\tnative\tdifftastic\tgumtree\tmissed\toutside_runs_equal")
            for (row in rows) {
                appendLine(listOf(row.file.repository, row.file.commit, row.file.path, row.file.language, row.declaration,
                    CharSets.restrict(row.ownOld, row.ownOld, row.file.old).cardinality(),
                    CharSets.restrict(row.ownNew, row.ownNew, row.file.new).cardinality(),
                    row.count(row.ours), row.count(row.native), row.count(row.difft) ?: "-", row.count(row.gumtree) ?: "-", row.missedChars, row.consistent,
                ).joinToString("\t"))
            }
        })
        val languages = listOf("Java", "Kotlin", "Scala 2", "Scala 3", "Python")
        fun pct(value: Double) = "%.1f%%".format(value * 100)
        File(directory, "report.md").writeText(buildString {
            appendLine("# Fine-grained differencing against Difftastic and GumTree")
            appendLine()
            appendLine("Tools: $versions. ${rows.size} changed declarations compared by tokens in ${jobs.size} files.")
            appendLine("Shares are of each declaration's own non-whitespace characters, both sides together.")
            appendLine()
            appendLine("| Language | Files | Declarations | Ours | Native | Difftastic | GumTree | Missed > 0 | Ours > baselines | Outside runs differ | Difftastic fallback | GumTree unavailable |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
            for (language in languages) {
                val these = rows.filter { it.file.language == language }
                val files = jobs.filter { it.language == language }
                if (these.isEmpty()) continue
                fun median(f: (Row) -> Double?) = these.mapNotNull(f).takeIf { it.isNotEmpty() }?.let { pct(CharSets.median(it)) } ?: "-"
                appendLine("| $language | ${files.size} | ${these.size} | ${median { it.share(it.ours) }} | ${median { it.share(it.native) }} | " +
                    "${median { it.share(it.difft) }} | ${median { it.share(it.gumtree) }} | ${these.count { it.missedChars > 0 }} | " +
                    "${these.count { it.overHighlights }} | ${these.count { !it.consistent }} | ${files.count { it.difftastic == null }} | ${files.count { it.gumtree == null }} |")
            }
            appendLine()
            appendLine("## Difftastic fallbacks and GumTree failures")
            appendLine()
            jobs.filter { it.difftastic == null }.forEach { appendLine("- ${it.repository} ${it.commit} ${it.path}: Difftastic ${it.difftasticLanguage}") }
            jobs.filter { it.gumtree == null && !it.language.startsWith("Scala") }.forEach { appendLine("- ${it.repository} ${it.commit} ${it.path}: GumTree ${it.gumtreeNote}") }
            appendLine()
            appendLine("## Our runs highlight more than every baseline")
            appendLine()
            appendLine("| Language | Declaration | Ours | Native | Difftastic | GumTree |")
            appendLine("| --- | --- | --- | --- | --- | --- |")
            for (row in rows.filter { it.overHighlights }.sortedByDescending { it.gap }) {
                appendLine("| ${row.file.language} | ${row.file.repository} ${row.file.commit} `${row.file.path}` ${row.declaration} | ${pct(row.share(row.ours)!!)} | " +
                    "${pct(row.share(row.native)!!)} | ${row.share(row.difft)?.let(::pct) ?: "-"} | ${row.share(row.gumtree)?.let(::pct) ?: "-"} |")
            }
            appendLine()
            appendLine("## Declarations whose text outside the runs differs between the sides")
            for (row in rows.filter { !it.consistent }) snippet(row)
            appendLine()
            appendLine("## Declarations with missed characters")
            for (row in rows.filter { it.missedChars > 0 }.sortedByDescending { it.missedChars }) snippet(row)
        })
        File(directory, "review.md").writeText(buildString {
            appendLine("# Review set: 30 declarations per language where the tools disagree most")
            appendLine()
            appendLine("Ordered by missed characters, then by the gap between our share and the nearer baseline.")
            for (language in languages) {
                val chosen = rows.filter { it.file.language == language }
                    .sortedWith(compareByDescending<Row> { it.missedChars }.thenByDescending { it.gap }).take(30)
                if (chosen.isEmpty()) continue
                appendLine()
                appendLine("# $language")
                chosen.forEachIndexed { index, row -> snippet(row, "${index + 1}. ") }
            }
        })
        println("VALIDATION fine-grained ${rows.size} declarations in ${jobs.size} files; ${rows.count { it.missedChars > 0 }} with missed characters; report in ${directory.absolutePath}")
    }

    private fun StringBuilder.snippet(row: Row, prefix: String = "") {
        fun pct(value: Double?) = value?.let { "%.1f%%".format(it * 100) } ?: "-"
        appendLine()
        appendLine("### $prefix${row.file.language}: ${row.file.repository} ${row.file.commit} `${row.file.path}` ${row.declaration}")
        appendLine()
        appendLine("Own ${row.ownChars} chars; ours ${pct(row.share(row.ours))}, native ${pct(row.share(row.native))}, " +
            "Difftastic ${pct(row.share(row.difft))}, GumTree ${pct(row.share(row.gumtree))}; missed ${row.missedChars}; " +
            "text outside the runs ${if (row.consistent) "equal" else "DIFFERS"} on the two sides.")
        appendLine()
        if (!row.consistent) appendLine("Outside the runs: ${row.difference}").appendLine()
        appendLine("```text")
        for ((side, text, own, pick) in listOf(
            Side("old", row.file.old, row.ownOld) { p: Pair<BitSet, BitSet> -> p.first },
            Side("new", row.file.new, row.ownNew) { p: Pair<BitSet, BitSet> -> p.second },
        )) {
            appendLine("--- $side")
            val marks = listOfNotNull("ours" to pick(row.ours), "nat" to pick(row.native), row.difft?.let { "dft" to pick(it) },
                row.gumtree?.let { "gum" to pick(it) }, "MISS" to pick(row.missed))
            append(CharSets.snippet(text, own, marks))
        }
        appendLine("```")
    }

    private data class Side(val name: String, val text: String, val own: BitSet, val pick: (Pair<BitSet, BitSet>) -> BitSet)

    companion object {
        /** The texts around their first difference, as `old … | new …`. */
        fun difference(old: String, new: String): String {
            val at = old.commonPrefixWith(new).length
            val from = (at - 20).coerceAtLeast(0)
            return "old `${old.substring(from, (at + 40).coerceAtMost(old.length))}` | new `${new.substring(from, (at + 40).coerceAtMost(new.length))}`"
        }

        private val PATTERNS = listOf("*.java", "*.kt", "*.scala", "*.py")

        /** Runs [command] and returns its standard output; fails on a timeout. A non-zero exit is not a failure: Difftastic may use one. */
        fun run(command: List<String>, environment: Map<String, String>?, timeoutSeconds: Long): String {
            val builder = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
            environment?.let { builder.environment().putAll(it) }
            val process = builder.start()
            val output = StringBuilder()
            val reader = Thread { output.append(process.inputStream.bufferedReader().readText()) }.apply { start() }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("${File(command.first()).name} timed out after $timeoutSeconds s")
            }
            reader.join()
            return output.toString()
        }
    }
}
