package io.github.agadek.smartdiff.engine.api

import java.util.EnumSet

/**
 * One old-side declaration linked to one new-side declaration, or to several on the other side, with its [kinds], its
 * [confidence] and the [evidence] that supports it (relationships/02 R1 to R6).
 *
 * A relationship adds information: it is never a [Presence] and never replaces `MATCHED`, `ADDED`, `REMOVED` or
 * `UNCERTAIN`. [kinds] is built as an [EnumSet], so it iterates in [RelationshipKind] order (R19); [old] and [new] are
 * sorted by range start.
 */
data class Relationship(
    /** The kinds this link carries, in [RelationshipKind] order; never empty. */
    val kinds: Set<RelationshipKind>,
    /** The old-side endpoints, at least one, sorted by range start. */
    val old: List<RelationshipEndpoint>,
    /** The new-side endpoints, at least one, sorted by range start. */
    val new: List<RelationshipEndpoint>,
    val confidence: Confidence,
    /** The support for this link, in [EvidenceOrder] order (R19). */
    val evidence: List<Evidence>,
) {
    init {
        require(kinds.isNotEmpty()) { "a relationship needs at least one kind" }
        require(old.isNotEmpty() && new.isNotEmpty()) { "a relationship needs an endpoint on each side: $this" }
        require(old == old.sortedBy { it.range.start } && new == new.sortedBy { it.range.start }) {
            "relationship endpoints must be sorted by range start: $this"
        }
        require(kinds.toList() == kinds.sortedBy { it.ordinal }) { "relationship kinds must be in enum order: $this" }
        require(evidence.toList() == evidence.sortedWith(EvidenceOrder)) {
            "relationship evidence must be in type order: $this"
        }
        require(RelationshipKind.RENAME in kinds || evidence.none { it is Evidence.Reference }) {
            "reference evidence is allowed only on a RENAME relationship: $this"
        }
    }
}

/** What a [Relationship] says changed; a link can carry several kinds. */
enum class RelationshipKind {
    /** The two declarations have different names and similar content. */
    RENAME,

    /** The declaration is in another container, or in another place among its siblings. */
    MOVE,

    /** The declaration changed kind within its family, such as a trait turned into a class. */
    KIND_CHANGE,

    /** The new declaration holds code that was removed from the old one. */
    EXTRACT,

    /** The removed declaration's code now lies inside a new one. */
    INLINE,
}

/** The ordinal strength of a relationship's evidence (relationships/02 R4). It is never shown as a percentage. */
enum class Confidence { HIGH, MEDIUM, LOW }

/**
 * One end of a [Relationship]. [range] is the declaration's whole range on its side, which identifies its node; a
 * [subject] is not unique in a file.
 */
data class RelationshipEndpoint(val subject: Subject, val range: TextRange)

/** One piece of support for a relationship, in the order of the [EvidenceOrder] ranking (R19). */
sealed interface Evidence {
    /** The old and new names of a rename. */
    data class Name(val old: String, val new: String) : Evidence

    /**
     * [shared] of the [total] tokens were tiled between the two sides: an extraction's target body from the extracted
     * code, or an inlining's removed body from the target's added code. [oldRange] and [newRange] are the tiled code's
     * ranges on each side.
     */
    data class Content(val shared: Int, val total: Int, val oldRange: TextRange, val newRange: TextRange) : Evidence

    /** The container path, and for a kind change the kind, on each side. */
    data class Structure(
        val oldPath: String,
        val newPath: String,
        val oldKind: DeclarationKind? = null,
        val newKind: DeclarationKind? = null,
    ) : Evidence

    /** The index among its siblings on each side. */
    data class Position(val oldIndex: Int, val newIndex: Int) : Evidence

    /**
     * One usage that changed only to follow the rename (relationships/04 R12): [symbol] is the new-side declaration the
     * usage resolves to, and the two ranges are the usage on each side. It never adds an endpoint.
     *
     * Only a [RelationshipKind.RENAME] relationship carries it, after [Position] and before [Score], and several are
     * ordered by old range start, then new range start.
     */
    data class Reference(val symbol: Subject.Declaration, val oldRange: TextRange, val newRange: TextRange) : Evidence

    /**
     * The engine's numeric score, kept for debugging and the benchmark (R6). It is never shown as a percentage, and a
     * [Confidence] is what the API exposes.
     */
    data class Score(val value: Double) : Evidence
}

/** The fixed order of evidence types that a [Relationship] follows (R19). */
internal object EvidenceOrder : Comparator<Evidence> {
    fun of(evidence: Evidence): Int = when (evidence) {
        is Evidence.Name -> 0
        is Evidence.Content -> 1
        is Evidence.Structure -> 2
        is Evidence.Position -> 3
        is Evidence.Reference -> 4
        is Evidence.Score -> 5
    }

    override fun compare(first: Evidence, second: Evidence): Int {
        val byType = of(first).compareTo(of(second))
        if (byType != 0) return byType
        if (first is Evidence.Reference && second is Evidence.Reference) {
            return compareValuesBy(first, second, { it.oldRange.start }, { it.newRange.start })
        }
        return 0
    }
}

/**
 * The score thresholds that turn a numeric score into a [Confidence] (R14, R25, R26, R30): [Confidence.HIGH] at
 * [high] or above, [Confidence.MEDIUM] at [medium] or above, and [Confidence.LOW] below.
 *
 * The request carries a policy, as it carries its [Limits], so tests and the benchmark set one directly; a registry key
 * sets it in a running IDE (R15).
 */
data class ConfidencePolicy(val high: Double = 0.82, val medium: Double = 0.7) {
    init {
        require(medium in 0.0..1.0 && high in medium..1.0) { "invalid confidence policy: $this" }
    }

    /** The confidence of a relationship whose score is [score]. */
    fun classify(score: Double): Confidence = when {
        score >= high -> Confidence.HIGH
        score >= medium -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    /** The policy as the registry key holds it, `high,medium`. */
    fun format(): String = "$high,$medium"

    companion object {
        val DEFAULT = ConfidencePolicy()

        /** A policy from `high,medium`; null for an empty or malformed value, so the caller keeps [DEFAULT] (R30). */
        fun parse(value: String?): ConfidencePolicy? {
            val parts = value?.trim()?.takeIf { it.isNotEmpty() }?.split(',') ?: return null
            if (parts.size != 2) return null
            val high = parts[0].trim().toDoubleOrNull() ?: return null
            val medium = parts[1].trim().toDoubleOrNull() ?: return null
            return try {
                ConfidencePolicy(high, medium)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
