@file:Suppress("DEPRECATION")

package io.github.agadek.smartdiff.engine.api

/** The deprecation message of the [ChangeNode] fields that are now computed from a [Relationship] (relationships/02 R7). */
internal const val RELATIONSHIP_FIELD =
    "Read ComparisonResult.relationships instead: this field is computed from a Relationship (relationships/02 R7, R8)."

/** The engine-neutral outcome of one comparison (technical specification §5). */
data class ComparisonResult(
    val engineId: EngineId,
    val engineVersion: String,
    val status: Status,
    /** Source outside all subjects; null if unchanged. */
    val fileRegion: ChangeNode? = null,
    val changes: List<ChangeNode> = emptyList(),
    /** Source the engine could not classify. */
    val unanalyzed: List<RangePair> = emptyList(),
    /** Why the outline falls back to the native diff, or why some source was not analyzed. */
    val fallbackReason: String? = null,
    /**
     * Whether the engine threw, rather than reporting one of the specified fallbacks (rule 4 in [ComparisonEngine]).
     * Only [threw] sets it, so a reason that happens to start with the same words is not counted.
     */
    val engineThrew: Boolean = false,
    /**
     * The declarations this comparison linked, in a fixed order (relationships/02 R1, R19). Empty by default and for an
     * engine that does not detect relationships (R22). The [ChangeNode] rename, move, kind change and extraction fields
     * are computed from this list (R7, R8).
     */
    val relationships: List<Relationship> = emptyList(),
    /** A note the outline appends to the summary line, such as `structure view` for the fallback provider. */
    val analysisNote: String? = null,
) {
    /** Whether any change, the file region or an unanalyzed range would show in the outline. */
    val hasReportedChanges: Boolean
        get() = fileRegion?.isReported == true || changes.any { it.isReported } || unanalyzed.isNotEmpty()

    companion object {
        /** Prefix of the reason a comparison carries when it exceeded its time limit (§9.2 step 5). */
        const val TIMEOUT_REASON = "timed out"

        /** A failed result for a comparison over its time limit, with the reason the outline and Changes tree show. */
        fun timedOut(engine: ComparisonEngine, timeoutMillis: Long): ComparisonResult =
            failed(engine, "$TIMEOUT_REASON after $timeoutMillis ms")

        /**
         * A failed result for a comparison whose engine threw; [detail] is the exception's message or error. The reason
         * keeps the engine id, and [engineThrew] marks it for the readiness check (merge/06 R32).
         */
        fun threw(engine: ComparisonEngine, detail: String): ComparisonResult =
            failed(engine, "engine ${engine.id} failed: $detail").copy(engineThrew = true)

        fun failed(engine: ComparisonEngine, reason: String): ComparisonResult =
            ComparisonResult(engine.id, engine.version, Status.FAILED, fallbackReason = reason)

        fun tooLarge(engine: ComparisonEngine, reason: String): ComparisonResult =
            ComparisonResult(engine.id, engine.version, Status.TOO_LARGE, fallbackReason = reason)

        fun unsupported(engine: ComparisonEngine, reason: String): ComparisonResult =
            ComparisonResult(engine.id, engine.version, Status.UNSUPPORTED, fallbackReason = reason)
    }
}

enum class Status {
    /** Everything was analyzed. */
    ANALYZED,

    /** Some source is unanalyzed; the rest was analyzed. */
    PARTIAL,

    /** The engine does not handle this input; native diff only. */
    UNSUPPORTED,

    /** A limit was exceeded; native diff only. */
    TOO_LARGE,

    /** The engine failed or timed out; native diff only. */
    FAILED,
}

