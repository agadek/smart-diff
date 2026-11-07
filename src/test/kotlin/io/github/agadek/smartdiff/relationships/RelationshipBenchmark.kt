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
import io.github.agadek.smartdiff.engine.api.ConfidencePolicy
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.compareSafely
import io.github.agadek.smartdiff.engine.api.contract.NothingHidden
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationProvider
import io.github.agadek.smartdiff.references.ReferencePass
import io.github.agadek.smartdiff.ui.OutlineModel
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * The structural relationships benchmark: labelled cases, real commits, the relationships the result reports, their
 * metrics and confidence counts, the gates and the reports. The requirements are in
 * `docs/specs/relationships/01-relationship-benchmark.md`; the design and the test table are in its details file.
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
        "usage-grouping",
    )

    /** R9, R44: the usage-grouping category needs at least four cases per language. */
    val CATEGORY_MINIMUMS = mapOf("usage-grouping" to 4)

    /** The category that a checked-in real-commit label carries; it is outside the case matrix. */
    const val REAL_COMMIT_CATEGORY = "real-commit"

    /**
     * Categories a corpus does not have the construct for, with the reason the report shows (R30). In step 1 the
     * usage-grouping category has the Java resolver only; the Kotlin K2 and Scala resolvers arrive in step 2, which
     * removes these entries and adds their four fixtures.
     */
    val NOT_APPLICABLE: Map<String, Map<String, String>> = mapOf(
        "kotlin" to mapOf("usage-grouping" to "the Kotlin K2 reference resolver arrives in step 2"),
        "scala2" to mapOf("usage-grouping" to "the Scala reference resolver arrives in step 2"),
        "scala3" to mapOf("usage-grouping" to "the Scala reference resolver arrives in step 2"),
    )

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
            path == other.path && kind == other.kind && name == other.name && signatureKey() == other.signatureKey()

        /** The outline shows a method's signature in parentheses; accept either the raw or displayed form in labels. */
        private fun displaySignature(): String? = signature?.let { if (it.startsWith("(")) it else "($it)" }

        /** No-argument declarations come back as null, "" or "()" depending on the language; they are the same identity. */
        private fun signatureKey(): String? = displaySignature()?.takeUnless { it == "()" }

        /** A stable key for the baseline and report lists, including what tells duplicates apart. */
        fun key(): String =
            listOf(path, kind.name, name, displaySignature() ?: "-", range?.toString() ?: "-").joinToString("|")
    }

    data class LabelledRelationship(
        val kinds: Set<Kind>,
        val old: Endpoint,
        val new: Endpoint,
        val confidence: Confidence?,
        /** The grouped usage range pairs; null when the label is a draft that does not list them (R44). */
        val usages: List<Usage>? = null,
    )

    /** One grouped usage on the two sides, as a rename label lists it (R44). */
    data class Usage(val old: TextRange, val new: TextRange)

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
        /** The engine's score, from the relationship's `Score` evidence; null when it has none (relationships/03 R20). */
        val score: Double? = null,
    )

    /** One reported reference, with the rename relationship's endpoints (R44). */
    data class ActualUsage(val old: Endpoint, val new: Endpoint, val oldRange: TextRange, val newRange: TextRange)

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
        /** The reference evidence the pass grouped, for usage labels (R44). */
        val actualUsages: List<ActualUsage> = emptyList(),
        /** The reference pass's status for the report; null when the file ran no pass. */
        val referenceStatus: String? = null,
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
        /** R33: the reported relationships of each kind, at each confidence. */
        val confidenceByKind: Map<String, Map<Confidence, Int>> = emptyMap(),
        /** R44: grouped usage pairs, wrongly grouped usages, missed usages and renames with no `usages` label. */
        val usageTp: Int = 0,
        val usageFp: Int = 0,
        val usageFn: Int = 0,
        val usageUnlabelled: Int = 0,
    ) {
        /** R12: the pairs the engine reported; zero means precision has nothing to divide, so the report shows `n/a`. */
        val reportedPairs: Int get() = perKind.values.sumOf { it.tp + it.fp }
        val labelledPairs: Int get() = perKind.values.sumOf { it.tp + it.fn }
        val reportedUsages: Int get() = usageTp + usageFp
        val labelledUsages: Int get() = usageTp + usageFn
        val usagePrecision: Double get() = if (reportedUsages == 0) 1.0 else usageTp.toDouble() / reportedUsages
        val usageRecall: Double get() = if (labelledUsages == 0) 1.0 else usageTp.toDouble() / labelledUsages
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
        val metricsByCategory: Map<String, Metrics> = emptyMap(),
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
        /** The policy the run classified relationships with; report.json records it (relationships/02 R15). */
        val confidencePolicy: ConfidencePolicy = ConfidencePolicy.DEFAULT,
        /** R19/R44: the reference pass version that produced the usage evidence. */
        val referencePassVersion: String = ReferencePass.VERSION,
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
            // Absent is allowed in a draft only; a gate run reports it as usage-unlabelled (R44).
            if (map.containsKey("usages")) list(map["usages"]).map { usage(it, file) } else null,
        )
    }

    private fun usage(value: Any?, file: File): Usage {
        val map = map(value, file)
        return Usage(range(map["old"], file, "usage old"), range(map["new"], file, "usage new"))
    }

    private fun range(value: Any?, file: File, what: String): TextRange {
        val text = value as? String ?: error("label ${file.path}: $what range is required")
        val parts = text.split("..")
        require(parts.size == 2) { "label ${file.path}: $what range must be start..end" }
        return TextRange(parts[0].trim().toInt(), parts[1].trim().toInt())
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

    /**
     * The relationships [result] reports, one benchmark relationship per endpoint pair and kind (R31). A relationship of
     * several kinds becomes one row per kind, as the label can list them together (R38).
     *
     * An old endpoint's path comes from [oldTree] by its range: a cross-container move changed its container, so the new
     * side names the other one. Without that path, as in a test that builds only a result or when the second parse
     * fails, the result tree's own path for the old range is used, which is the new-side container.
     */
    fun reportedRelationships(result: ComparisonResult, oldTree: DeclarationTree? = null): List<ActualRelationship> {
        if (result.relationships.isEmpty()) return emptyList()
        val oldPaths = oldContainerPaths(oldTree)
        val paths = resultPaths(result)
        val relationships = mutableListOf<ActualRelationship>()
        for (relationship in result.relationships) {
            val score = relationship.evidence.filterIsInstance<Evidence.Score>().firstOrNull()?.value
            for (old in relationship.old) for (new in relationship.new) {
                val oldPath = oldPaths[old.range] ?: paths.old[old.range] ?: ""
                val newPath = paths.new[new.range] ?: ""
                for (kind in relationship.kinds) {
                    relationships += ActualRelationship(
                        Kind.valueOf(kind.name),
                        endpoint(old.subject, oldPath, old.range),
                        endpoint(new.subject, newPath, new.range),
                        confidence(relationship.confidence),
                        score,
                    )
                }
            }
        }
        return relationships
    }

    /** A relationship's confidence as the benchmark names it; the label's own confidence is still not scored. */
    private fun confidence(confidence: io.github.agadek.smartdiff.engine.api.Confidence): Confidence = when (confidence) {
        io.github.agadek.smartdiff.engine.api.Confidence.HIGH -> Confidence.HIGH
        io.github.agadek.smartdiff.engine.api.Confidence.MEDIUM -> Confidence.MEDIUM
        io.github.agadek.smartdiff.engine.api.Confidence.LOW -> Confidence.LOW
    }

    /** The reference evidence of [result], each with the rename relationship's endpoints (R44). */
    fun reportedUsages(result: ComparisonResult, oldTree: DeclarationTree? = null): List<ActualUsage> {
        if (result.relationships.isEmpty()) return emptyList()
        val oldPaths = oldContainerPaths(oldTree)
        val paths = resultPaths(result)
        val usages = mutableListOf<ActualUsage>()
        for (relationship in result.relationships) {
            if (Kind.RENAME !in relationship.kinds.map { Kind.valueOf(it.name) }) continue
            val references = relationship.evidence.filterIsInstance<Evidence.Reference>()
            if (references.isEmpty()) continue
            for (old in relationship.old) {
                val oldPath = oldPaths[old.range] ?: paths.old[old.range] ?: ""
                for (new in relationship.new) {
                    val newPath = paths.new[new.range] ?: ""
                    for (reference in references.filter { it.symbol == new.subject }) {
                        usages += ActualUsage(
                            endpoint(old.subject, oldPath, old.range),
                            endpoint(new.subject, newPath, new.range),
                            reference.oldRange,
                            reference.newRange,
                        )
                    }
                }
            }
        }
        return usages
    }

    /** The paths of the result tree by range, one map per side, so the two sides' offsets cannot be confused. */
    private class ResultPaths(val old: Map<TextRange, String>, val new: Map<TextRange, String>)

    private fun resultPaths(result: ComparisonResult): ResultPaths {
        val old = HashMap<TextRange, String>()
        val new = HashMap<TextRange, String>()
        fun visit(node: ChangeNode, ancestors: List<Subject>) {
            val path = ancestors.joinToString(" / ") { OutlineModel.title(it) }
            node.oldRange?.let { old.putIfAbsent(it, path) }
            node.newRange?.let { new.putIfAbsent(it, path) }
            node.children.forEach { visit(it, ancestors + node.subject) }
        }
        result.fileRegion?.let { visit(it, emptyList()) }
        result.changes.forEach { visit(it, emptyList()) }
        return ResultPaths(old, new)
    }

    /** Each declaration's container path on the old side, by the declaration's range in the old text. */
    private fun oldContainerPaths(tree: DeclarationTree?): Map<TextRange, String> {
        if (tree == null) return emptyMap()
        val paths = HashMap<TextRange, String>()
        fun visit(declaration: Declaration, ancestors: List<Subject>) {
            paths[declaration.range] = ancestors.joinToString(" / ") { OutlineModel.title(it) }
            val subject = Subject.Declaration(declaration.kind, declaration.name, declaration.displaySignature)
            declaration.children.forEach { visit(it, ancestors + subject) }
        }
        tree.topLevel.forEach { visit(it, emptyList()) }
        return paths
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
        var usageTp = 0
        var usageFp = 0
        var usageFn = 0
        var usageUnlabelled = 0
        val confidenceByKind = mutableMapOf<String, MutableMap<Confidence, Int>>()
        for (result in results) {
            val label = result.label
            val claimed = claimedRelationships(result)
            for (expected in label.relationships) {
                val matchedKinds = mutableSetOf<Kind>()
                result.actualRelationships.forEachIndexed { index, actual ->
                    if (claimed[index] && actual.kind in expected.kinds && endpointsMatch(expected.old, actual.old) && endpointsMatch(expected.new, actual.new)) {
                        matchedKinds += actual.kind
                        perKind.getOrPut(actual.kind.name) { PairCounts() }.tp++
                    }
                }
                (expected.kinds - matchedKinds).forEach { perKind.getOrPut(it.name) { PairCounts() }.fn++ }
            }
            result.actualRelationships.forEachIndexed { index, actual ->
                // R33: every reported relationship counts, whatever its kind scored.
                confidenceByKind.getOrPut(actual.kind.name) { mutableMapOf() }.merge(actual.confidence, 1, Int::plus)
                // A low relationship is not a pair: it is never a true positive, false positive or forced pair
                // (relationships/03, `Benchmark treatment of low confidence`).
                if (actual.confidence == Confidence.LOW) return@forEachIndexed
                val forcedPair = label.ambiguous.any { endpointsMatch(it, actual.old) || endpointsMatch(it, actual.new) }
                if (forcedPair) forced++ else if (!claimed[index]) perKind.getOrPut(actual.kind.name) { PairCounts() }.fp++
            }
            val matchedNodes = matchingNodes(result)
            for ((index, expected) in label.changes.withIndex()) {
                total++
                val actual = matchedNodes[index]
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
            val expectedRenames = label.relationships.filter { Kind.RENAME in it.kinds }
            val usedUsages = mutableSetOf<Int>()
            for (expected in expectedRenames) {
                val actualIndices = result.actualUsages.indices.filter { index ->
                    index !in usedUsages &&
                        endpointsMatch(expected.old, result.actualUsages[index].old) &&
                        endpointsMatch(expected.new, result.actualUsages[index].new)
                }
                val labelled = expected.usages
                if (labelled == null) {
                    if (ReferencePass.supports(label.language)) usageUnlabelled += actualIndices.size
                    usedUsages += actualIndices
                    continue
                }
                val remaining = actualIndices.toMutableList()
                for (usage in labelled) {
                    val match = remaining.firstOrNull { result.actualUsages[it].oldRange == usage.old && result.actualUsages[it].newRange == usage.new }
                    if (match == null) {
                        usageFn++
                    } else {
                        usageTp++
                        remaining.remove(match)
                        usedUsages += match
                    }
                }
                usageFp += remaining.size
                usedUsages += remaining
            }
            if (ReferencePass.supports(label.language)) {
                usageUnlabelled += result.actualUsages.indices.count { it !in usedUsages }
            }
        }
        // Kinds in enum order, so the rows of two runs and of the baseline line up (R28).
        val orderedKinds = Kind.entries.mapNotNull { kind -> perKind[kind.name]?.let { kind.name to it } }.toMap()
        val orderedConfidence = Kind.entries.mapNotNull { kind -> confidenceByKind[kind.name]?.let { kind.name to it } }.toMap()
        return Metrics(
            orderedKinds,
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
            orderedConfidence,
            usageTp,
            usageFp,
            usageFn,
            usageUnlabelled,
        )
    }

    /**
     * Which reported relationship each expected kind claims, one per expected kind and kind (R32, R38). A second copy of
     * a reported relationship claims nothing, so it is a false positive and fails the `no-new-wrong-pairs` gate.
     */
    fun claimedRelationships(case: CaseResult): BooleanArray {
        val claimed = BooleanArray(case.actualRelationships.size)
        for (expected in case.label.relationships) {
            for (kind in expected.kinds) {
                val index = case.actualRelationships.indices.firstOrNull { i ->
                    !claimed[i] && case.actualRelationships[i].confidence != Confidence.LOW &&
                        case.actualRelationships[i].kind == kind &&
                        endpointsMatch(expected.old, case.actualRelationships[i].old) &&
                        endpointsMatch(expected.new, case.actualRelationships[i].new)
                }
                if (index != null) claimed[index] = true
            }
        }
        return claimed
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
        categoryMinimums: Map<String, Int> = CATEGORY_MINIMUMS,
    ): List<MatrixFailure> = buildList {
        for (corpus in corpora) {
            for (category in categories) {
                if (notApplicable[corpus]?.containsKey(category) == true) continue
                val count = labels.count { it.corpus == corpus && it.category == category && it.reviewed }
                val needed = categoryMinimums[category] ?: minimum
                if (count < needed) add(MatrixFailure(corpus, category, count, needed))
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

    /**
     * One node per expected change (R15, R42). Overloads can share path, kind, name and signature, so a node is claimed
     * once and removed from the pool: the node with the expected presence wins among the nodes left, so an added
     * overload is not confused with an unchanged one of the same identity.
     */
    fun matchingNodes(case: CaseResult): List<ChangeNode?> {
        val remaining = case.actualNodes.toMutableList()
        return case.label.changes.map { expected ->
            val preferred = remaining.indexOfFirst { (endpoint, node) ->
                endpointsMatch(expected.subject, endpoint) && node.presence == expected.presence
            }
            val chosen = if (preferred >= 0) preferred else remaining.indexOfFirst { (endpoint, _) -> endpointsMatch(expected.subject, endpoint) }
            if (chosen < 0) null else remaining.removeAt(chosen).second
        }
    }

    /** One high-threshold candidate: how many rename pairs it would call high, and how many of those are correct. */
    data class TuningRow(val threshold: Double, val high: Int, val correctHigh: Int, val correctPairs: Int) {
        val precision: Double get() = if (high == 0) 1.0 else correctHigh.toDouble() / high
        val coverage: Double get() = if (correctPairs == 0) 0.0 else correctHigh.toDouble() / correctPairs
    }

    /**
     * The high-threshold table of relationships/03 R20, over [cases] only. A pair is correct when the label claims its
     * `RENAME` kind; a low relationship is not a pair and is left out. Every score comes from the engine's `Score`
     * evidence, so the table needs no second run.
     */
    fun tuningTable(cases: List<CaseResult>, from: Double = 0.70, to: Double = 1.00, step: Double = 0.01): List<TuningRow> {
        val pairs = mutableListOf<Pair<Double, Boolean>>()
        for (case in cases) {
            val claimed = claimedRelationships(case)
            case.actualRelationships.forEachIndexed { index, actual ->
                if (actual.kind != Kind.RENAME || actual.confidence == Confidence.LOW) return@forEachIndexed
                val score = actual.score ?: return@forEachIndexed
                pairs += score to claimed[index]
            }
        }
        val correctPairs = pairs.count { it.second }
        val rows = mutableListOf<TuningRow>()
        var threshold = from
        while (threshold <= to + 1e-9) {
            val high = pairs.filter { it.first >= threshold }
            rows += TuningRow(threshold, high.size, high.count { it.second }, correctPairs)
            threshold += step
        }
        return rows
    }

    /** The highest threshold that keeps high-confidence precision at least 95% and three quarters of correct pairs high. */
    fun tunedHigh(rows: List<TuningRow>): Double? =
        rows.filter { it.precision >= 0.95 && it.coverage >= 0.75 }.maxOfOrNull { it.threshold }

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
        confidence: ConfidencePolicy = ConfidencePolicy.DEFAULT,
        /** Only score cases of this split; null scores every split. The tuning run uses [Split.TUNING] only (R20). */
        split: Split? = null,
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
                runCase(engine, directory, confidence, split)?.let { cases += it }
                continue
            }
            val line = runCommitLine(engine, entry, labelRoot, drafts != null, confidence, split)
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
        val byCategory = cases.groupBy { it.label.category }.toSortedMap().mapValues { evaluate(it.value.map { row -> row.label }, it.value) }
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
            metricsByCategory = byCategory,
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
            confidencePolicy = confidence,
        )
        write(report)
        return report
    }

    private fun runCase(engine: DeclarationEngine, directory: File, confidence: ConfidencePolicy, split: Split?): CaseResult? {
        val labelFile = File(directory, "label.yaml")
        require(labelFile.isFile) { "case ${directory.path} has no label.yaml" }
        val label = parseLabel(labelFile)
        if (split != null && label.split != split) return null
        require(label.source == null) { "label ${labelFile.path}: a case folder label must not have a source" }
        require(label.category in CATEGORIES) { "label ${labelFile.path}: unknown category ${label.category}" }
        val extension = extensionFor(label.language)
        val oldFile = File(directory, "old.$extension")
        val newFile = File(directory, "new.$extension")
        require(oldFile.isFile) { "case ${directory.path} has no ${oldFile.name}" }
        require(newFile.isFile) { "case ${directory.path} has no ${newFile.name}" }
        val old = oldFile.readText().replace("\r\n", "\n")
        val new = newFile.readText().replace("\r\n", "\n")
        val request = ComparisonRequest(old, new, label.language, "${label.case}.$extension", confidence = confidence)
        val outcome = compare(engine, request)
        val oldTree = oldTree(engine, request, outcome.result)
        return CaseResult(
            label, outcome.result, reportedRelationships(outcome.result, oldTree), actualNodes(outcome.result),
            outcome.millis, hidden(old, new, outcome.result), outcome.exception,
            actualUsages = reportedUsages(outcome.result, oldTree),
            referenceStatus = outcome.referenceStatus,
        )
    }

    private fun runCommitLine(
        engine: DeclarationEngine,
        entry: CorpusEntry,
        labelRoot: File,
        draft: Boolean,
        confidence: ConfidencePolicy,
        split: Split?,
    ): CommitLine {
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
                // A rename is a delete plus an add, so it is listed as skipped instead of vanishing from the run.
                listOf("diff", "--no-renames", "--name-status", "--diff-filter=ADM", parent, commit, "--", "*.java", "*.kt", "*.scala"),
            ).lines().map { it.trim() }.filter { it.isNotEmpty() }
            val files = mutableListOf<CommitFile>()
            for (change in changes) {
                val parts = change.split(Regex("\\s+"), limit = 2)
                val status = parts.first()
                val path = parts.getOrNull(1) ?: continue
                if (status != "M") {
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
                // R33: an unlabelled Scala file's dialect decides its corpus; an unknown dialect is listed, not filed as Scala 2.
                val corpus = label?.corpus ?: if (path.endsWith(".scala")) scalaCorpusFor(dialectId(old, new)) else candidates.single()
                val languageId = label?.language ?: languageIdFor(path)
                val request = ComparisonRequest(old, new, languageId, File(path).name, confidence = confidence)
                val outcome = compare(engine, request)
                val id = "${repository.name} ${commit.take(7)} $path"
                val scored = label != null && label.reviewed && (split == null || label.split == split)
                if (scored) {
                    val oldDeclarationTree = oldTree(engine, request, outcome.result)
                    result.cases += CaseResult(
                        label!!, outcome.result, reportedRelationships(outcome.result, oldDeclarationTree), actualNodes(outcome.result),
                        outcome.millis, hidden(old, new, outcome.result), outcome.exception, id,
                        actualUsages = reportedUsages(outcome.result, oldDeclarationTree),
                        referenceStatus = outcome.referenceStatus,
                    )
                } else if (label?.reviewed != true) {
                    val reason = when {
                        label == null && corpus == null -> "unknown dialect"
                        label == null -> "no label"
                        else -> "not reviewed"
                    }
                    result.unlabelled += UnlabelledFile(repository.name, commit, path, corpus ?: "unknown", reason)
                    val hiddenHere = hidden(old, new, outcome.result)
                    result.safety += safetyProblems(id, hiddenHere, outcome)
                    if (hiddenHere) result.unlabelledHidden++
                    if (outcome.result.status != Status.ANALYZED && outcome.result.status != Status.PARTIAL) result.unlabelledFallback++
                    if (draft && corpus != null) result.drafts += draft(repository.name, commit, path, corpus, languageId, outcome)
                }
                files += CommitFile(corpus, label?.reviewed == true)
            }
            // R10: a commit counts when every modified file has a reviewed label; added and deleted files do not block it.
            if (files.isNotEmpty() && files.all { it.reviewed } && files.all { it.corpus != null }) {
                files.mapNotNull { it.corpus }.distinct().forEach { corpusName -> result.labelledCommits[corpusName] = (result.labelledCommits[corpusName] ?: 0) + 1 }
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

    private data class CommitFile(val corpus: String?, val reviewed: Boolean)

    private data class Draft(val corpus: String, val repository: String, val commit: String, val path: String, val text: String)

    /** The old-side tree, only when a relationship has an old endpoint, so a run without relationships does not parse twice. */
    private fun oldTree(engine: DeclarationEngine, request: ComparisonRequest, result: ComparisonResult): DeclarationTree? {
        if (result.relationships.isEmpty()) return null
        // The old side is parsed a second time, deliberately outside Outcome.millis: the comparison's time is what the
        // report measures. A provider error here must not abort the run, so the new-side path fallback is used instead.
        return runCatching { onPooledThread { engine.trees(request, Cancellation.NONE)?.first } }.getOrNull()
    }

    private fun compare(engine: ComparisonEngine, request: ComparisonRequest): Outcome {
        val started = System.nanoTime()
        return try {
            val result = onPooledThread { compareSafely(engine, request, Cancellation.NONE) }
            val usageStarted = System.nanoTime()
            val pass = if (result.status == Status.ANALYZED || result.status == Status.PARTIAL) {
                ReferencePass.runForBenchmark(null, request, result, Cancellation.NONE)
            } else {
                null
            }
            Outcome(
                pass?.result ?: result,
                (usageStarted - started) / 1_000_000,
                null,
                pass?.status?.name,
                (System.nanoTime() - usageStarted) / 1_000_000,
            )
        } catch (e: Exception) {
            Outcome(ComparisonResult(engine.id, engine.version, Status.FAILED, fallbackReason = e.message), (System.nanoTime() - started) / 1_000_000, e.message)
        }
    }

    private data class Outcome(
        val result: ComparisonResult,
        val millis: Long,
        val exception: String? = null,
        val referenceStatus: String? = null,
        val usageMillis: Long = 0,
    )

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

    /** R33: an unlabelled Scala file's dialect id, or null when no provider is loaded or the lookup throws. */
    private fun dialectId(old: String, new: String): String? = try {
        val provider = DeclarationProvider.EP_NAME.extensionList.firstOrNull { it.supports("Scala") }
        if (provider == null) {
            null
        } else {
            ReadAction.compute(ThrowableComputable<String?, RuntimeException> {
                provider.dialect(ProjectManager.getInstance().defaultProject, listOf(old, new), null)?.id
            })
        }
    } catch (e: Exception) {
        null
    }

    /** R33: the corpus an unlabelled Scala file is counted under; null means `unknown dialect`, never Scala 2 by default. */
    fun scalaCorpusFor(dialectId: String?): String? = when (dialectId) {
        "Scala 3" -> "scala3"
        "Scala 2" -> "scala2"
        else -> null
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
            val relationships = reportedRelationships(outcome.result)
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
        val unlabelled = usageUnlabelledRenames(cases)
        if (unlabelled.isNotEmpty()) add("usage-unlabelled renames: ${unlabelled.joinToString(", ")}")
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
                val currentWrong = wrongPairs(cases) + forcedPairs(cases) + wrongUsages(cases)
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
            val claimed = claimedRelationships(case)
            case.actualRelationships.forEachIndexed { index, actual ->
                if (actual.confidence == Confidence.LOW) return@forEachIndexed
                val forced = label.ambiguous.any { endpointsMatch(it, actual.old) || endpointsMatch(it, actual.new) }
                if (!claimed[index] && !forced) add("${label.case}|${actual.kind}|${actual.old.key()}|${actual.new.key()}")
            }
        }
    }

    /** R18: the wrong and forced pairs with the label's path, as the report lists them; [wrongPairs] stays stable for gates. */
    fun wrongPairLines(cases: List<CaseResult>): List<String> = buildList {
        for (case in cases) {
            val label = case.label
            val claimed = claimedRelationships(case)
            case.actualRelationships.forEachIndexed { index, actual ->
                if (actual.confidence == Confidence.LOW) return@forEachIndexed
                val forced = label.ambiguous.any { endpointsMatch(it, actual.old) || endpointsMatch(it, actual.new) }
                if (forced) {
                    add("${label.case}|forced|${actual.kind}|${actual.old.name}|${actual.new.name}|${label.path ?: "-"}")
                } else if (!claimed[index]) {
                    add("${label.case}|wrong|${actual.kind}|${actual.old.name}|${actual.new.name}|${label.path ?: "-"}")
                }
            }
        }
    }

    fun forcedPairs(cases: List<CaseResult>): Set<String> = buildSet {
        for (case in cases) {
            for (actual in case.actualRelationships) {
                if (actual.confidence == Confidence.LOW) continue
                if (case.label.ambiguous.any { endpointsMatch(it, actual.old) || endpointsMatch(it, actual.new) }) {
                    add("${case.label.case}|forced|${actual.kind}|${actual.old.key()}|${actual.new.key()}")
                }
            }
        }
    }

    /** R44: a reported reference on a `usages`-labelled rename with no matching pair is a wrong pair. */
    fun wrongUsages(cases: List<CaseResult>): Set<String> = buildSet {
        for (case in cases) {
            for ((expected, actuals) in usagePairs(case)) {
                if (expected.usages == null) continue
                val remaining = actuals.toMutableList()
                for (usage in expected.usages) {
                    val match = remaining.indexOfFirst { it.oldRange == usage.old && it.newRange == usage.new }
                    if (match >= 0) remaining.removeAt(match)
                }
                remaining.forEach { actual ->
                    add("${case.label.case}|usage|${expected.old.key()}|${expected.new.key()}|${actual.oldRange}|${actual.newRange}")
                }
            }
        }
    }

    /** R44: a listed usage that the pass did not report; listed, not gated by default. */
    fun missedUsages(cases: List<CaseResult>): List<String> = buildList {
        for (case in cases) {
            for ((expected, actuals) in usagePairs(case)) {
                val listed = expected.usages ?: continue
                val remaining = actuals.toMutableList()
                for (usage in listed) {
                    val match = remaining.indexOfFirst { it.oldRange == usage.old && it.newRange == usage.new }
                    if (match >= 0) {
                        remaining.removeAt(match)
                    } else {
                        add("${case.label.case}|missed-usage|${expected.old.name}|${expected.new.name}|${usage.old}|${usage.new}|${case.label.path ?: "-"}")
                    }
                }
            }
        }
    }

    /** R44: a rename label in a language the pass covers without a `usages` list fails completeness. */
    fun usageUnlabelledRenames(cases: List<CaseResult>): List<String> = buildList {
        for (case in cases) {
            if (!ReferencePass.supports(case.label.language)) continue
            for (relationship in case.label.relationships) {
                if (Kind.RENAME !in relationship.kinds) continue
                if (relationship.usages == null) {
                    add("${case.label.case}|usage-unlabelled|${relationship.old.name}|${relationship.new.name}|${case.label.path ?: "-"}")
                }
            }
        }
    }

    /** R44: reported references on a rename whose label has no `usages` list, counted and not scored. */
    fun unlabelledUsages(cases: List<CaseResult>): List<String> = buildList {
        for (case in cases) {
            if (!ReferencePass.supports(case.label.language)) continue
            for ((expected, actuals) in usagePairs(case)) {
                if (expected.usages != null) continue
                actuals.forEach { actual ->
                    add("${case.label.case}|usage-unlabelled|${expected.old.name}|${expected.new.name}|${actual.oldRange}|${actual.newRange}|${case.label.path ?: "-"}")
                }
            }
            val labelled = case.label.relationships.filter { Kind.RENAME in it.kinds }
            case.actualUsages.filter { usage ->
                labelled.none { endpointsMatch(it.old, usage.old) && endpointsMatch(it.new, usage.new) }
            }.forEach { usage ->
                add("${case.label.case}|usage-unlabelled|${usage.old.name}|${usage.new.name}|${usage.oldRange}|${usage.newRange}|${case.label.path ?: "-"}")
            }
        }
    }

    /** The label's rename relationships and the reported usages that name the same endpoints. */
    private fun usagePairs(case: CaseResult): List<Pair<LabelledRelationship, List<ActualUsage>>> =
        case.label.relationships.filter { Kind.RENAME in it.kinds }.map { expected ->
            expected to case.actualUsages.filter {
                endpointsMatch(expected.old, it.old) && endpointsMatch(expected.new, it.new)
            }
        }

    fun missedRelationships(cases: List<CaseResult>): List<String> = buildList {
        for (case in cases) {
            val claimed = claimedRelationships(case)
            for (expected in case.label.relationships) {
                val found = case.actualRelationships.indices
                    .filter { claimed[it] }
                    .map { case.actualRelationships[it] }
                    .filter { actual ->
                        actual.kind in expected.kinds && endpointsMatch(expected.old, actual.old) && endpointsMatch(expected.new, actual.new)
                    }
                    .map { it.kind }
                    .toSet()
                (expected.kinds - found).forEach { kind ->
                    add("${case.label.case}|missed|$kind|${expected.old.name}|${expected.new.name}|${case.label.path ?: "-"}")
                }
            }
        }
    }

    fun classificationMismatches(cases: List<CaseResult>): List<String> = buildList {
        for (case in cases) {
            val matchedNodes = matchingNodes(case)
            for ((index, expected) in case.label.changes.withIndex()) {
                val actual = matchedNodes[index]
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
                for (kind in kinds(metrics)) {
                    val counts = metrics.perKind[kind] ?: PairCounts()
                    appendLine(listOf(language, kind, counts.tp, counts.fp, counts.fn, formatPrecision(counts), formatRecall(counts), format(counts.wrongShare)).joinToString("\t"))
                }
                appendLine(
                    listOf(
                        language, "USAGE", metrics.usageTp, metrics.usageFp, metrics.usageFn,
                        formatUsagePrecision(metrics), formatUsageRecall(metrics), "",
                    ).joinToString("\t"),
                )
            }
        })
        File(report.reportDirectory, "report.md").writeText(buildString {
            appendLine("# SMART Diff relationship benchmark")
            appendLine()
            appendLine(if (report.smoke) "Status: smoke run" else "Status: complete run")
            appendLine("Engine: ${DeclarationEngine.ID} ${DeclarationEngine.VERSION}; IDE: ${ApplicationInfo.getInstance().build.asStringWithoutProductCode()}")
            appendLine("Reference pass: ${report.referencePassVersion}")
            appendLine("Scala plugin: ${scalaVersion()}; corpus revision: ${report.corpusRevision}; gate: ${report.gateMode}")
            appendLine("Analysis limit: ${TIME_LIMIT_MILLIS} ms per file; reference pass limit: ${ReferencePass.BUDGET_MILLIS} ms per file")
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
            appendLine(
                "Usages: ${report.overall.reportedUsages} reported, ${report.overall.usageTp} matched, " +
                    "${report.overall.usageFp} wrong, ${report.overall.usageFn} missed, ${report.overall.usageUnlabelled} unlabelled",
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
                    "Precision ${formatMetricsPrecision(metrics)}, recall ${formatMetricsRecall(metrics)}, " +
                        "presence ${format(metrics.presenceAccuracy)}, content ${format(metrics.contentAccuracy)}, " +
                        "classification ${format(metrics.classificationAccuracy)}, median ${metrics.medianMillis} ms, " +
                        "p95 ${metrics.p95Millis} ms, slowest ${metrics.slowestMillis} ms of $TIME_LIMIT_MILLIS ms",
                )
                appendLine()
            }
            appendLine("## Per corpus")
            for ((language, metrics) in report.metricsByLanguage) {
                appendLine(
                    "- $language: precision ${formatMetricsPrecision(metrics)}, recall ${formatMetricsRecall(metrics)}, " +
                        "wrong share ${format(metrics.wrongShare)}, presence ${format(metrics.presenceAccuracy)}, " +
                        "content ${format(metrics.contentAccuracy)}, moved ${format(metrics.movedAccuracy)}, " +
                        "signature ${format(metrics.signatureAccuracy)}, kind ${format(metrics.kindAccuracy)}, " +
                        "usage precision ${formatUsagePrecision(metrics)}, usage recall ${formatUsageRecall(metrics)}, " +
                        "median ${metrics.medianMillis} ms, p95 ${metrics.p95Millis} ms, slowest ${metrics.slowestMillis} ms of $TIME_LIMIT_MILLIS ms",
                )
            }
            appendLine()
            appendLine("## Per kind")
            appendLine("| Corpus | Kind | TP | FP | FN | Precision | Recall |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- |")
            for (corpus in CORPORA) {
                val metrics = report.metricsByLanguage[corpus] ?: continue
                for (kind in kinds(metrics)) {
                    val counts = metrics.perKind[kind] ?: PairCounts()
                    appendLine("| $corpus | $kind | ${counts.tp} | ${counts.fp} | ${counts.fn} | ${formatPrecision(counts)} | ${formatRecall(counts)} |")
                }
            }
            appendLine()
            appendLine("## Confidence counts")
            appendLine("Reported relationships of each kind at each confidence, per corpus (R33). A label's confidence is not scored.")
            appendLine()
            appendLine("| Corpus | Kind | HIGH | MEDIUM | LOW |")
            appendLine("| --- | --- | --- | --- | --- |")
            for (corpus in CORPORA) {
                val metrics = report.metricsByLanguage[corpus] ?: continue
                for (kind in kinds(metrics)) {
                    val counts = metrics.confidenceByKind[kind].orEmpty()
                    appendLine(
                        "| $corpus | $kind | ${counts[Confidence.HIGH] ?: 0} | ${counts[Confidence.MEDIUM] ?: 0} | " +
                            "${counts[Confidence.LOW] ?: 0} |",
                    )
                }
            }
            appendLine()
            if (report.metricsByCategory.isNotEmpty()) {
                appendLine("## Per category")
                appendLine("| Category | Precision | Recall | Presence | Content | Cases |")
                appendLine("| --- | --- | --- | --- | --- | --- |")
                for ((category, metrics) in report.metricsByCategory) {
                    appendLine(
                        "| $category | ${formatMetricsPrecision(metrics)} | ${formatMetricsRecall(metrics)} | " +
                            "${format(metrics.presenceAccuracy)} | ${format(metrics.contentAccuracy)} | ${metrics.presenceTotal} |",
                    )
                }
                appendLine()
            }
            if (report.newGatePairs.isNotEmpty()) {
                appendLine("## New in this run")
                report.newGatePairs.forEach { appendLine("- $it") }
                appendLine()
            }
            appendLine("## Wrong and forced relationships")
            val wrong = wrongPairLines(report.cases).sorted()
            if (wrong.isEmpty()) appendLine("- none") else wrong.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Wrong usages")
            val wrongUsages = wrongUsages(report.cases).sorted()
            if (wrongUsages.isEmpty()) appendLine("- none") else wrongUsages.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Missed usages")
            val missedUsages = missedUsages(report.cases).sorted()
            if (missedUsages.isEmpty()) appendLine("- none") else missedUsages.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Usage-unlabelled renames")
            val unlabelledRenames = usageUnlabelledRenames(report.cases).sorted()
            if (unlabelledRenames.isEmpty()) appendLine("- none") else unlabelledRenames.forEach { appendLine("- $it") }
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
            "referencePassVersion" to report.referencePassVersion,
            "ide" to ApplicationInfo.getInstance().build.asStringWithoutProductCode(),
            "scalaPlugin" to scalaVersion(),
            "smoke" to report.smoke,
            "shortfalls" to report.matrixFailures.map { it.message },
            "notApplicable" to report.notApplicable,
            "labelledCommits" to report.labelledCommits,
            "wrongPairs" to (wrongPairs(report.cases) + forcedPairs(report.cases)).sorted(),
            "wrongPairDetails" to wrongPairLines(report.cases).sorted(),
            "wrongUsages" to wrongUsages(report.cases).sorted(),
            "missedUsages" to missedUsages(report.cases).sorted(),
            "usageUnlabelled" to unlabelledUsages(report.cases).sorted(),
            "usageUnlabelledRenames" to usageUnlabelledRenames(report.cases).sorted(),
            "missed" to missedRelationships(report.cases),
            "classificationMismatches" to classificationMismatches(report.cases),
            "precision" to precision,
            "recall" to recall,
            "overall" to metricsJson(report.overall),
            "perLanguage" to report.metricsByLanguage.mapValues { metricsJson(it.value) },
            "perSplit" to report.metricsBySplit.mapKeys { it.key.name.lowercase() }.mapValues { metricsJson(it.value) },
            "perCategory" to report.metricsByCategory.mapValues { metricsJson(it.value) },
            "gate" to report.gateMode,
            "confidencePolicy" to mapOf("high" to report.confidencePolicy.high, "medium" to report.confidencePolicy.medium),
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

    /** Every kind the run saw, paired or low-only, in [Kind] order so the reports stay deterministic (R33). */
    private fun kinds(metrics: Metrics): List<String> =
        (metrics.perKind.keys + metrics.confidenceByKind.keys).distinct().sortedBy { Kind.valueOf(it).ordinal }

    private fun metricsJson(metrics: Metrics): Map<String, Any> = mapOf(
        "precision" to metrics.precision,
        "recall" to metrics.recall,
        "reportedPairs" to metrics.reportedPairs,
        "labelledPairs" to metrics.labelledPairs,
        "wrongShare" to metrics.wrongShare,
        "forcedPairs" to metrics.forcedPairs,
        "classificationAccuracy" to metrics.classificationAccuracy,
        "presenceAccuracy" to metrics.presenceAccuracy,
        "contentAccuracy" to metrics.contentAccuracy,
        "movedAccuracy" to metrics.movedAccuracy,
        "signatureAccuracy" to metrics.signatureAccuracy,
        "kindAccuracy" to metrics.kindAccuracy,
        "usageTp" to metrics.usageTp,
        "usageFp" to metrics.usageFp,
        "usageFn" to metrics.usageFn,
        "usageUnlabelled" to metrics.usageUnlabelled,
        "usagePrecision" to metrics.usagePrecision,
        "usageRecall" to metrics.usageRecall,
        "hidden" to metrics.hidden,
        "fallback" to metrics.fallback,
        "medianMillis" to metrics.medianMillis,
        "p95Millis" to metrics.p95Millis,
        "slowestMillis" to metrics.slowestMillis,
        "perKind" to kinds(metrics).associateWith { kind ->
            val counts = metrics.perKind[kind] ?: PairCounts()
            mapOf(
                "tp" to counts.tp, "fp" to counts.fp, "fn" to counts.fn,
                "reportedPairs" to (counts.tp + counts.fp),
                "precision" to counts.precision, "recall" to counts.recall,
                "confidence" to mapOf(
                    "HIGH" to (metrics.confidenceByKind[kind]?.get(Confidence.HIGH) ?: 0),
                    "MEDIUM" to (metrics.confidenceByKind[kind]?.get(Confidence.MEDIUM) ?: 0),
                    "LOW" to (metrics.confidenceByKind[kind]?.get(Confidence.LOW) ?: 0),
                ),
            )
        },
    )

    private fun formatPrecision(counts: PairCounts): String = if (counts.tp + counts.fp == 0) "n/a" else format(counts.precision)

    private fun formatRecall(counts: PairCounts): String = if (counts.tp + counts.fn == 0) "n/a" else format(counts.recall)

    private fun formatMetricsPrecision(metrics: Metrics): String = if (metrics.reportedPairs == 0) "n/a" else format(metrics.precision)

    private fun formatMetricsRecall(metrics: Metrics): String = if (metrics.labelledPairs == 0) "n/a" else format(metrics.recall)

    private fun formatUsagePrecision(metrics: Metrics): String = if (metrics.reportedUsages == 0) "n/a" else format(metrics.usagePrecision)

    private fun formatUsageRecall(metrics: Metrics): String = if (metrics.labelledUsages == 0) "n/a" else format(metrics.usageRecall)

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
        val process = ProcessBuilder(listOf("git", "-c", "core.quotePath=false", "-C", repository.absolutePath) + arguments)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0 && !allowFailure) error("git ${arguments.joinToString(" ")} failed in $repository with exit code $exit")
        return output
    }

    private fun gitSucceeds(repository: File, arguments: List<String>): Boolean {
        val process = ProcessBuilder(listOf("git", "-c", "core.quotePath=false", "-C", repository.absolutePath) + arguments)
            .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        return process.waitFor() == 0
    }

    private fun <T> onPooledThread(task: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread(Callable { task() }).get(10, TimeUnit.MINUTES)
}
