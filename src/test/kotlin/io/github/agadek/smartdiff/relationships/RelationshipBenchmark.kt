package io.github.agadek.smartdiff.relationships

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.compareSafely
import io.github.agadek.smartdiff.engine.api.contract.NothingHidden
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationProvider
import io.github.agadek.smartdiff.ui.OutlineModel
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * The structural relationships benchmark: labelled cases, real commits, current-engine adapter, metrics, gates and
 * reports. The requirements are in `docs/specs/relationships/01-relationship-benchmark.md`; the design and the test
 * table are in its details file.
 */
object RelationshipBenchmark {

    const val CORPUS_PROPERTY = "smartdiff.relationships.corpus"
    const val GATE_PROPERTY = "smartdiff.relationships.gate"
    const val BASELINE_PROPERTY = "smartdiff.relationships.baseline"
    const val TOLERANCE_PROPERTY = "smartdiff.relationships.tolerance"
    const val DRAFTS_PROPERTY = "smartdiff.relationships.drafts"
    const val DEFAULT_GATE = "no-new-wrong-pairs"
    const val TIME_LIMIT_MILLIS = 5000L

    /** R9: cases per category and corpus. */
    const val MINIMUM_CASES = 3

    /** R10: labelled real commits per corpus. */
    const val MINIMUM_COMMITS = 30

    /** Where the checked-in case and real-commit labels live. */
    const val DEFAULT_LABEL_ROOT = "src/test/testData"

    /** The corpora of R33; Scala 2 and Scala 3 are separate languages in every count. */
    val CORPORA = listOf("java", "kotlin", "scala2", "scala3")

    /**
     * The case matrix of R8, as kebab-case categories a label names in `category:` (the details file lists them as
     * "method rename", "move in the file" and so on).
     */
    val CATEGORIES = listOf(
        "method-rename",
        "class-rename",
        "field-rename",
        "rename-with-body-change",
        "rename-with-signature-change",
        "move-in-file",
        "move-with-rename",
        "move-with-body-change",
        "kind-change",
        "signature-change",
        "overloads-same-body",
        "duplicate-bodies",
        "extraction",
        "extraction-many-sources",
        "extraction-many-targets",
        "inlining",
        "statement-reorder",
        "formatting-only",
        "comment-only",
        "string-literal-change",
        "parse-error-one-side",
        "malformed-both-sides",
        "empty-identical",
        "large-file",
        "ambiguous-rename-group",
    )

    /** The category that a checked-in real-commit label carries; it is outside the case matrix. */
    const val REAL_COMMIT_CATEGORY = "real-commit"

    /**
     * Categories a corpus does not have the construct for, with the reason the report shows (R30). Empty while every
     * matrix category names a construct that Java, Kotlin, Scala 2 and Scala 3 all have; the mechanism keeps a future
     * category from being dropped without a stated reason.
     */
    val NOT_APPLICABLE: Map<String, Map<String, String>> = emptyMap()

    enum class Kind { RENAME, MOVE, KIND_CHANGE, EXTRACT, INLINE }
    enum class Confidence { HIGH, MEDIUM, LOW }
    enum class Split { TUNING, HELD_OUT }

    data class Endpoint(
        val path: String,
        val kind: DeclarationKind,
        val name: String,
        val signature: String?,
        val range: TextRange? = null,
    ) {
        fun sameIdentity(other: Endpoint): Boolean =
            path == other.path && kind == other.kind && name == other.name && displaySignature() == other.displaySignature()

        /** The outline shows a method's signature in parentheses; accept either the raw or displayed form in labels. */
        private fun displaySignature(): String? = signature?.let { if (it.startsWith("(")) it else "($it)" }

        /** A stable key for the baseline and report lists, including what tells duplicates apart. */
        fun key(): String =
            listOf(path, kind.name, name, displaySignature() ?: "-", range?.toString() ?: "-").joinToString("|")
    }

    data class LabelledRelationship(
        val kinds: Set<Kind>,
        val old: Endpoint,
        val new: Endpoint,
        val confidence: Confidence?,
    )

    data class ExpectedChange(
        val subject: Endpoint,
        val presence: Presence,
        val content: Content,
        val moved: Boolean? = null,
        val signatureChanged: Boolean? = null,
        val kindChangedFrom: DeclarationKind? = null,
    )

    /** The commit a real-commit label belongs to (R35). */
    data class CommitSource(val repository: String, val commit: String, val path: String)

    data class Label(
        val case: String,
        val language: String,
        val split: Split,
        val relationships: List<LabelledRelationship>,
        val changes: List<ExpectedChange>,
        val ambiguous: List<Endpoint>,
        val notes: String?,
        val category: String = case.substringBeforeLast('-'),
        val corpus: String = language.lowercase(),
        /** A checked-in case is reviewed; a real-commit label is scored only when it says `reviewed: true` (R36, R37). */
        val reviewed: Boolean = true,
        val source: CommitSource? = null,
        val path: String? = null,
    )

    data class ActualRelationship(
        val kind: Kind,
        val old: Endpoint,
        val new: Endpoint,
        val confidence: Confidence,
    )

    data class CaseResult(
        val label: Label,
        val result: ComparisonResult,
        val actualRelationships: List<ActualRelationship>,
        val actualNodes: List<Pair<Endpoint, ChangeNode>>,
        val millis: Long,
        val hidden: Boolean,
        val exception: String? = null,
        /** The repository, commit and path of a real-commit case, for the report. */
        val source: String? = null,
    )

    data class CorpusEntry(val path: File?, val repository: File?, val arguments: List<String>, val line: String)

    /** One file a real-commit line selected but could not score. */
    data class UnlabelledFile(
        val repository: String,
        val commit: String,
        val path: String,
        val corpus: String,
        val reason: String,
    ) {
        val id: String get() = "$repository ${commit.take(7)} $path"
    }

    /** One category and corpus, or one corpus's labelled commits, below its minimum (R30). */
    data class MatrixFailure(
        val corpus: String,
        val scope: String,
        val count: Int,
        val minimum: Int,
        val unit: String = "cases",
    ) {
        val message: String get() = "$corpus $scope: $count $unit, need $minimum"
    }