/** One changed (or, for navigation, unchanged) subject and its nested subjects. */
data class ChangeNode(
    val subject: Subject,
    /** Null when the subject is absent on the old side. */
    val oldRange: TextRange?,
    /** Null when the subject is absent on the new side. */
    val newRange: TextRange?,
    val presence: Presence,
    /** Only for [Presence.MATCHED]. */
    val moved: Boolean = false,
    /** Only for [Presence.MATCHED]. */
    val signatureChanged: Boolean = false,
    val content: Content = Content.UNCHANGED,
    /** Only for [Presence.UNCERTAIN]: the subjects on the other side it may correspond to. */
    val candidates: List<Candidate> = emptyList(),
    /** Optional fine-grained changed spans; empty if the engine has none. */
    val details: List<RangePair> = emptyList(),
    val children: List<ChangeNode> = emptyList(),
    /**
     * Only for [Presence.MATCHED]: the subject on the old side when the engine paired two subjects with different names
     * because their content is similar. Such a rename candidate may be wrong, so the outline always shows it.
     *
     * Computed from a [Relationship] of kind [RelationshipKind.RENAME] (relationships/02 R7, R8).
     */
    @get:Deprecated(RELATIONSHIP_FIELD, ReplaceWith("relationships"))
    val renamedFrom: Subject? = null,
    /**
     * Only for [Presence.MATCHED]: the kind on the old side when the declaration changed kind, such as a trait turned
     * into a class. Such a node always has [signatureChanged] set.
     *
     * Computed from a [Relationship] of kind [RelationshipKind.KIND_CHANGE] (relationships/02 R7, R8).
     */
    @get:Deprecated(RELATIONSHIP_FIELD, ReplaceWith("relationships"))
    val kindChangedFrom: DeclarationKind? = null,
    /**
     * Only for [Presence.ADDED]: the old-side code this subject was extracted from, most of its body first. A link adds
     * information and is never a match (ADR 0003).
     *
     * Computed from a [Relationship] of kind [RelationshipKind.EXTRACT] (relationships/02 R7, R8).
     */
    @get:Deprecated(RELATIONSHIP_FIELD, ReplaceWith("relationships"))
    val extractedFrom: List<Origin> = emptyList(),
    /** Only for [Presence.MATCHED]: the added subjects that code removed from this one was extracted to. */
    @get:Deprecated(RELATIONSHIP_FIELD, ReplaceWith("relationships"))
    val extractedTo: List<Subject> = emptyList(),
    /**
     * Only for [Presence.REMOVED]: the changed declarations that this declaration's code was inlined into, most of its
     * body first. A link adds information and is never a match (relationships/05 R6, R9).
     *
     * Computed from a [Relationship] of kind [RelationshipKind.INLINE] (relationships/02 R7, R8, R35).
     */
    @get:Deprecated(RELATIONSHIP_FIELD, ReplaceWith("relationships"))
    val inlinedInto: List<Inlined> = emptyList(),
    /**
     * Only for [Presence.MATCHED]: the removed declarations whose code this subject took, most of the body first
     * (relationships/05 R7, R10).
     *
     * Computed from a [Relationship] of kind [RelationshipKind.INLINE] (relationships/02 R7, R8, R36).
     */
    @get:Deprecated(RELATIONSHIP_FIELD, ReplaceWith("relationships"))
    val inlinedFrom: List<Inlined> = emptyList(),
) {
    /** Whether this node is a change by itself, not only because of its children. */
    val isChangedItself: Boolean
        get() = presence != Presence.MATCHED || moved || signatureChanged || renamedFrom != null || content != Content.UNCHANGED

    /** Whether the outline shows this node: it is a change itself, or a child is reported. */
    val isReported: Boolean
        get() = isChangedItself || children.any { it.isReported }
}

enum class Presence { MATCHED, ADDED, REMOVED, UNCERTAIN }

enum class Content { UNCHANGED, FORMATTING_ONLY, CHANGED, UNANALYZED }

/** What a change node is about. */
sealed interface Subject {
    /**
     * [signature] is the parameter list as the user reads it, null for declarations without one; [receiver] is the
     * receiver type of an extension, shown before the name.
     */
    data class Declaration(val kind: DeclarationKind, val name: String, val signature: String?, val receiver: String? = null) : Subject

    /** For engines without declarations, such as a line or token engine. */
    data class Region(val description: String) : Subject
}

/** Shared declaration labels, so every engine names declarations the same way. */
enum class DeclarationKind {
    CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE, OBJECT, CONSTRUCTOR, METHOD, FUNCTION, FIELD, PROPERTY,
    ENUM_CONSTANT, TYPE_ALIAS, INITIALIZER, TRAIT, GIVEN, EXTENSION,
    KEY,
    TAG,
    SECTION,
    ITEM,
    VARIABLE,
}

/** A possible counterpart of an uncertain node; [range] is on the side where the node is absent. */
data class Candidate(val subject: Subject, val range: TextRange)

/** Code on the old side that an added subject was extracted from. */
data class Origin(
    val subject: Subject,
    /** The old-side range of the source declaration, which identifies its node; a subject is not unique in a file. */
    val declarationRange: TextRange,
    /** The old-side range of the extracted code, inside [declarationRange]. */
    val range: TextRange,
)

/**
 * One end of an inlining as a change node shows it: the other declaration, and the inlined code's ranges on both
 * sides. [declarationRange] identifies the other declaration's node; a subject is not unique in a file.
 */
data class Inlined(
    val subject: Subject,
    val declarationRange: TextRange,
    /** The inlined code's range on the old side, inside the removed declaration. */
    val oldRange: TextRange,
    /** The inlined code's range on the new side, inside the target. */
    val newRange: TextRange,
)

/** A span on the old side, the new side, or both; null where it has no extent on that side. */
data class RangePair(val old: TextRange?, val new: TextRange?) {
    init {
        require(old != null || new != null) { "a range pair needs at least one side" }
    }
}

/** Offsets `[start, end)` into one side's text. */
data class TextRange(val start: Int, val end: Int) {
    init {
        require(start in 0..end) { "invalid range [$start, $end)" }
    }

    val length: Int get() = end - start

    operator fun contains(offset: Int): Boolean = offset in start until end

    override fun toString(): String = "[$start, $end)"
}
