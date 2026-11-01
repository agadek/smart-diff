package io.github.agadek.smartdiff.relationships

import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
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
import io.github.agadek.smartdiff.ui.OutlineModel
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/** The structural relationships benchmark: labelled cases, current-engine adapter, metrics, gates and reports. */
object RelationshipBenchmark {

    const val CORPUS_PROPERTY = "smartdiff.relationships.corpus"
    const val GATE_PROPERTY = "smartdiff.relationships.gate"
    const val BASELINE_PROPERTY = "smartdiff.relationships.baseline"
    const val TOLERANCE_PROPERTY = "smartdiff.relationships.tolerance"
    const val DEFAULT_GATE = "no-new-wrong-pairs"
    const val TIME_LIMIT_MILLIS = 5000L

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

    data class Label(
        val case: String,
        val language: String,
        val split: Split,
        val relationships: List<LabelledRelationship>,
        val changes: List<ExpectedChange>,
        val ambiguous: List<Endpoint>,
        val notes: String?,
        val category: String = case.substringBeforeLast('-', case),
        val corpus: String = language.lowercase(),
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
    )

    data class CorpusEntry(val path: File?, val repository: File?, val arguments: List<String>)

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
    ) {
        val classificationAccuracy: Double get() = if (classificationTotal == 0) 1.0 else classificationCorrect.toDouble() / classificationTotal
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
    ) {
        val ready: Boolean get() = gateFailures.isEmpty()
    }

    fun listFile(value: String?): File? = value?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)

    fun parseCorpus(file: File): List<CorpusEntry> = file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val directory = File(line)
            if (directory.isDirectory) {
                CorpusEntry(directory, null, emptyList())
            } else {
                val words = line.split(Regex("\\s+"))
                CorpusEntry(null, File(words.first()), words.drop(1))
            }
        }

    fun parseLabel(file: File): Label {
        val root = Yaml().load<Map<String, Any?>>(file.reader()) ?: error("empty label: ${file.path}")
        val expected = map(root["expected"])
        return Label(
            case = root["case"] as? String ?: error("label ${file.path}: case is required"),
            language = root["language"] as? String ?: error("label ${file.path}: language is required"),
            split = when ((root["split"] as? String)?.lowercase()) {
                null, "tuning" -> Split.TUNING
                "held-out", "heldout" -> Split.HELD_OUT
                else -> error("label ${file.path}: unknown split ${root["split"]}")
            },
            relationships = list(expected["relationships"]).map { relationship(it, file) },
            changes = list(expected["changes"]).map { change(it, file) },
            ambiguous = list(expected["ambiguous"]).map { endpoint(it, file) },
            notes = root["notes"] as? String,
            category = root["category"] as? String ?: (root["case"] as String).substringBeforeLast('-'),
            corpus = root["corpus"] as? String ?: (root["language"] as String).lowercase(),
        )
    }

    private fun relationship(value: Any?, file: File): LabelledRelationship {
        val map = map(value)
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
        val map = map(value)
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
        val map = map(value)
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

    @Suppress("UNCHECKED_CAST")
    private fun map(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: error("expected a mapping, got $value")
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
        for (result in results) {
            val label = result.label
            val matched = BooleanArray(result.actualRelationships.size)
            for (expected in label.relationships) {
                var any = false
                result.actualRelationships.forEachIndexed { index, actual ->
                    if (actual.kind in expected.kinds && endpointsMatch(expected.old, actual.old) && endpointsMatch(expected.new, actual.new)) {
                        matched[index] = true
                        any = true
                        perKind.getOrPut(actual.kind.name) { PairCounts() }.tp++
                    }
                }
                if (!any) expected.kinds.forEach { perKind.getOrPut(it.name) { PairCounts() }.fn++ }
            }
            result.actualRelationships.forEachIndexed { index, actual ->
                val forcedPair = label.ambiguous.any { endpointsMatch(it, actual.old) || endpointsMatch(it, actual.new) }
                if (forcedPair) forced++ else if (!matched[index]) perKind.getOrPut(actual.kind.name) { PairCounts() }.fp++
            }
            for (expected in label.changes) {
                total++
                val actual = result.actualNodes.firstOrNull { (endpoint, _) -> endpointsMatch(expected.subject, endpoint) }?.second
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
        )
    }

    /** The minimum case matrix (R8, R9): every category has [minimum] cases for each language that has it. */
    fun matrixFailures(labels: List<Label>, languages: List<String>, categories: List<String>, minimum: Int = 3): List<String> = buildList {
        for (language in languages) {
            for (category in categories) {
                val count = labels.count { it.language == language && it.category == category }
                if (count < minimum) add("$language $category: $count cases, need $minimum")
            }
        }
    }

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

    fun run(corpus: File, output: File = File("build/reports/smartdiff-relationships")): Report {
        val engine = DeclarationEngine(null)
        val entries = parseCorpus(corpus)
        val cases = mutableListOf<CaseResult>()
        for (entry in entries) {
            val directory = entry.path ?: continue
            val label = parseLabel(File(directory, "label.yaml"))
            val extension = when (label.language.uppercase()) {
                "JAVA" -> "java"
                "KOTLIN" -> "kt"
                else -> "scala"
            }
            val old = File(directory, "old.$extension").readText().replace("\r\n", "\n")
            val new = File(directory, "new.$extension").readText().replace("\r\n", "\n")
            val started = System.nanoTime()
            val result = try {
                onPooledThread {
                    compareSafely(engine, ComparisonRequest(old, new, label.language, "${label.case}.$extension"), Cancellation.NONE)
                }
            } catch (e: Exception) {
                cases += CaseResult(label, ComparisonResult(engine.id, engine.version, Status.FAILED, fallbackReason = e.message), emptyList(), emptyList(), 0, false, e.message)
                continue
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            val hidden = (result.status == Status.ANALYZED || result.status == Status.PARTIAL) && NothingHidden.violation(old, new, result) != null
            cases += CaseResult(label, result, actualRelationships(result), actualNodes(result), millis, hidden)
        }
        val byLanguage = cases.groupBy { it.label.corpus }.mapValues { evaluate(it.value.map { row -> row.label }, it.value) }
        val bySplit = cases.groupBy { it.label.split }.mapValues { evaluate(it.value.map { row -> row.label }, it.value) }
        val overall = evaluate(cases.map { it.label }, cases)
        val gateMode = System.getProperty(GATE_PROPERTY)?.takeIf { it.isNotBlank() } ?: DEFAULT_GATE
        val tolerance = System.getProperty(TOLERANCE_PROPERTY)?.toDoubleOrNull() ?: 0.0
        val baseline = System.getProperty(BASELINE_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
        val failures = gateFailures(gateMode, overall, byLanguage, baseline, tolerance, cases)
        val report = Report(cases, byLanguage, bySplit, overall, gateMode, failures, revision(corpus), output, entries.count { it.path == null })
        write(report)
        return report
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
    ): List<String> = buildList {
        if (cases.any { it.hidden }) add("nothing-hidden violation")
        if (cases.any { it.millis > TIME_LIMIT_MILLIS }) add("file over the ${TIME_LIMIT_MILLIS} ms limit")
        if (cases.any { it.exception != null }) add("case threw")
        if (baselineFile != null && !baselineFile.isFile) {
            add("baseline not found: ${baselineFile.path}")
            return@buildList
        }
        val baseline = baselineFile?.let { readBaseline(it) } ?: return@buildList
        when (mode) {
            "no-new-wrong-pairs" -> {
                val currentWrong = wrongPairs(cases) + forcedPairs(cases)
                val new = currentWrong - baseline.wrongPairs
                if (new.isNotEmpty()) add("new wrong or forced pairs: ${new.joinToString(", ")}")
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
                    if (before - counts.precision > tolerance / 100.0) add("precision fell for $key")
                }
                for ((key, counts) in gateCounts(byLanguage)) {
                    val before = baseline.recall[key] ?: continue
                    if (before - counts.recall > tolerance / 100.0) add("recall fell for $key")
                }
            }
            else -> add("unknown gate mode: $mode")
        }
    }

    data class Baseline(val wrongPairs: Set<String>, val precision: Map<String, Double>, val recall: Map<String, Double>)

    private fun readBaseline(file: File): Baseline {
        @Suppress("UNCHECKED_CAST")
        val root = GsonBuilder().create().fromJson(file.readText(), Map::class.java) as Map<String, Any?>
        val wrong = (root["wrongPairs"] as? List<*>)?.map { it.toString() }?.toSet().orEmpty()
        fun metrics(key: String) = (root[key] as? Map<*, *>)?.entries?.associate { it.key.toString() to (it.value as Number).toDouble() }.orEmpty()
        return Baseline(wrong, metrics("precision"), metrics("recall"))
    }

    fun wrongPairs(cases: List<CaseResult>): Set<String> = buildSet {
        for (case in cases) {
            val label = case.label
            val matched = BooleanArray(case.actualRelationships.size)
            for (expected in label.relationships) {
                val index = case.actualRelationships.indexOfFirst { actual ->
                    actual.kind in expected.kinds && endpointsMatch(expected.old, actual.old) && endpointsMatch(expected.new, actual.new)
                }
                if (index >= 0) matched[index] = true
            }
            case.actualRelationships.forEachIndexed { index, actual ->
                val forced = label.ambiguous.any { endpointsMatch(it, actual.old) || endpointsMatch(it, actual.new) }
                if (!matched[index] && !forced) add("${label.case}|${actual.kind}|${actual.old.path}|${actual.old.name}|${actual.new.path}|${actual.new.name}")
            }
        }
    }

    fun forcedPairs(cases: List<CaseResult>): Set<String> = buildSet {
        for (case in cases) {
            for (actual in case.actualRelationships) {
                if (case.label.ambiguous.any { endpointsMatch(it, actual.old) || endpointsMatch(it, actual.new) }) {
                    add("${case.label.case}|forced|${actual.old.name}|${actual.new.name}")
                }
            }
        }
    }

    fun missedRelationships(cases: List<CaseResult>): List<String> = buildList {
        for (case in cases) {
            for (expected in case.label.relationships) {
                val found = case.actualRelationships.any { actual ->
                    actual.kind in expected.kinds && endpointsMatch(expected.old, actual.old) && endpointsMatch(expected.new, actual.new)
                }
                if (!found) add("${case.label.case}|missed|${expected.kinds.first()}|${expected.old.name}|${expected.new.name}")
            }
        }
    }

    fun classificationMismatches(cases: List<CaseResult>): List<String> = buildList {
        for (case in cases) {
            for (expected in case.label.changes) {
                val actual = case.actualNodes.firstOrNull { (endpoint, _) -> endpointsMatch(expected.subject, endpoint) }?.second
                if (actual == null || actual.presence != expected.presence || actual.content != expected.content) {
                    add("${case.label.case}|${expected.subject.name}|expected ${expected.presence}/${expected.content}, actual ${actual?.presence ?: "missing"}/${actual?.content ?: "-"}")
                }
            }
        }
    }

    fun write(report: Report) {
        report.reportDirectory.mkdirs()
        File(report.reportDirectory, "summary.tsv").writeText(buildString {
            appendLine("language\tkind\ttp\tfp\tfn\tprecision\trecall\twrong-share")
            for ((language, metrics) in report.metricsByLanguage) {
                for ((kind, counts) in metrics.perKind) {
                    appendLine(listOf(language, kind, counts.tp, counts.fp, counts.fn, format(counts.precision), format(counts.recall), format(counts.wrongShare)).joinToString("\t"))
                }
            }
        })
        File(report.reportDirectory, "report.md").writeText(buildString {
            appendLine("# SMART Diff relationship benchmark")
            appendLine()
            appendLine("Engine: ${DeclarationEngine.ID} ${DeclarationEngine.VERSION}; IDE: ${ApplicationInfo.getInstance().build.asStringWithoutProductCode()}")
            appendLine("Scala plugin: ${scalaVersion()}; corpus revision: ${report.corpusRevision}; gate: ${report.gateMode}")
            appendLine("Skipped real-commit lines without labels: ${report.skippedCommitLines}")
            appendLine()
            appendLine("Cases: ${report.cases.size}; hidden: ${report.overall.hidden}; fallback: ${report.overall.fallback}; forced pairs: ${report.overall.forcedPairs}")
            appendLine()
            for ((split, metrics) in report.metricsBySplit) {
                appendLine("## ${split.name.lowercase()}")
                appendLine("Precision ${format(metrics.precision)}, recall ${format(metrics.recall)}, classification ${format(metrics.classificationAccuracy)}, median ${metrics.medianMillis} ms, p95 ${metrics.p95Millis} ms, slowest ${metrics.slowestMillis} ms")
                appendLine()
            }
            appendLine("## Per language")
            for ((language, metrics) in report.metricsByLanguage) {
                appendLine("- $language: precision ${format(metrics.precision)}, recall ${format(metrics.recall)}, wrong share ${format(metrics.wrongShare)}, median ${metrics.medianMillis} ms, p95 ${metrics.p95Millis} ms, slowest ${metrics.slowestMillis} ms")
            }
            appendLine()
            appendLine("## Wrong and missed relationships")
            val wrong = (wrongPairs(report.cases) + forcedPairs(report.cases)).sorted()
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
            appendLine("## Gate")
            if (report.gateFailures.isEmpty()) appendLine("Passed") else report.gateFailures.forEach { appendLine("- $it") }
        })
        val precision = gateCounts(report.metricsByLanguage).mapValues { it.value.precision }
        val recall = gateCounts(report.metricsByLanguage).mapValues { it.value.recall }
        val json = mapOf(
            "engine" to mapOf("id" to DeclarationEngine.ID.toString(), "version" to DeclarationEngine.VERSION),
            "ide" to ApplicationInfo.getInstance().build.asStringWithoutProductCode(),
            "scalaPlugin" to scalaVersion(),
            "wrongPairs" to (wrongPairs(report.cases) + forcedPairs(report.cases)).toList(),
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
        )
        File(report.reportDirectory, "report.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(json))
        println("RELATIONSHIPS ${report.cases.size} cases, precision ${format(report.overall.precision)}, recall ${format(report.overall.recall)}; report in ${report.reportDirectory.absolutePath}")
    }

    private fun metricsJson(metrics: Metrics): Map<String, Any> = mapOf(
        "precision" to metrics.precision,
        "recall" to metrics.recall,
        "wrongShare" to metrics.wrongShare,
        "forcedPairs" to metrics.forcedPairs,
        "classificationAccuracy" to metrics.classificationAccuracy,
        "hidden" to metrics.hidden,
        "fallback" to metrics.fallback,
        "medianMillis" to metrics.medianMillis,
        "p95Millis" to metrics.p95Millis,
        "slowestMillis" to metrics.slowestMillis,
        "perKind" to metrics.perKind.mapValues { mapOf("tp" to it.value.tp, "fp" to it.value.fp, "fn" to it.value.fn, "precision" to it.value.precision, "recall" to it.value.recall) },
    )

    private fun revision(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(file.readText().toByteArray())
        val root = file.parentFile
        file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val directory = if (File(line).isAbsolute) File(line) else File(root, line)
            if (directory.isDirectory) directory.listFiles().orEmpty().sortedBy { it.name }.forEach { digest.update(it.readBytes()) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun scalaVersion(): String = try {
        com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("org.intellij.scala"))?.version ?: "absent"
    } catch (e: Exception) {
        "unknown"
    }

    private fun format(value: Double): String = "%.3f".format(value)

    private fun <T> onPooledThread(task: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread(Callable { task() }).get(10, TimeUnit.MINUTES)
}