    data class PairCounts(var tp: Int = 0, var fp: Int = 0, var fn: Int = 0) {
        val precision: Double get() = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
        val recall: Double get() = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn)
        val wrongShare: Double get() = if (tp + fp == 0) 0.0 else fp.toDouble() / (tp + fp)
    }

    data class Metrics(
        val perKind: Map<String, PairCounts>,
        val forcedPairs: Int,
        val classificationCorrect: Int,
        val classificationTotal: Int,
        val hidden: Int,
        val fallback: Int,
        val times: List<Long>,
        val presenceCorrect: Int = 0,
        val presenceTotal: Int = 0,
        val contentCorrect: Int = 0,
        val contentTotal: Int = 0,
        val movedCorrect: Int = 0,
        val movedTotal: Int = 0,
        val signatureCorrect: Int = 0,
        val signatureTotal: Int = 0,
        val kindCorrect: Int = 0,
        val kindTotal: Int = 0,
    ) {
        val classificationAccuracy: Double get() = if (classificationTotal == 0) 1.0 else classificationCorrect.toDouble() / classificationTotal
        val presenceAccuracy: Double get() = accuracy(presenceCorrect, presenceTotal)
        val contentAccuracy: Double get() = accuracy(contentCorrect, contentTotal)
        val movedAccuracy: Double get() = accuracy(movedCorrect, movedTotal)
        val signatureAccuracy: Double get() = accuracy(signatureCorrect, signatureTotal)
        val kindAccuracy: Double get() = accuracy(kindCorrect, kindTotal)
        private fun accuracy(correct: Int, total: Int): Double = if (total == 0) 1.0 else correct.toDouble() / total
        val medianMillis: Long get() = median(times)
        val p95Millis: Long get() = percentile(times, 0.95)
        val slowestMillis: Long get() = times.maxOrNull() ?: 0
        val precision: Double get() = aggregate(perKind.values).precision
        val recall: Double get() = aggregate(perKind.values).recall
        val wrongShare: Double get() = aggregate(perKind.values).wrongShare
    }

    data class Report(
        val cases: List<CaseResult>,
        val metricsByLanguage: Map<String, Metrics>,
        val metricsBySplit: Map<Split, Metrics>,
        val overall: Metrics,
        val gateMode: String,
        val gateFailures: List<String>,
        val corpusRevision: String,
        val reportDirectory: File,
        val skippedCommitLines: Int = 0,
        val smoke: Boolean = false,
        val matrixFailures: List<MatrixFailure> = emptyList(),
        val labelledCommits: Map<String, Int> = emptyMap(),
        val notApplicable: List<String> = emptyList(),
        val unlabelled: List<UnlabelledFile> = emptyList(),
        val skippedFiles: List<String> = emptyList(),
        val newGatePairs: List<String> = emptyList(),
        val draftsWritten: Int = 0,
        /** R17 counts unlabelled files too, since R34 compares them. */
        val unlabelledHidden: Int = 0,
        val unlabelledFallback: Int = 0,
    ) {
        val ready: Boolean get() = gateFailures.isEmpty()
    }

    fun listFile(value: String?): File? = value?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)

    fun parseCorpus(file: File): List<CorpusEntry> = file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val directory = File(line)
            if (words(line).size == 1 && directory.isDirectory) {
                CorpusEntry(directory, null, emptyList(), line)
            } else {
                val words = words(line)
                if (words.size < 2) {
                    error("corpus line '$line' is neither a case folder nor a repository with git arguments")
                }
                CorpusEntry(null, File(words.first()), words.drop(1), line)
            }
        }

    private fun words(line: String): List<String> = line.split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun parseLabel(file: File): Label {
        val root = try {
            Yaml().load<Map<String, Any?>>(file.reader())
        } catch (e: Exception) {
            error("label ${file.path}: ${e.message}")
        } ?: error("label ${file.path}: empty label")
        val case = root["case"] as? String ?: error("label ${file.path}: case is required")
        val language = root["language"] as? String ?: error("label ${file.path}: language is required")
        val corpus = (root["corpus"] as? String)?.lowercase() ?: defaultCorpus(language, file)
        require(corpus in CORPORA) { "label ${file.path}: unknown corpus $corpus" }
        val category = root["category"] as? String ?: error("label ${file.path}: category is required")
        val source = (root["source"] as? Map<*, *>)?.let { value ->
            val map = value.entries.associate { it.key.toString() to it.value }
            CommitSource(
                repository = map["repository"] as? String ?: error("label ${file.path}: source repository is required"),
                commit = map["commit"] as? String ?: error("label ${file.path}: source commit is required"),
                path = map["path"] as? String ?: error("label ${file.path}: source path is required"),
            )
        }
        val expected = map(root["expected"], file)
        return Label(
            case = case,
            language = language,
            split = when ((root["split"] as? String)?.lowercase()) {
                null, "tuning" -> Split.TUNING
                "held-out", "heldout" -> Split.HELD_OUT
                else -> error("label ${file.path}: unknown split ${root["split"]}")
            },
            relationships = list(expected["relationships"]).map { relationship(it, file) },
            changes = list(expected["changes"]).map { change(it, file) },
            ambiguous = list(expected["ambiguous"]).map { endpoint(it, file) },
            notes = root["notes"] as? String,
            category = category,
            corpus = corpus,
            // A real-commit label is scored only when it says so; a draft says `reviewed: false` (R37).
            reviewed = root["reviewed"] as? Boolean ?: (source == null),
            source = source,
            path = file.path,
        )
    }

    private fun defaultCorpus(language: String, file: File): String = when (language.uppercase()) {
        "JAVA" -> "java"
        "KOTLIN" -> "kotlin"
        else -> error("label ${file.path}: corpus is required for $language, so Scala 2 and Scala 3 stay apart")
    }

    private fun relationship(value: Any?, file: File): LabelledRelationship {
        val map = map(value, file)
        val kinds = list(map["kinds"]).map { kind(it, file) }.toSet()
        require(kinds.isNotEmpty()) { "label ${file.path}: relationship kinds are required" }
        return LabelledRelationship(
            kinds,
            endpoint(map["old"], file),
            endpoint(map["new"], file),
            (map["confidence"] as? String)?.uppercase()?.let { Confidence.valueOf(it) },
        )
    }

    private fun change(value: Any?, file: File): ExpectedChange {
        val map = map(value, file)
        return ExpectedChange(
            endpoint(map["subject"], file),
            Presence.valueOf((map["presence"] as? String ?: "MATCHED").uppercase()),
            Content.valueOf((map["content"] as? String ?: "UNCHANGED").uppercase().replace('-', '_')),
            map["moved"] as? Boolean,
            map["signatureChanged"] as? Boolean,
            (map["kindChangedFrom"] as? String)?.uppercase()?.let { DeclarationKind.valueOf(it) },
        )
    }

    private fun endpoint(value: Any?, file: File): Endpoint {
        val map = map(value, file)
        val range = (map["range"] as? String)?.let {
            val parts = it.split("..")
            require(parts.size == 2) { "label ${file.path}: range must be start..end" }
            TextRange(parts[0].toInt(), parts[1].toInt())
        }
        return Endpoint(
            path = map["path"] as? String ?: "",
            kind = DeclarationKind.valueOf((map["kind"] as? String ?: error("label ${file.path}: endpoint kind is required")).uppercase()),
            name = map["name"] as? String ?: error("label ${file.path}: endpoint name is required"),
            signature = map["signature"] as? String,
            range = range,
        )
    }

    private fun kind(value: Any?, file: File): Kind =
        Kind.valueOf((value as? String ?: error("label ${file.path}: relationship kind is required")).uppercase().replace('-', '_'))

    private fun map(value: Any?, file: File): Map<String, Any?> =
        value as? Map<String, Any?> ?: error("label ${file.path}: expected a mapping, got $value")

    private fun list(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

    fun actualRelationships(result: ComparisonResult): List<ActualRelationship> {
        val relationships = mutableListOf<ActualRelationship>()
        fun visit(node: ChangeNode, ancestors: List<Subject>) {
            val path = ancestors.joinToString(" / ") { OutlineModel.title(it) }
            val old = { subject: Subject, range: TextRange? -> endpoint(subject, path, range) }
            val new = { range: TextRange? -> endpoint(node.subject, path, range) }
            node.renamedFrom?.let { relationships += ActualRelationship(Kind.RENAME, old(it, node.oldRange), new(node.newRange), Confidence.MEDIUM) }
            if (node.moved) relationships += ActualRelationship(Kind.MOVE, old(node.subject, node.oldRange), new(node.newRange), Confidence.HIGH)
            node.kindChangedFrom?.let {
                val before = Subject.Declaration(it, (node.renamedFrom as? Subject.Declaration)?.name ?: (node.subject as Subject.Declaration).name, (node.subject as Subject.Declaration).signature)
                relationships += ActualRelationship(Kind.KIND_CHANGE, old(before, node.oldRange), new(node.newRange), Confidence.HIGH)
            }
            node.extractedFrom.forEach { relationships += ActualRelationship(Kind.EXTRACT, old(it.subject, it.range), new(node.newRange), Confidence.HIGH) }
            node.extractedTo.forEach { relationships += ActualRelationship(Kind.EXTRACT, old(node.subject, node.oldRange), endpoint(it, path, null), Confidence.HIGH) }
            node.children.forEach { visit(it, ancestors + node.subject) }
        }
        result.fileRegion?.let { visit(it, emptyList()) }
        result.changes.forEach { visit(it, emptyList()) }
        return relationships
    }

    fun actualNodes(result: ComparisonResult): List<Pair<Endpoint, ChangeNode>> {
        val nodes = mutableListOf<Pair<Endpoint, ChangeNode>>()
        fun visit(node: ChangeNode, ancestors: List<Subject>) {
            val path = ancestors.joinToString(" / ") { OutlineModel.title(it) }
            nodes += endpoint(node.subject, path, node.oldRange) to node
            node.children.forEach { visit(it, ancestors + node.subject) }
        }
        result.fileRegion?.let { visit(it, emptyList()) }
        result.changes.forEach { visit(it, emptyList()) }
        return nodes
    }

    private fun endpoint(subject: Subject, path: String, range: TextRange?): Endpoint = when (subject) {
        is Subject.Declaration -> Endpoint(path, subject.kind, subject.name, subject.signature, range)
        is Subject.Region -> Endpoint(path, DeclarationKind.INITIALIZER, subject.description, null, range)
    }

    fun evaluate(labels: List<Label>, results: List<CaseResult>): Metrics {
        val perKind = mutableMapOf<String, PairCounts>()
        var forced = 0
        var correct = 0
        var total = 0
        var presenceCorrect = 0
        var presenceTotal = 0
        var contentCorrect = 0
        var contentTotal = 0
        var movedCorrect = 0
        var movedTotal = 0
        var signatureCorrect = 0
        var signatureTotal = 0
        var kindCorrect = 0
        var kindTotal = 0
        for (result in results) {
            val label = result.label
            val matched = BooleanArray(result.actualRelationships.size)
            for (expected in label.relationships) {
                val matchedKinds = mutableSetOf<Kind>()
                result.actualRelationships.forEachIndexed { index, actual ->
                    if (actual.kind in expected.kinds && endpointsMatch(expected.old, actual.old) && endpointsMatch(expected.new, actual.new) &&
                        (expected.confidence == null || expected.confidence == actual.confidence)
                    ) {
                        matched[index] = true
                        matchedKinds += actual.kind
                        perKind.getOrPut(actual.kind.name) { PairCounts() }.tp++
                    }
                }
                (expected.kinds - matchedKinds).forEach { perKind.getOrPut(it.name) { PairCounts() }.fn++ }
            }
            result.actualRelationships.forEachIndexed { index, actual ->
                val forcedPair = label.ambiguous.any { endpointsMatch(it, actual.old) || endpointsMatch(it, actual.new) }
                if (forcedPair) forced++ else if (!matched[index]) perKind.getOrPut(actual.kind.name) { PairCounts() }.fp++
            }
            for (expected in label.changes) {
                total++
                val actual = result.actualNodes.firstOrNull { (endpoint, _) -> endpointsMatch(expected.subject, endpoint) }?.second
                presenceTotal++
                if (actual?.presence == expected.presence) presenceCorrect++
                contentTotal++
                if (actual?.content == expected.content) contentCorrect++
                if (expected.moved != null) {
                    movedTotal++
                    if (actual?.moved == expected.moved) movedCorrect++
                }
                if (expected.signatureChanged != null) {
                    signatureTotal++
                    if (actual?.signatureChanged == expected.signatureChanged) signatureCorrect++
                }
                if (expected.kindChangedFrom != null) {
                    kindTotal++
                    if (actual?.kindChangedFrom == expected.kindChangedFrom) kindCorrect++
                }
                if (actual != null && actual.presence == expected.presence && actual.content == expected.content &&
                    (expected.moved == null || actual.moved == expected.moved) &&
                    (expected.signatureChanged == null || actual.signatureChanged == expected.signatureChanged) &&
                    (expected.kindChangedFrom == null || actual.kindChangedFrom == expected.kindChangedFrom)
                ) correct++
            }
        }
        return Metrics(
            perKind,
            forced,
            correct,
            total,
            results.count { it.hidden },
            results.count { it.result.status == Status.UNSUPPORTED || it.result.status == Status.TOO_LARGE || it.result.status == Status.FAILED },
            results.map { it.millis },
            presenceCorrect,
            presenceTotal,
            contentCorrect,
            contentTotal,
            movedCorrect,
            movedTotal,
            signatureCorrect,
            signatureTotal,
            kindCorrect,
            kindTotal,
        )
    }

    /**
     * The minimums of R8, R9 and R10 (R30). Every matrix category and corpus is counted unless
     * [notApplicable] gives a reason, and every corpus's labelled commits are counted against [commitMinimum].
     */
    fun matrixFailures(
        labels: List<Label>,
        corpora: List<String> = CORPORA,
        categories: List<String> = CATEGORIES,
        labelledCommits: Map<String, Int> = emptyMap(),
        minimum: Int = MINIMUM_CASES,
        commitMinimum: Int = MINIMUM_COMMITS,
        notApplicable: Map<String, Map<String, String>> = NOT_APPLICABLE,
    ): List<MatrixFailure> = buildList {
        for (corpus in corpora) {
            for (category in categories) {
                if (notApplicable[corpus]?.containsKey(category) == true) continue
                val count = labels.count { it.corpus == corpus && it.category == category && it.reviewed }
                if (count < minimum) add(MatrixFailure(corpus, category, count, minimum))
            }
            val commits = labelledCommits[corpus] ?: 0
            if (commits < commitMinimum) add(MatrixFailure(corpus, "labelled commits", commits, commitMinimum, "commits"))
        }
    }

    /** The not-applicable entries with their reasons, as the report lists them. */
    fun notApplicableReasons(notApplicable: Map<String, Map<String, String>> = NOT_APPLICABLE): List<String> =
        notApplicable.entries
            .sortedBy { it.key }
            .flatMap { (corpus, categories) -> categories.entries.sortedBy { it.key }.map { "${it.key} in $corpus: ${it.value}" } }

    fun endpointsMatch(expected: Endpoint, actual: Endpoint): Boolean =
        expected.sameIdentity(actual) && (expected.range == null || expected.range == actual.range)

    fun aggregate(counts: Collection<PairCounts>): PairCounts =
        counts.fold(PairCounts()) { all, next -> PairCounts(all.tp + next.tp, all.fp + next.fp, all.fn + next.fn) }

    fun median(values: List<Long>): Long = percentile(values, 0.5)

    fun percentile(values: List<Long>, fraction: Double): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    /**
     * Runs the corpus list, the way `RelationshipBenchmarkTest` runs it. [labelRoot] is the checked-in test data;
     * [drafts] is where R37 writes draft labels for unlabelled real-commit files.
     */
    fun run(
        corpus: File,
        output: File = File("build/reports/smartdiff-relationships"),
        labelRoot: File = File(DEFAULT_LABEL_ROOT),
        drafts: File? = null,
        minimum: Int = MINIMUM_CASES,
        commitMinimum: Int = MINIMUM_COMMITS,
    ): Report {
        val engine = DeclarationEngine(null)
        val entries = parseCorpus(corpus)
        val repositories = entries.mapNotNull { it.repository }.distinctBy { it.name }
        validateCommitLabels(labelRoot, repositories)

        val cases = mutableListOf<CaseResult>()
        val unlabelled = mutableListOf<UnlabelledFile>()
        val skippedFiles = mutableListOf<String>()
        val safety = mutableListOf<String>()
        val labelledCommits = mutableMapOf<String, Int>()
        val draftsToWrite = mutableListOf<Draft>()
        var skippedCommitLines = 0
        var unlabelledHidden = 0
        var unlabelledFallback = 0

        for (entry in entries) {
            val directory = entry.path
            if (directory != null) {
                cases += runCase(engine, directory)
                continue
            }
            val line = runCommitLine(engine, entry, labelRoot, drafts != null)
            cases += line.cases
            unlabelled += line.unlabelled
            skippedFiles += line.skipped
            safety += line.safety
            draftsToWrite += line.drafts
            skippedCommitLines += line.skippedCommitLines
            unlabelledHidden += line.unlabelledHidden
            unlabelledFallback += line.unlabelledFallback
            for ((name, count) in line.labelledCommits) labelledCommits[name] = (labelledCommits[name] ?: 0) + count
        }

        val draftsWritten = drafts?.let { writeDrafts(it, draftsToWrite) } ?: 0
        val byLanguage = cases.groupBy { it.label.corpus }.mapValues { evaluate(it.value.map { row -> row.label }, it.value) }
        val bySplit = cases.groupBy { it.label.split }.mapValues { evaluate(it.value.map { row -> row.label }, it.value) }
        val overall = evaluate(cases.map { it.label }, cases)
        val gateMode = System.getProperty(GATE_PROPERTY)?.takeIf { it.isNotBlank() } ?: DEFAULT_GATE
        val tolerance = System.getProperty(TOLERANCE_PROPERTY)?.toDoubleOrNull() ?: 0.0
        val baselineFile = System.getProperty(BASELINE_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
        val failures = gateFailures(gateMode, overall, byLanguage, baselineFile, tolerance, cases, safety)
        val shortfalls = matrixFailures(cases.map { it.label }, labelledCommits = labelledCommits, minimum = minimum, commitMinimum = commitMinimum)
        val report = Report(
            cases = cases,
            metricsByLanguage = byLanguage,
            metricsBySplit = bySplit,
            overall = overall,
            gateMode = gateMode,
            gateFailures = failures,
            corpusRevision = corpusRevision(corpus, labelRoot),
            reportDirectory = output,
            skippedCommitLines = skippedCommitLines,
            smoke = shortfalls.isNotEmpty(),
            matrixFailures = shortfalls,
            labelledCommits = labelledCommits,
            notApplicable = notApplicableReasons(),
            unlabelled = unlabelled,
            skippedFiles = skippedFiles,
            newGatePairs = baselineGaps(byLanguage, baselineFile),
            draftsWritten = draftsWritten,
            unlabelledHidden = unlabelledHidden,
            unlabelledFallback = unlabelledFallback,
        )
        write(report)
        return report
    }

    private fun runCase(engine: ComparisonEngine, directory: File): CaseResult {
        val labelFile = File(directory, "label.yaml")
        require(labelFile.isFile) { "case ${directory.path} has no label.yaml" }
        val label = parseLabel(labelFile)
        require(label.source == null) { "label ${labelFile.path}: a case folder label must not have a source" }
        require(label.category in CATEGORIES) { "label ${labelFile.path}: unknown category ${label.category}" }
        val extension = extensionFor(label.language)
        val oldFile = File(directory, "old.$extension")
        val newFile = File(directory, "new.$extension")
        require(oldFile.isFile) { "case ${directory.path} has no ${oldFile.name}" }
        require(newFile.isFile) { "case ${directory.path} has no ${newFile.name}" }
        val old = oldFile.readText().replace("\r\n", "\n")
        val new = newFile.readText().replace("\r\n", "\n")
        val outcome = compare(engine, old, new, label.language, "${label.case}.$extension")
        return CaseResult(
            label, outcome.result, actualRelationships(outcome.result), actualNodes(outcome.result),
            outcome.millis, hidden(old, new, outcome.result), outcome.exception,
        )
    }

    private fun runCommitLine(engine: ComparisonEngine, entry: CorpusEntry, labelRoot: File, draft: Boolean): CommitLine {
        val repository = entry.repository ?: error("corpus line '${entry.line}' is not a case folder")
        if (!repository.isDirectory) error("real-commit repository not found: ${repository.path}")
        val result = CommitLine()
        val commits = git(repository, listOf("rev-list") + entry.arguments + listOf("--", "*.java", "*.kt", "*.scala"))
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (commits.isEmpty()) result.skippedCommitLines++
        for (commit in commits) {
            val parent = git(repository, listOf("rev-parse", "--verify", "--quiet", "$commit^"), allowFailure = true).trim()
            if (parent.isEmpty()) {
                result.skipped.add("${repository.name} ${commit.take(7)}: no first parent")
                result.skippedCommitLines++
                continue
            }
            val changes = git(
                repository,
                listOf("diff", "--name-status", "--diff-filter=ADM", parent, commit, "--", "*.java", "*.kt", "*.scala"),
            ).lines().map { it.trim() }.filter { it.isNotEmpty() }
            val files = mutableListOf<CommitFile>()
            var skippedHere = 0
            for (change in changes) {
                val parts = change.split(Regex("\\s+"), limit = 2)
                val status = parts.first()
                val path = parts.getOrNull(1) ?: continue
                if (status != "M") {
                    skippedHere++
                    result.skipped.add("${repository.name} ${commit.take(7)} $path: ${if (status == "A") "added" else "deleted"}")
                    continue
                }
                val old = git(repository, listOf("show", "$parent:$path")).replace("\r\n", "\n")
                val new = git(repository, listOf("show", "$commit:$path")).replace("\r\n", "\n")
                val candidates = corporaFor(path)
                val labelFile = commitLabelFile(labelRoot, candidates, repository.name, commit, path)
                val label = labelFile?.let(::parseLabel)
                if (label != null) {
                    val source = label.source ?: error("label ${labelFile.path}: a real-commit label needs source")
                    require(source.repository == repository.name) { "label ${labelFile.path}: repository ${source.repository} is not the clone folder ${repository.name}" }
                    require(source.commit == commit) { "label ${labelFile.path}: commit ${source.commit} is not ${commit}" }
                    require(source.path == path) { "label ${labelFile.path}: path ${source.path} is not $path" }
                    require(label.category == REAL_COMMIT_CATEGORY) { "label ${labelFile.path}: category must be $REAL_COMMIT_CATEGORY" }
                }
                val corpus = label?.corpus ?: if (path.endsWith(".scala")) scalaCorpus(old, new) else candidates.single()
                val languageId = label?.language ?: languageIdFor(path)
                val outcome = compare(engine, old, new, languageId, File(path).name)
                val id = "${repository.name} ${commit.take(7)} $path"
                if (label != null && label.reviewed) {
                    result.cases += CaseResult(
                        label, outcome.result, actualRelationships(outcome.result), actualNodes(outcome.result),
                        outcome.millis, hidden(old, new, outcome.result), outcome.exception, id,
                    )
                } else {
                    result.unlabelled += UnlabelledFile(repository.name, commit, path, corpus, if (label == null) "no label" else "not reviewed")
                    val hiddenHere = hidden(old, new, outcome.result)
                    result.safety += safetyProblems(id, hiddenHere, outcome)
                    if (hiddenHere) result.unlabelledHidden++
                    if (outcome.result.status != Status.ANALYZED && outcome.result.status != Status.PARTIAL) result.unlabelledFallback++
                    if (draft) result.drafts += draft(repository.name, commit, path, corpus, languageId, outcome)
                }
                files += CommitFile(corpus, label?.reviewed == true)
            }
            if (files.isNotEmpty() && skippedHere == 0 && files.all { it.reviewed }) {
                files.map { it.corpus }.distinct().forEach { corpusName -> result.labelledCommits[corpusName] = (result.labelledCommits[corpusName] ?: 0) + 1 }
            }
        }
        return result
    }

    private class CommitLine {
        val cases = mutableListOf<CaseResult>()
        val unlabelled = mutableListOf<UnlabelledFile>()
        val skipped = mutableListOf<String>()
        val safety = mutableListOf<String>()
        val labelledCommits = mutableMapOf<String, Int>()
        val drafts = mutableListOf<Draft>()
        var skippedCommitLines = 0
        var unlabelledHidden = 0
        var unlabelledFallback = 0
    }

    private data class CommitFile(val corpus: String, val reviewed: Boolean)

    private data class Draft(val corpus: String, val repository: String, val commit: String, val path: String, val text: String)

    private fun compare(engine: ComparisonEngine, old: String, new: String, languageId: String, fileName: String): Outcome {
        val started = System.nanoTime()
        return try {
            val result = onPooledThread { compareSafely(engine, ComparisonRequest(old, new, languageId, fileName), Cancellation.NONE) }
            Outcome(result, (System.nanoTime() - started) / 1_000_000)
        } catch (e: Exception) {
            Outcome(ComparisonResult(engine.id, engine.version, Status.FAILED, fallbackReason = e.message), (System.nanoTime() - started) / 1_000_000, e.message)
        }
    }

    private data class Outcome(val result: ComparisonResult, val millis: Long, val exception: String? = null)

    private fun hidden(old: String, new: String, result: ComparisonResult): Boolean =
        (result.status == Status.ANALYZED || result.status == Status.PARTIAL) && NothingHidden.violation(old, new, result) != null

    /** R26: a nothing-hidden violation, a file over the limit or an exception fails the run in every mode, unlabelled files too. */
    private fun safetyProblems(id: String, hidden: Boolean, outcome: Outcome): List<String> = buildList {
        if (hidden) add("nothing-hidden violation in $id")
        if (outcome.millis > TIME_LIMIT_MILLIS) add("file over the ${TIME_LIMIT_MILLIS} ms limit: $id")
        outcome.exception?.let { add("case threw in $id: $it") }
    }

    private fun extensionFor(language: String): String = when (language.uppercase()) {
        "JAVA" -> "java"
        "KOTLIN" -> "kt"
        else -> "scala"
    }

    private fun languageIdFor(path: String): String = when {
        path.endsWith(".kt") -> "kotlin"
        path.endsWith(".scala") -> "Scala"
        else -> "JAVA"
    }

    private fun corporaFor(path: String): List<String> = when {
        path.endsWith(".java") -> listOf("java")
        path.endsWith(".kt") -> listOf("kotlin")
        else -> listOf("scala2", "scala3")
    }

    /** R33: an unlabelled Scala file is counted under the dialect it parses in. */
    private fun scalaCorpus(old: String, new: String): String = try {
        val provider = DeclarationProvider.EP_NAME.extensionList.firstOrNull { it.supports("Scala") } ?: return "scala2"
        val id = ReadAction.compute(ThrowableComputable<String?, RuntimeException> {
            provider.dialect(ProjectManager.getInstance().defaultProject, listOf(old, new), null)?.id
        })
        if (id == "Scala 3") "scala3" else "scala2"
    } catch (e: Exception) {
        "scala2"
    }

    /**
     * The checked-in label for a real-commit file (R35): `<labelRoot>/<corpus>/relationships/commits/<repository>/<short-sha>/<file-name>.yaml`,
     * where `<file-name>` is the file's name without its extension. A folder name that is a prefix of the commit works
     * as the short SHA, so a 7- or 10-character folder both match.
     */
    fun commitLabelFile(labelRoot: File, corpora: List<String>, repository: String, commit: String, path: String): File? {
        val name = File(path).name.substringBeforeLast('.', File(path).name)
        for (corpus in corpora) {
            val folder = File(labelRoot, "$corpus/relationships/commits/$repository")
            if (!folder.isDirectory) continue
            val sha = folder.listFiles().orEmpty()
                .filter { it.isDirectory && commit.startsWith(it.name) }
                .maxByOrNull { it.name.length } ?: continue
            val file = File(sha, "$name.yaml")
            if (file.isFile) return file
        }
        return null
    }

    /**
     * R35: every checked-in real-commit label names a repository, a commit and a path; a label whose commit is not in
     * the clone, whose path is not in the commit, or whose commit has no first parent fails the run with the label path.
     */
    fun validateCommitLabels(labelRoot: File, repositories: Collection<File>) {
        for (repository in repositories) {
            for (corpus in CORPORA) {
                val folder = File(labelRoot, "$corpus/relationships/commits/${repository.name}")
                if (!folder.isDirectory) continue
                for (sha in folder.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
                    for (file in sha.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".yaml") }.sortedBy { it.name }) {
                        val label = parseLabel(file)
                        val source = label.source ?: error("label ${file.path}: a real-commit label needs source")
                        require(source.repository == repository.name) {
                            "label ${file.path}: repository ${source.repository} is not the clone folder ${repository.name}"
                        }
                        require(source.commit.startsWith(sha.name)) { "label ${file.path}: commit ${source.commit} is not under ${sha.name}" }
                        require(label.corpus == corpus) { "label ${file.path}: corpus ${label.corpus} is not the folder $corpus" }
                        require(label.category == REAL_COMMIT_CATEGORY) { "label ${file.path}: category must be $REAL_COMMIT_CATEGORY" }
                        if (!gitSucceeds(repository, listOf("cat-file", "-e", "${source.commit}^{commit}"))) {
                            error("label ${file.path}: commit ${source.commit} is not in ${repository.path}")
                        }
                        if (!gitSucceeds(repository, listOf("cat-file", "-e", "${source.commit}:${source.path}"))) {
                            error("label ${file.path}: path ${source.path} is not in commit ${source.commit}")
                        }
                        if (!gitSucceeds(repository, listOf("rev-parse", "--verify", "--quiet", "${source.commit}^^{commit}"))) {
                            error("label ${file.path}: commit ${source.commit} has no first parent")
                        }
                    }
                }
            }
        }
    }

    /** R37: a draft from what the engine reports today, marked unreviewed, so it is never scored as it stands. */
    private fun draft(repository: String, commit: String, path: String, corpus: String, languageId: String, outcome: Outcome): Draft {
        val name = File(path).name.substringBeforeLast('.', File(path).name)
        val title = "$repository-${commit.take(7)}-$name"
        val text = buildString {
            appendLine("case: $title")
            appendLine("language: $languageId")
            appendLine("corpus: $corpus")
            appendLine("category: $REAL_COMMIT_CATEGORY")
            appendLine("split: held-out")
            appendLine("reviewed: false")
            appendLine("source:")
            appendLine("  repository: ${quote(repository)}")
            appendLine("  commit: $commit")
            appendLine("  path: ${quote(path)}")
            appendLine("expected:")
            val relationships = actualRelationships(outcome.result)
            if (relationships.isEmpty()) {
                appendLine("  relationships: []")
            } else {
                appendLine("  relationships:")
                for (relationship in relationships) {
                    appendLine("    - kinds: [${relationship.kind.name}]")
                    appendLine("      old: ${inline(relationship.old)}")
                    appendLine("      new: ${inline(relationship.new)}")
                }
            }
            val nodes = actualNodes(outcome.result)
            if (nodes.isEmpty()) {
                appendLine("  changes: []")
            } else {
                appendLine("  changes:")
                for ((endpoint, node) in nodes) {
                    appendLine("    - subject: ${inline(endpoint)}")
                    appendLine("      presence: ${node.presence.name}")
                    appendLine("      content: ${node.content.name}")
                    if (node.moved) appendLine("      moved: true")
                    if (node.signatureChanged) appendLine("      signatureChanged: true")
                    node.kindChangedFrom?.let { appendLine("      kindChangedFrom: ${it.name}") }
                }
            }
            appendLine("  ambiguous: []")
            appendLine("notes: \"Draft from the engine's report; review every relationship and classification before setting reviewed: true.\"")
        }
        return Draft(corpus, repository, commit, path, text)
    }

    private fun inline(endpoint: Endpoint): String =
        "{ path: ${quote(endpoint.path)}, kind: ${endpoint.kind.name}, name: ${quote(endpoint.name)}, signature: ${endpoint.signature?.let(::quote) ?: "null"} }"

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** R37: writes the drafts under [directory] in the checked-in layout; returns how many it wrote. */
    private fun writeDrafts(directory: File, drafts: List<Draft>): Int {
        var written = 0
        for (draft in drafts) {
            val name = File(draft.path).name.substringBeforeLast('.', File(draft.path).name)
            val folder = File(File(File(File(File(directory, draft.corpus), "relationships"), "commits"), draft.repository), draft.commit.take(7))
            folder.mkdirs()
            File(folder, "$name.yaml").writeText(draft.text)
            written++
        }
        return written
    }

    fun gateCounts(byLanguage: Map<String, Metrics>): Map<String, PairCounts> = buildMap {
        for ((language, metrics) in byLanguage) {
            for ((kind, counts) in metrics.perKind) put("$language|$kind", counts)
        }
    }

    fun gateFailures(
        mode: String,
        current: Metrics,
        byLanguage: Map<String, Metrics>,
        baselineFile: File?,
        tolerance: Double,
        cases: List<CaseResult>,
        safety: List<String> = emptyList(),
    ): List<String> = buildList {
        if (cases.any { it.hidden }) add("nothing-hidden violation")
        if (cases.any { it.millis > TIME_LIMIT_MILLIS }) add("file over the ${TIME_LIMIT_MILLIS} ms limit")
        if (cases.any { it.exception != null }) add("case threw")
        addAll(safety)
        if (baselineFile != null && !baselineFile.isFile) {
            add("baseline not found: ${baselineFile.path}")
            return@buildList
        }
        val baseline = baselineFile?.let { readBaseline(it) } ?: return@buildList
        if (baseline.smoke) {
            add("baseline is a smoke run: ${baselineFile.path}")
            return@buildList
        }
        when (mode) {
            "no-new-wrong-pairs" -> {
                val currentWrong = wrongPairs(cases) + forcedPairs(cases)
                val new = currentWrong - baseline.wrongPairs
                if (new.isNotEmpty()) add("new wrong or forced pairs: ${new.sorted().joinToString(", ")}")
            }
            "precision" -> {
                for ((key, counts) in gateCounts(byLanguage)) {
                    val before = baseline.precision[key] ?: continue
                    if (counts.precision < before) add("precision fell for $key: ${counts.precision} < $before")
                }
            }
            "tolerance" -> {
                for ((key, counts) in gateCounts(byLanguage)) {
                    val before = baseline.precision[key] ?: continue
                    if (before - counts.precision > tolerance / 100.0) add("precision fell for $key: ${counts.precision} < $before by more than $tolerance points")
                }
                for ((key, counts) in gateCounts(byLanguage)) {
                    val before = baseline.recall[key] ?: continue
                    if (before - counts.recall > tolerance / 100.0) add("recall fell for $key: ${counts.recall} < $before by more than $tolerance points")
                }
            }
            else -> add("unknown gate mode: $mode")
        }
    }

    /** R41: the `(corpus, kind)` pairs the baseline lacks; they are listed as new in this run and do not fail a gate. */
    fun baselineGaps(byLanguage: Map<String, Metrics>, baselineFile: File?): List<String> {
        if (baselineFile == null || !baselineFile.isFile) return emptyList()
        val baseline = readBaseline(baselineFile)
        return gateCounts(byLanguage).keys
            .filter { it !in baseline.precision || it !in baseline.recall }
            .sorted()
            .map { "new in this run: $it" }
    }

    data class Baseline(val wrongPairs: Set<String>, val precision: Map<String, Double>, val recall: Map<String, Double>, val smoke: Boolean)

    private fun readBaseline(file: File): Baseline {
        @Suppress("UNCHECKED_CAST")
        val root = GsonBuilder().create().fromJson(file.readText(), Map::class.java) as Map<String, Any?>
        val wrong = (root["wrongPairs"] as? List<*>)?.map { it.toString() }?.toSet().orEmpty()
        fun metrics(key: String) = (root[key] as? Map<*, *>)?.entries
            ?.mapNotNull { entry -> (entry.value as? Number)?.let { entry.key.toString() to it.toDouble() } }
            ?.toMap().orEmpty()
        return Baseline(wrong, metrics("precision"), metrics("recall"), root["smoke"] as? Boolean ?: false)
    }

    fun wrongPairs(cases: List<CaseResult>): Set<String> = buildSet {
        for (case in cases) {
            val label = case.label
            val matched = BooleanArray(case.actualRelationships.size)
            for (expected in label.relationships) {
                case.actualRelationships.forEachIndexed { index, actual ->
                    if (actual.kind in expected.kinds && endpointsMatch(expected.old, actual.old) && endpointsMatch(expected.new, actual.new) &&
                        (expected.confidence == null || expected.confidence == actual.confidence)
                    ) {
                        matched[index] = true
                    }
                }
            }
            case.actualRelationships.forEachIndexed { index, actual ->
                val forced = label.ambiguous.any { endpointsMatch(it, actual.old) || endpointsMatch(it, actual.new) }
                if (!matched[index] && !forced) add("${label.case}|${actual.kind}|${actual.old.key()}|${actual.new.key()}")
            }
        }
    }

    /** R18: the wrong and forced pairs with the label's path, as the report lists them; [wrongPairs] stays stable for gates. */
    fun wrongPairLines(cases: List<CaseResult>): List<String> = buildList {
        for (case in cases) {
            val label = case.label
            val matched = BooleanArray(case.actualRelationships.size)
            for (expected in label.relationships) {
                case.actualRelationships.forEachIndexed { index, actual ->
                    if (actual.kind in expected.kinds && endpointsMatch(expected.old, actual.old) && endpointsMatch(expected.new, actual.new) &&
                        (expected.confidence == null || expected.confidence == actual.confidence)
                    ) {
                        matched[index] = true
                    }
                }
            }
            case.actualRelationships.forEachIndexed { index, actual ->
                val forced = label.ambiguous.any { endpointsMatch(it, actual.old) || endpointsMatch(it, actual.new) }
                if (forced) {
                    add("${label.case}|forced|${actual.kind}|${actual.old.name}|${actual.new.name}|${label.path ?: "-"}")
                } else if (!matched[index]) {
                    add("${label.case}|wrong|${actual.kind}|${actual.old.name}|${actual.new.name}|${label.path ?: "-"}")
                }
            }
        }
    }

    fun forcedPairs(cases: List<CaseResult>): Set<String> = buildSet {
        for (case in cases) {
            for (actual in case.actualRelationships) {
                if (case.label.ambiguous.any { endpointsMatch(it, actual.old) || endpointsMatch(it, actual.new) }) {
                    add("${case.label.case}|forced|${actual.kind}|${actual.old.key()}|${actual.new.key()}")
                }
            }
        }
    }

    fun missedRelationships(cases: List<CaseResult>): List<String> = buildList {
        for (case in cases) {
            for (expected in case.label.relationships) {
                val found = case.actualRelationships.filter { actual ->
                    actual.kind in expected.kinds && endpointsMatch(expected.old, actual.old) && endpointsMatch(expected.new, actual.new) &&
                        (expected.confidence == null || expected.confidence == actual.confidence)
                }.map { it.kind }.toSet()
                (expected.kinds - found).forEach { kind ->
                    add("${case.label.case}|missed|$kind|${expected.old.name}|${expected.new.name}|${case.label.path ?: "-"}")
                }
            }
        }
    }

    fun classificationMismatches(cases: List<CaseResult>): List<String> = buildList {
        for (case in cases) {
            for (expected in case.label.changes) {
                val actual = case.actualNodes.firstOrNull { (endpoint, _) -> endpointsMatch(expected.subject, endpoint) }?.second
                val mismatch = actual == null || actual.presence != expected.presence || actual.content != expected.content ||
                    (expected.moved != null && actual.moved != expected.moved) ||
                    (expected.signatureChanged != null && actual.signatureChanged != expected.signatureChanged) ||
                    (expected.kindChangedFrom != null && actual.kindChangedFrom != expected.kindChangedFrom)
                if (mismatch) {
                    add(
                        "${case.label.case}|${expected.subject.name}|expected ${expected.presence}/${expected.content}, " +
                            "actual ${actual?.presence ?: "missing"}/${actual?.content ?: "-"}|${case.label.path ?: "-"}",
                    )
                }
            }
        }
    }

    fun write(report: Report) {
        report.reportDirectory.mkdirs()
        File(report.reportDirectory, "summary.tsv").writeText(buildString {
            appendLine("corpus\tkind\ttp\tfp\tfn\tprecision\trecall\twrong-share")
            for ((language, metrics) in report.metricsByLanguage) {
                for ((kind, counts) in metrics.perKind) {
                    appendLine(listOf(language, kind, counts.tp, counts.fp, counts.fn, format(counts.precision), format(counts.recall), format(counts.wrongShare)).joinToString("\t"))
                }
            }
        })
        File(report.reportDirectory, "report.md").writeText(buildString {
            appendLine("# SMART Diff relationship benchmark")
            appendLine()
            appendLine(if (report.smoke) "Status: smoke run" else "Status: complete run")
            appendLine("Engine: ${DeclarationEngine.ID} ${DeclarationEngine.VERSION}; IDE: ${ApplicationInfo.getInstance().build.asStringWithoutProductCode()}")
            appendLine("Scala plugin: ${scalaVersion()}; corpus revision: ${report.corpusRevision}; gate: ${report.gateMode}")
            appendLine("Analysis limit: ${TIME_LIMIT_MILLIS} ms per file")
            appendLine("Skipped real-commit commits: ${report.skippedCommitLines}")
            appendLine()
            if (report.smoke) {
                appendLine("## Shortfalls")
                report.matrixFailures.forEach { appendLine("- ${it.message}") }
                appendLine()
            }
            if (report.notApplicable.isNotEmpty()) {
                appendLine("## Not applicable")
                report.notApplicable.forEach { appendLine("- $it") }
                appendLine()
            }
            appendLine(
                "Cases: ${report.cases.size}; hidden: ${report.overall.hidden} of ${report.cases.size} scored + ${report.unlabelledHidden} unlabelled; " +
                    "fallback: ${report.overall.fallback} scored + ${report.unlabelledFallback} unlabelled; " +
                    "forced pairs: ${report.overall.forcedPairs}; unlabelled: ${report.unlabelled.size}; skipped files: ${report.skippedFiles.size}; " +
                    "drafts: ${report.draftsWritten}",
            )
            appendLine()
            for (corpus in CORPORA) {
                val commits = report.labelledCommits[corpus] ?: 0
                appendLine("- $corpus labeled commits: $commits of $MINIMUM_COMMITS")
            }
            appendLine()
            for ((split, metrics) in report.metricsBySplit) {
                appendLine("## ${split.name.lowercase()}")
                appendLine(
                    "Precision ${format(metrics.precision)}, recall ${format(metrics.recall)}, " +
                        "presence ${format(metrics.presenceAccuracy)}, content ${format(metrics.contentAccuracy)}, " +
                        "classification ${format(metrics.classificationAccuracy)}, median ${metrics.medianMillis} ms, " +
                        "p95 ${metrics.p95Millis} ms, slowest ${metrics.slowestMillis} ms of $TIME_LIMIT_MILLIS ms",
                )
                appendLine()
            }
            appendLine("## Per corpus")
            for ((language, metrics) in report.metricsByLanguage) {
                appendLine(
                    "- $language: precision ${format(metrics.precision)}, recall ${format(metrics.recall)}, " +
                        "wrong share ${format(metrics.wrongShare)}, presence ${format(metrics.presenceAccuracy)}, " +
                        "content ${format(metrics.contentAccuracy)}, moved ${format(metrics.movedAccuracy)}, " +
                        "signature ${format(metrics.signatureAccuracy)}, kind ${format(metrics.kindAccuracy)}, " +
                        "median ${metrics.medianMillis} ms, p95 ${metrics.p95Millis} ms, slowest ${metrics.slowestMillis} ms of $TIME_LIMIT_MILLIS ms",
                )
            }
            appendLine()
            if (report.newGatePairs.isNotEmpty()) {
                appendLine("## New in this run")
                report.newGatePairs.forEach { appendLine("- $it") }
                appendLine()
            }
            appendLine("## Wrong and missed relationships")
            val wrong = wrongPairLines(report.cases).sorted()
            if (wrong.isEmpty()) appendLine("- none") else wrong.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Missed relationships")
            val missed = missedRelationships(report.cases).sorted()
            if (missed.isEmpty()) appendLine("- none") else missed.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Classification mismatches")
            val mismatches = classificationMismatches(report.cases).sorted()
            if (mismatches.isEmpty()) appendLine("- none") else mismatches.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Unlabelled files")
            if (report.unlabelled.isEmpty()) appendLine("- none") else report.unlabelled.sortedBy { it.id }.forEach { appendLine("- ${it.id} (${it.corpus}, ${it.reason})") }
            appendLine()
            appendLine("## Skipped files")
            if (report.skippedFiles.isEmpty()) appendLine("- none") else report.skippedFiles.sorted().forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Gate")
            if (report.gateFailures.isEmpty()) appendLine("Passed") else report.gateFailures.forEach { appendLine("- $it") }
        })
        val precision = gateCounts(report.metricsByLanguage).mapValues { it.value.precision }
        val recall = gateCounts(report.metricsByLanguage).mapValues { it.value.recall }
        val json = mapOf(
            "engine" to mapOf("id" to DeclarationEngine.ID.toString(), "version" to DeclarationEngine.VERSION),
            "ide" to ApplicationInfo.getInstance().build.asStringWithoutProductCode(),
            "scalaPlugin" to scalaVersion(),
            "smoke" to report.smoke,
            "shortfalls" to report.matrixFailures.map { it.message },
            "notApplicable" to report.notApplicable,
            "labelledCommits" to report.labelledCommits,
            "wrongPairs" to (wrongPairs(report.cases) + forcedPairs(report.cases)).sorted(),
            "wrongPairDetails" to wrongPairLines(report.cases).sorted(),
            "missed" to missedRelationships(report.cases),
            "classificationMismatches" to classificationMismatches(report.cases),
            "precision" to precision,
            "recall" to recall,
            "overall" to metricsJson(report.overall),
            "perLanguage" to report.metricsByLanguage.mapValues { metricsJson(it.value) },
            "perSplit" to report.metricsBySplit.mapKeys { it.key.name.lowercase() }.mapValues { metricsJson(it.value) },
            "gate" to report.gateMode,
            "corpusRevision" to report.corpusRevision,
            "skippedCommitLines" to report.skippedCommitLines,
            "unlabelledHidden" to report.unlabelledHidden,
            "unlabelledFallback" to report.unlabelledFallback,
            "skippedFiles" to report.skippedFiles.sorted(),
            "unlabelled" to report.unlabelled.sortedBy { it.id }.map {
                mapOf("repository" to it.repository, "commit" to it.commit, "path" to it.path, "corpus" to it.corpus, "reason" to it.reason)
            },
            "newGatePairs" to report.newGatePairs,
            "draftsWritten" to report.draftsWritten,
        )
        File(report.reportDirectory, "report.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(json))
        println("RELATIONSHIPS ${report.cases.size} cases, precision ${format(report.overall.precision)}, recall ${format(report.overall.recall)}${if (report.smoke) ", smoke run" else ""}; report in ${report.reportDirectory.absolutePath}")
    }

    private fun metricsJson(metrics: Metrics): Map<String, Any> = mapOf(
        "precision" to metrics.precision,
        "recall" to metrics.recall,
        "wrongShare" to metrics.wrongShare,
        "forcedPairs" to metrics.forcedPairs,
        "classificationAccuracy" to metrics.classificationAccuracy,
        "presenceAccuracy" to metrics.presenceAccuracy,
        "contentAccuracy" to metrics.contentAccuracy,
        "movedAccuracy" to metrics.movedAccuracy,
        "signatureAccuracy" to metrics.signatureAccuracy,
        "kindAccuracy" to metrics.kindAccuracy,
        "hidden" to metrics.hidden,
        "fallback" to metrics.fallback,
        "medianMillis" to metrics.medianMillis,
        "p95Millis" to metrics.p95Millis,
        "slowestMillis" to metrics.slowestMillis,
        "perKind" to metrics.perKind.mapValues { mapOf("tp" to it.value.tp, "fp" to it.value.fp, "fn" to it.value.fn, "precision" to it.value.precision, "recall" to it.value.recall) },
    )

    /** R43: a hash of the list, every case and label file it reaches, and the clone of each real-commit line. */
    fun corpusRevision(corpus: File, labelRoot: File = File(DEFAULT_LABEL_ROOT)): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(corpus.readBytes())
        for (entry in parseCorpus(corpus)) {
            val directory = entry.path
            if (directory != null) {
                val files = directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).path }
                for (file in files) {
                    digest.update(file.relativeTo(directory).path.toByteArray())
                    digest.update(file.readBytes())
                }
                continue
            }
            val repository = entry.repository ?: continue
            if (repository.isDirectory) {
                digest.update(git(repository, listOf("rev-parse", "HEAD")).trim().toByteArray())
            }
            for (name in CORPORA) {
                val labels = File(labelRoot, "$name/relationships/commits/${repository.name}")
                if (!labels.isDirectory) continue
                val files = labels.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(labels).path }
                for (file in files) {
                    digest.update(file.relativeTo(labels).path.toByteArray())
                    digest.update(file.readBytes())
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun scalaVersion(): String = try {
        com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("org.intellij.scala"))?.version ?: "absent"
    } catch (e: Exception) {
        "unknown"
    }

    private fun format(value: Double): String = "%.3f".format(value)

    private fun git(repository: File, arguments: List<String>, allowFailure: Boolean = false): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.absolutePath) + arguments)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0 && !allowFailure) error("git ${arguments.joinToString(" ")} failed in $repository with exit code $exit")
        return output
    }

    private fun gitSucceeds(repository: File, arguments: List<String>): Boolean {
        val process = ProcessBuilder(listOf("git", "-C", repository.absolutePath) + arguments)
            .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        return process.waitFor() == 0
    }

    private fun <T> onPooledThread(task: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread(Callable { task() }).get(10, TimeUnit.MINUTES)
}
