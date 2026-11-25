package io.github.agadek.smartdiff.ui

import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.EquivalenceRule
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.RenameNameMapping
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import java.util.IdentityHashMap

/** A change label shown after a node's title (technical specification §10). */
enum class Tag(val text: String) {
    ADDED("added"),
    REMOVED("removed"),
    UNCERTAIN("uncertain"),
    RENAME_CANDIDATE("rename candidate"),
    MOVED("moved"),
    CHANGED("changed"),
    FORMATTING_ONLY("formatting-only"),
    COMMENT_ONLY("comment-only"),
    REWRITE_ONLY("rewrite-only"),
    UNANALYZED("unanalyzed"),
    SIGNATURE_CHANGED("signature changed"),
    USAGES_RENAMED("usages renamed"),
}

/**
 * A type of change the user can hide from the outline (technical specification §10). Uncertain, rename candidate and
 * unanalyzed changes have no type here: they are never hidden (ADR 0003).
 */
enum class ChangeType(val text: String, val tags: Set<Tag>) {
    CHANGED("Changed", setOf(Tag.CHANGED, Tag.SIGNATURE_CHANGED)),
    ADDED("Added", setOf(Tag.ADDED)),
    REMOVED("Removed", setOf(Tag.REMOVED)),
    MOVED("Moved", setOf(Tag.MOVED)),
    FORMATTING_ONLY("Formatting-only", setOf(Tag.FORMATTING_ONLY)),

    /** Offered only in the outline, and only while "Mark comment-only changes" is on (outline/11 R8, R9). */
    COMMENT_ONLY("Comment-only", setOf(Tag.COMMENT_ONLY)),

    /** Offered only in the outline, and only while "Mark rewrite-only changes" is on (declarations/08 R13, R14). */
    REWRITE_ONLY("Rewrite-only", setOf(Tag.REWRITE_ONLY)),
    ;

    companion object {
        /**
         * The change types of views without comment-only and rewrite-only changes: the Changes tree and the merge
         * outline (outline/11, declarations/08 out of scope).
         */
        val withoutCommentOnly: List<ChangeType> get() = entries.filter { it != COMMENT_ONLY && it != REWRITE_ONLY }

        /** The change types the outline's filter offers (outline/11 R8, R9). */
        fun forOutline(markCommentOnly: Boolean): List<ChangeType> = forOutline(markCommentOnly, markRewriteOnly = false)

        /** The change types the outline's filter offers (outline/11 R8, R9; declarations/08 R13, R14). */
        fun forOutline(markCommentOnly: Boolean, markRewriteOnly: Boolean): List<ChangeType> =
            entries.filter { (it != COMMENT_ONLY || markCommentOnly) && (it != REWRITE_ONLY || markRewriteOnly) }
    }
}

enum class ItemKind {
    /** A [ChangeNode] of the result, a change or a container of changes. */
    CHANGE,
    FILE_REGION,

    /** A possible counterpart of an uncertain node. */
    CANDIDATE,

    /** One of the result's unanalyzed ranges. */
    UNANALYZED_RANGE,
    FORMATTING_GROUP,
    UNCERTAIN_GROUP,
    UNANALYZED_GROUP,

    /** A headed list in the refactorings view, such as `Refactorings` or `Other changes` (relationships/06 R5, R16). */
    SECTION,

    /** One relationship row in the refactorings view (relationships/06 R3). */
    RELATIONSHIP,

    /** A piece of evidence under a relationship row (relationships/06 R9). */
    EVIDENCE,

    /** One grouped usage under a rename row (relationships/06 R4). */
    USAGE,

    /** Statements moved into a declaration, a row under its target declaration (relationships/08 R12). */
    CODE_MOVE,
}

/** One row of the outline tree. Ranges are offsets into the compared texts; a null range means "not present on that side". */
class OutlineItem(
    val kind: ItemKind,
    val title: String,
    val tags: List<Tag> = emptyList(),
    val oldRange: TextRange? = null,
    val newRange: TextRange? = null,
    val details: List<RangePair> = emptyList(),
    val children: List<OutlineItem> = emptyList(),
    /** Shown after the change labels, such as where extracted code came from. */
    val note: String? = null,
    /** A relationship row's confidence as a word, never a percentage (relationships/06 R7). */
    val confidence: Confidence? = null,
    /** Further endpoint ranges framed with the row's primary range (relationships/06 R10). */
    val frames: List<RangePair> = emptyList(),
    /** A rename's palette index, or null for rows without grouped usages (relationships/06 R12). */
    val colour: Int? = null,
    /** The change node this row is built from, when it is built from one (outline/12 details). */
    val node: ChangeNode? = null,
    /** The change nodes a relationship row frames through its endpoints (outline/12 R13). */
    val endpoints: List<ChangeNode> = emptyList(),
    /** Whether every markable node of this row and its children is reviewed (outline/12 R4, R5). */
    var reviewed: Boolean = false,
    /** Whether this row is a move, so it gets a marker in both editors (outline/13 R2). */
    val moved: Boolean = false,
) {
    val isGroup: Boolean
        get() = kind == ItemKind.FORMATTING_GROUP || kind == ItemKind.UNCERTAIN_GROUP ||
            kind == ItemKind.UNANALYZED_GROUP || kind == ItemKind.SECTION

    /** Whether this row is a declaration shown only as the container of its changed children, with no change label of its own. */
    val isContainerOnly: Boolean
        get() = kind == ItemKind.CHANGE && tags.isEmpty() && children.isNotEmpty()

    /**
     * Whether following the caret at [offset] on the old side ([old]) or the new side may select this row (outline/07
     * R11, R12). A row that is not a declaration containing other declarations always may. A container shown only for
     * its changed children never may. A matched container that did not move, with a change of its own, may only on a
     * line that its own change touches: a line of one of its changed runs or detail spans, or its first line when it was
     * renamed (a kind change is a signature change and has runs). So a caret on an unchanged line of the container, such
     * as a field of a class with a reformatted header, does not frame the whole container. A container that was added,
     * removed or moved, or whose own source was not compared by tokens, may anywhere in its range. Functions are never
     * treated as containers. [lineOf] maps an offset on that side to its line.
     */
    internal fun followsCaretAt(old: Boolean, offset: Int, lineOf: (Int) -> Int): Boolean {
        if (kind != ItemKind.CHANGE) return true
        val node = node
        // A function's nested declarations or code moves, such as a local class, do not make it a container: a caret on
        // any line of a changed function selects it (R1).
        if (node?.isCallable == true || (children.isEmpty() && (node == null || node.children.isEmpty()))) return true
        if (tags.isEmpty()) return false
        if (node == null || node.presence != Presence.MATCHED || node.moved) return true
        if (node.content == Content.CHANGED && !node.comparedByTokens) return true
        val line = lineOf(offset)
        val range = if (old) oldRange else newRange
        if (range != null && node.renamedFrom != null && lineOf(range.start) == line) return true
        return (node.changedRuns + details).any { pair ->
            val span = (if (old) pair.old else pair.new) ?: return@any false
            lineOf(span.start) <= line && line <= lineOf(maxOf(span.start, span.end - 1))
        }
    }

    private val ChangeNode.isCallable: Boolean
        get() = (subject as? Subject.Declaration)?.kind in CALLABLE_KINDS

    /** The row's text, which is also its accessible name: the title, the labels, the note, the confidence and its mark. */
    val label: String get() {
        val labels = tags.map { it.text } + listOfNotNull(note, confidence?.name?.lowercase()) +
            if (reviewed) listOf("reviewed") else emptyList()
        return if (labels.isEmpty()) title else "$title  ${labels.joinToString(", ")}"
    }

    /** A copy of this row with new children, which keeps every other field. */
    internal fun withChildren(children: List<OutlineItem>): OutlineItem =
        OutlineItem(kind, title, tags, oldRange, newRange, details, children, note, confidence, frames, colour, node, endpoints, reviewed, moved)

    /** A copy without the move marker, for a container tree row shown under the refactorings view's Other changes. */
    internal fun withoutMoved(): OutlineItem =
        OutlineItem(kind, title, tags, oldRange, newRange, details, children, note, confidence, frames, colour, node, endpoints, reviewed)

    /** A copy of this row without its own labels or details, as a container of the rows that remain. */
    internal fun asContainer(children: List<OutlineItem>): OutlineItem =
        OutlineItem(
            kind, title, emptyList(), oldRange, newRange, emptyList(), children,
            note = null, confidence = null, frames = emptyList(), node = node, endpoints = endpoints, reviewed = reviewed,
        )

    override fun toString(): String = label
}

/**
 * Turns a [ComparisonResult] into the rows of the outline (technical specification §10). It knows only the engine API,
 * so every engine's result is shown the same way.
 */
object OutlineModel {

    /**
     * The outline's top-level rows: the file region, the reported changes in the result's order, then the Uncertain and
     * Unanalyzed groups. Only reported nodes and their containers are shown, and a container shown only because of its
     * children has no change label. Formatting-only changes are collected in a group per container unless [formattingInline].
     * A change whose every label is of a [hidden] type is left out, or shown only as a container of its other changes.
     * [reviewed] nodes make their rows grey with a `reviewed` label; with [hideReviewed] they are hidden as a change type
     * is. [oldText] and [newText] are read only to name the lines of unanalyzed ranges and to know grouped usages.
     */
    fun build(
        result: ComparisonResult,
        oldText: CharSequence,
        newText: CharSequence,
        formattingInline: Boolean,
        hidden: Set<ChangeType> = emptySet(),
        whitespacePolicy: WhitespacePolicy = WhitespacePolicy.INSIGNIFICANT,
        markCommentOnly: Boolean = false,
        reviewed: Set<ChangeNode> = emptySet(),
        hideReviewed: Boolean = false,
        rewriteOnly: Set<EquivalenceRule> = emptySet(),
    ): List<OutlineItem> {
        val uncertain = mutableListOf<OutlineItem>()
        val usages = Usages(result, oldText, newText, whitespacePolicy)
        val reviewedSet = identitySetOf(reviewed)
        val markable = markableNodes(result, oldText, newText, whitespacePolicy)
        val reviewedState = Reviewed(reviewedSet, markable, emptyMap(), emptyMap())
        val builder = Builder(
            formattingInline, hidden, uncertain, Links(result), usages, markCommentOnly, reviewedState, hideReviewed, rewriteOnly,
            oldText, newText,
        )
        val rows = mutableListOf<OutlineItem>()
        result.fileRegion?.takeIf { it.isReported && !isHidden(it, hidden, markCommentOnly = markCommentOnly, hideReviewed = hideReviewed, reviewed = reviewedSet, rewriteOnly = rewriteOnly) }?.let { region ->
            rows += OutlineItem(
                ItemKind.FILE_REGION, "File region", tags(region, markCommentOnly = markCommentOnly, rewriteOnly = rewriteOnly), region.oldRange, region.newRange, region.details,
                node = region,
            ).also { it.reviewed = reviewedState.isReviewed(it) }
        }
        rows += builder.children(result.changes)
        if (uncertain.isNotEmpty()) {
            rows += OutlineItem(ItemKind.UNCERTAIN_GROUP, "Uncertain (${uncertain.size})", children = uncertain)
                .also { it.reviewed = reviewedState.isReviewed(it) }
        }
        if (result.unanalyzed.isNotEmpty()) {
            val ranges = result.unanalyzed.map { pair ->
                val sides = listOfNotNull(pair.old?.let { "old ${lines(it, oldText)}" }, pair.new?.let { "new ${lines(it, newText)}" })
                OutlineItem(ItemKind.UNANALYZED_RANGE, "Unanalyzed source, ${sides.joinToString(", ")}", oldRange = pair.old, newRange = pair.new)
            }
            rows += OutlineItem(ItemKind.UNANALYZED_GROUP, "Unanalyzed (${ranges.size})", children = ranges)
        }
        return rows
    }

    /**
     * The file summary line, such as `3 changed · 1 extracted · 1 added · 1 inlined · 1 removed · 1 moved`. An added
     * declaration extracted from old code counts as extracted instead of added; a removed declaration inlined into
     * another counts as inlined instead of removed. It counts every change, hidden or not, and ends with how many are
     * [hidden], such as `· 2 hidden`. When at least one change is [reviewed], it ends with `N of M reviewed`, after the
     * hidden count and before the analysis note (outline/12 R6).
     */
    fun summary(
        result: ComparisonResult,
        hidden: Set<ChangeType> = emptySet(),
        oldText: CharSequence = "",
        newText: CharSequence = "",
        whitespacePolicy: WhitespacePolicy = WhitespacePolicy.INSIGNIFICANT,
        markCommentOnly: Boolean = false,
        reviewed: Set<ChangeNode> = emptySet(),
        hideReviewed: Boolean = false,
        rewriteOnly: Set<EquivalenceRule> = emptySet(),
    ): String {
        val parts = summaryParts(result, hidden, oldText, newText, whitespacePolicy, markCommentOnly, reviewed, hideReviewed, rewriteOnly)
        return if (parts.isEmpty()) "No structural changes" else parts.joinToString(" · ")
    }

    /**
     * Whether [result] reports any structural change, counting exactly what [summary] shows. It decides on the result,
     * not on the summary's wording, so a caller can tell an analyzed file with no changes from one with changes.
     */
    fun hasChanges(
        result: ComparisonResult,
        hidden: Set<ChangeType> = emptySet(),
        oldText: CharSequence = "",
        newText: CharSequence = "",
        whitespacePolicy: WhitespacePolicy = WhitespacePolicy.INSIGNIFICANT,
    ): Boolean = summaryParts(result, hidden, oldText, newText, whitespacePolicy).isNotEmpty()

    /** The counted parts of [summary], empty when the result reports no structural change. */
    private fun summaryParts(
        result: ComparisonResult,
        hidden: Set<ChangeType>,
        oldText: CharSequence,
        newText: CharSequence,
        whitespacePolicy: WhitespacePolicy,
        markCommentOnly: Boolean = false,
        reviewed: Set<ChangeNode> = emptySet(),
        hideReviewed: Boolean = false,
        rewriteOnly: Set<EquivalenceRule> = emptySet(),
    ): List<String> {
        val usages = Usages(result, oldText, newText, whitespacePolicy)
        val counts = LinkedHashMap<String, Int>()
        listOf(Tag.CHANGED.text, EXTRACTED, Tag.ADDED.text, INLINED, Tag.REMOVED.text, Tag.MOVED.text, Tag.FORMATTING_ONLY.text, Tag.COMMENT_ONLY.text, Tag.REWRITE_ONLY.text,
            Tag.RENAME_CANDIDATE.text, Tag.USAGES_RENAMED.text, Tag.UNCERTAIN.text, Tag.UNANALYZED.text).forEach { counts[it] = 0 }
        fun count(node: ChangeNode) {
            if (node.isChangedItself || usages.hasUsages(node)) {
                tags(node, usages.isRenameOnly(node), markCommentOnly, rewriteOnly).map {
                    when {
                        it == Tag.ADDED && node.extractedFrom.isNotEmpty() -> EXTRACTED
                        it == Tag.REMOVED && node.inlinedInto.isNotEmpty() -> INLINED
                        else -> it.text
                    }
                }.filter { it in counts }.forEach { counts[it] = counts.getValue(it) + 1 }
            }
            node.children.forEach(::count)
        }
        result.fileRegion?.let(::count)
        result.changes.forEach(::count)
        counts[Tag.UNANALYZED.text] = counts.getValue(Tag.UNANALYZED.text) + result.unanalyzed.size
        val markable = markableNodes(result, oldText, newText, whitespacePolicy)
        val reviewedSet = identitySetOf(reviewed)
        val reviewedCount = markable.count { it in reviewedSet }
        val reviewedPart = if (reviewedCount > 0) listOf("$reviewedCount of ${markable.size} reviewed") else emptyList()
        return counts.filterValues { it > 0 }.map { (label, n) -> "$n $label" } +
            listOfNotNull(hiddenCount(result, hidden, markCommentOnly, reviewedSet, hideReviewed, rewriteOnly).takeIf { it > 0 }?.let { "$it hidden" }) +
            reviewedPart +
            if (result.status == Status.ANALYZED || result.status == Status.PARTIAL)
                listOfNotNull(result.analysisNote) else emptyList()
    }

    /** How many changes [build] leaves out, or shows only as containers, because their types are [hidden]. */
    fun hiddenCount(
        result: ComparisonResult,
        hidden: Set<ChangeType>,
        markCommentOnly: Boolean = false,
        reviewed: Set<ChangeNode> = emptySet(),
        hideReviewed: Boolean = false,
        rewriteOnly: Set<EquivalenceRule> = emptySet(),
    ): Int {
        if (hidden.isEmpty() && !hideReviewed) return 0
        val usages = Usages(result, "", "", WhitespacePolicy.INSIGNIFICANT)
        val reviewedSet = identitySetOf(reviewed)
        fun count(node: ChangeNode): Int =
            (if (node.isReported && isHidden(node, hidden, usages.hasUsages(node), markCommentOnly, hideReviewed, reviewedSet, rewriteOnly)) 1 else 0) +
                node.children.sumOf(::count)
        return (result.fileRegion?.let(::count) ?: 0) + result.changes.sumOf(::count)
    }

    /** The nodes a mark can be put on: every reported change, a node with grouped usages and the reported file region. */
    internal fun markableNodes(
        result: ComparisonResult,
        oldText: CharSequence,
        newText: CharSequence,
        whitespacePolicy: WhitespacePolicy,
    ): Set<ChangeNode> {
        val usages = Usages(result, oldText, newText, whitespacePolicy)
        val nodes = identitySetOf<ChangeNode>()
        fun visit(node: ChangeNode) {
            if (node.isChangedItself || usages.hasUsages(node)) nodes += node
            node.children.forEach(::visit)
        }
        result.fileRegion?.takeIf { it.isReported }?.let(nodes::add)
        result.changes.forEach(::visit)
        return nodes
    }

    /** The nodes whose innermost matched declaration contains a grouped usage, for the native diff's hidden lines (R8). */
    fun groupedUsageNodes(result: ComparisonResult): Set<ChangeNode> =
        Usages(result, "", "", WhitespacePolicy.INSIGNIFICANT).nodesWithUsages()

    /** A change itself whose every label is of a [hidden] type; an uncertain, rename candidate, grouped usage or unanalyzed change never is. With [hideReviewed], a [reviewed] change is hidden too. */
    fun isHidden(
        node: ChangeNode,
        hidden: Set<ChangeType>,
        groupedUsages: Boolean = false,
        markCommentOnly: Boolean = false,
        hideReviewed: Boolean = false,
        reviewed: Set<ChangeNode> = emptySet(),
        rewriteOnly: Set<EquivalenceRule> = emptySet(),
    ): Boolean {
        if (groupedUsages || !node.isChangedItself) return false
        if (hideReviewed && node in reviewed) return true
        if (hidden.isEmpty()) return false
        val hiddenTags = hidden.flatMapTo(HashSet()) { it.tags }
        return tags(node, markCommentOnly = markCommentOnly, rewriteOnly = rewriteOnly).all { it in hiddenTags }
    }

    /** A subject's title: kind, receiver, name and signature for a declaration (`method parse(String,int)`,
     * `function Date.formatIsoDate()`), or a region's description.
     */
    fun title(subject: Subject): String = when (subject) {
        is Subject.Declaration -> {
            val kind = kindName(subject.kind)
            val name = name(subject)
            val base = when {
                name.isEmpty() -> kind
                kind.isEmpty() -> name
                else -> "$kind $name"
            }
            base + (subject.signature?.let { "($it)" } ?: "")
        }
        is Subject.Region -> subject.description
    }

    /**
     * A node's title; a rename candidate shows the old name too (`method total → sum(int)`), and a declaration that
     * changed kind the old kind (`trait → class Shape`).
     */
    fun title(node: ChangeNode): String {
        val from = node.renamedFrom as? Subject.Declaration
        val subject = node.subject as? Subject.Declaration
        val kindFrom = node.kindChangedFrom?.let { kindName(it) + " → " }.orEmpty()
        if (from == null || subject == null) return kindFrom + title(node.subject)
        return kindFrom + title(subject.copy(name = "${name(from)} → ${name(subject)}", receiver = null))
    }

    internal fun kindName(kind: DeclarationKind): String =
        if (kind == DeclarationKind.ITEM) "" else kind.label()

    internal fun name(subject: Subject.Declaration): String = (subject.receiver?.let { "$it." } ?: "") + subject.name

    private const val EXTRACTED = "extracted"
    private const val INLINED = "inlined"

    /** Names after which a note says how many more there are. */
    private const val MAX_NAMES = 3

    /**
     * The extraction links of a result from both ends (extraction detection specification §3), with each node's
     * container, so a note can name the other end by its container when that differs.
     */
    private class Links(result: ComparisonResult) {
        private val parents = IdentityHashMap<ChangeNode, ChangeNode?>()

        /** Matched nodes by their old range, which an extraction origin names its source by. */
        private val sources = HashMap<TextRange, ChangeNode>()

        /** The added nodes extracted from each source, by the source's old range. */
        private val targets = HashMap<TextRange, MutableList<ChangeNode>>()

        /** Every node by its range on each side, which an inlining link names the other end by. */
        private val oldNodes = HashMap<TextRange, ChangeNode>()
        private val newNodes = HashMap<TextRange, ChangeNode>()

        init {
            fun visit(node: ChangeNode, parent: ChangeNode?) {
                parents[node] = parent
                node.oldRange?.let { oldNodes.putIfAbsent(it, node) }
                node.newRange?.let { newNodes.putIfAbsent(it, node) }
                if (node.presence == Presence.MATCHED && node.oldRange != null) sources.putIfAbsent(node.oldRange, node)
                node.extractedFrom.forEach { targets.getOrPut(it.declarationRange, ::mutableListOf) += node }
                node.children.forEach { visit(it, node) }
            }
            result.changes.forEach { visit(it, null) }
        }

        /** The notes of both link kinds: `extracted from …`, `code extracted to …`, `inlined into …` and the reverse. */
        fun note(node: ChangeNode): String? {
            val notes = mutableListOf<String>()
            if (node.extractedFrom.isNotEmpty()) {
                notes += "extracted from " + names(node.extractedFrom.map { origin ->
                    val source = sources[origin.declarationRange]
                    label(origin.subject, source?.let { parents[it] }, parents[node], qualify = source != null)
                })
            }
            if (node.extractedTo.isNotEmpty()) {
                val extracted = node.oldRange?.let { targets[it] }.orEmpty()
                notes += "code extracted to " + names(node.extractedTo.map { subject ->
                    val target = extracted.firstOrNull { it.subject == subject }
                    label(subject, target?.let { parents[it] }, parents[node], qualify = target != null)
                })
            }
            if (node.inlinedFrom.isNotEmpty()) {
                notes += "code inlined from " + names(node.inlinedFrom.map { inlined ->
                    val source = oldNodes[inlined.declarationRange]
                    label(inlined.subject, source?.let { parents[it] }, parents[node], qualify = source != null)
                })
            }
            if (node.inlinedInto.isNotEmpty()) {
                notes += "inlined into " + names(node.inlinedInto.map { inlined ->
                    val target = newNodes[inlined.declarationRange]
                    label(inlined.subject, target?.let { parents[it] }, parents[node], qualify = target != null)
                })
            }
            return notes.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }

        /** The name and signature of [subject], after its container's name when that is not [other]'s container. */
        private fun label(subject: Subject, container: ChangeNode?, other: ChangeNode?, qualify: Boolean): String {
            val declaration = subject as? Subject.Declaration ?: return title(subject)
            val own = if (declaration.name.isEmpty()) title(declaration) else name(declaration) + (declaration.signature?.let { "($it)" } ?: "")
            val outer = (container?.subject as? Subject.Declaration)?.takeIf { qualify && container !== other }
            return outer?.let { "${it.name}.$own" } ?: own
        }

        private fun names(names: List<String>): String =
            names.take(MAX_NAMES).joinToString(", ") + if (names.size > MAX_NAMES) ", +${names.size - MAX_NAMES} more" else ""
    }

    /** The extraction note for [node] in [result], or null when it has no extraction link. */
    fun note(result: ComparisonResult, node: ChangeNode): String? = Links(result).note(node)

    /**
     * The change labels of a node that is a change itself; none for a node shown only as a container. With
     * [markCommentOnly], a changed node whose only difference is in comments is `comment-only` instead of `changed`
     * (outline/11 R3, R6). [rewriteOnly] holds the enabled equivalence rules while "Mark rewrite-only changes" is on,
     * and is empty while it is off: a changed node whose needed rules are all enabled is `rewrite-only` instead of
     * `changed` (declarations/08 R4, R7, R8).
     */
    fun tags(
        node: ChangeNode,
        usagesRenamed: Boolean = false,
        markCommentOnly: Boolean = false,
        rewriteOnly: Set<EquivalenceRule> = emptySet(),
    ): List<Tag> {
        if (usagesRenamed) return listOf(Tag.USAGES_RENAMED)
        if (!node.isChangedItself) return emptyList()
        return buildList {
            when (node.presence) {
                Presence.ADDED -> add(Tag.ADDED)
                Presence.REMOVED -> add(Tag.REMOVED)
                Presence.UNCERTAIN -> add(Tag.UNCERTAIN)
                Presence.MATCHED -> {}
            }
            if (node.renamedFrom != null) add(Tag.RENAME_CANDIDATE)
            if (node.moved) add(Tag.MOVED)
            when (node.content) {
                Content.CHANGED -> add(
                    when {
                        markCommentOnly && node.commentOnly -> Tag.COMMENT_ONLY
                        node.rewriteRules.isNotEmpty() && rewriteOnly.containsAll(node.rewriteRules) -> Tag.REWRITE_ONLY
                        else -> Tag.CHANGED
                    },
                )
                Content.FORMATTING_ONLY -> add(Tag.FORMATTING_ONLY)
                Content.UNANALYZED -> add(Tag.UNANALYZED)
                Content.UNCHANGED -> {}
            }
            if (node.signatureChanged) add(Tag.SIGNATURE_CHANGED)
        }
    }

    /** The grouped usages of a result, by relationship and by the innermost matched declaration that contains them. */
    private class Usages(
        result: ComparisonResult,
        private val oldText: CharSequence,
        private val newText: CharSequence,
        private val policy: WhitespacePolicy,
    ) {
        private val byNode = IdentityHashMap<ChangeNode, MutableList<Evidence.Reference>>()
        private val byRelationship = IdentityHashMap<Relationship, List<Evidence.Reference>>()
        private val renameEndpoints = HashSet<TextRange>()
        private val nodes = mutableListOf<ChangeNode>()

        init {
            fun visit(node: ChangeNode) {
                if (node.presence == Presence.MATCHED && node.subject is Subject.Declaration && node.oldRange != null && node.newRange != null) {
                    nodes += node
                }
                node.children.forEach(::visit)
            }
            result.changes.forEach(::visit)
            for (relationship in result.relationships) {
                if (RelationshipKind.RENAME !in relationship.kinds) continue
                relationship.new.forEach { renameEndpoints += it.range }
                val references = relationship.evidence.filterIsInstance<Evidence.Reference>()
                if (references.isEmpty()) continue
                byRelationship[relationship] = references
                for (reference in references) {
                    val node = nodes
                        .filter { contains(it, reference) }
                        .minWithOrNull(compareBy({ it.oldRange!!.length }, { it.newRange!!.length }))
                        ?: continue
                    byNode.getOrPut(node, ::mutableListOf) += reference
                }
            }
        }

        fun references(node: ChangeNode): List<Evidence.Reference> = byNode[node].orEmpty()

        fun hasUsages(node: ChangeNode): Boolean = byNode.containsKey(node)

        fun nodesWithUsages(): Set<ChangeNode> =
            java.util.Collections.newSetFromMap(IdentityHashMap<ChangeNode, Boolean>()).apply { addAll(byNode.keys) }

        /** The rename relationship whose new endpoint is [node], if any. */
        fun renameOf(node: ChangeNode): Relationship? = byRelationship.keys.firstOrNull { relationship ->
            relationship.new.any { it.range == node.newRange }
        }

        fun renameReferences(node: ChangeNode): List<Evidence.Reference> = renameOf(node)?.let { byRelationship[it] }.orEmpty()

        /** A declaration whose only change is the grouped usages inside it (R10). */
        fun isRenameOnly(node: ChangeNode): Boolean {
            val references = byNode[node] ?: return false
            if (node.content == Content.UNANALYZED) return false
            if (node.newRange in renameEndpoints) return false
            if (node.moved || node.signatureChanged || node.renamedFrom != null || node.kindChangedFrom != null) return false
            val oldRange = node.oldRange ?: return false
            val newRange = node.newRange ?: return false
            return RenameNameMapping.isRenameOnly(oldText, newText, references, oldRange, newRange, policy)
        }

        private fun contains(node: ChangeNode, reference: Evidence.Reference): Boolean {
            val old = node.oldRange ?: return false
            val new = node.newRange ?: return false
            return reference.oldRange.start >= old.start && reference.oldRange.end <= old.end &&
                reference.newRange.start >= new.start && reference.newRange.end <= new.end
        }
    }

    private class Builder(
        private val formattingInline: Boolean,
        private val hidden: Set<ChangeType>,
        private val uncertain: MutableList<OutlineItem>,
        private val links: Links,
        private val usages: Usages,
        private val markCommentOnly: Boolean,
        private val reviewed: Reviewed,
        private val hideReviewed: Boolean,
        private val rewriteOnly: Set<EquivalenceRule>,
        private val oldText: CharSequence,
        private val newText: CharSequence,
    ) {

        /** Sets a row's reviewed flag from its own and its children's markable nodes (outline/12 R4, R5). */
        private fun <T : OutlineItem> marked(item: T): T = item.also { it.reviewed = reviewed.isReviewed(it) }

        /** The rows for [nodes], the children of one container; uncertain nodes go to [uncertain] instead. */
        fun children(nodes: List<ChangeNode>): List<OutlineItem> {
            val rows = mutableListOf<OutlineItem>()
            val formatting = mutableListOf<OutlineItem>()
            for (node in nodes) {
                if (!node.isReported && !usages.hasUsages(node)) continue
                if (node.presence == Presence.UNCERTAIN) {
                    uncertain += uncertainItem(node)
                    continue
                }
                val item = item(node) ?: continue
                if (!formattingInline && isFormattingOnly(node)) formatting += item else rows += item
            }
            if (formatting.isNotEmpty()) rows += marked(OutlineItem(ItemKind.FORMATTING_GROUP, "Formatting-only (${formatting.size})", children = formatting))
            return rows
        }

        /**
         * The row for a reported node, or null for a container whose only reported children were uncertain or hidden. A
         * hidden node with shown children is a container without change labels. A node with grouped usages is never
         * hidden and its row frames the usages inside it as well.
         */
        private fun item(node: ChangeNode): OutlineItem? {
            val children = children(node.children) + codeMoveRows(node)
            val grouped = usages.references(node)
            if (isHidden(node, hidden, grouped.isNotEmpty(), markCommentOnly, hideReviewed, reviewed.reviewed, rewriteOnly)) {
                if (children.isEmpty()) return null
                return marked(OutlineItem(ItemKind.CHANGE, title(node), emptyList(), node.oldRange, node.newRange, children = children, node = node))
            }
            if (!node.isChangedItself && children.isEmpty() && grouped.isEmpty()) return null
            // An extraction frames the code it came from on the old side, where it is otherwise absent; an inlining
            // frames the code it went into on the new side, where the removed declaration is absent.
            val oldRange = node.oldRange ?: node.extractedFrom.firstOrNull()?.range
            val newRange = node.newRange ?: node.inlinedInto.firstOrNull()?.newRange
            val details = node.details + node.inlinedInto.map { RangePair(null, it.newRange) } +
                node.inlinedFrom.map { RangePair(null, it.newRange) } +
                grouped.map { RangePair(it.oldRange, it.newRange) } +
                usages.renameReferences(node).map { RangePair(it.oldRange, it.newRange) }
            val usageNote = usages.renameOf(node)?.let { relationship ->
                val count = usages.renameReferences(node).size
                if (count == 0) null else if (count == 1) "1 usage" else "$count usages"
            }
            val tags = tags(node, usages.isRenameOnly(node), markCommentOnly, rewriteOnly)
            return marked(
                OutlineItem(
                    ItemKind.CHANGE, title(node), tags, oldRange, newRange,
                    details.distinct(), children,
                    listOfNotNull(usageNote, links.note(node), codeMovedToNote(node)).joinToString(", ").ifEmpty { null },
                    node = node,
                    moved = Tag.MOVED in tags,
                ),
            )
        }

        /**
         * The code moves into [node], after its child declarations, in new-side order (relationships/08 R12 to R15, R17).
         * A row's tags are `moved`, and `changed` when its statements differ beyond formatting; the change type filter
         * hides a row whose every tag it hides. A row names its target as its endpoint, so it follows the target's
         * reviewed mark, and it has no change node of its own: the summary line does not count it (R20).
         */
        private fun codeMoveRows(node: ChangeNode): List<OutlineItem> {
            if (node.codeMoves.isEmpty()) return emptyList()
            val hiddenTags = hidden.flatMapTo(HashSet()) { it.tags }
            return node.codeMoves.sortedBy { it.newRange.start }.mapNotNull { move ->
                val tags = if (move.content == Content.CHANGED) listOf(Tag.MOVED, Tag.CHANGED) else listOf(Tag.MOVED)
                if (hiddenTags.isNotEmpty() && tags.all { it in hiddenTags }) return@mapNotNull null
                marked(
                    OutlineItem(
                        ItemKind.CODE_MOVE,
                        codeMoveTitle(move.oldRange, move.newRange, oldText, newText),
                        tags,
                        move.oldRange,
                        move.newRange,
                        details = move.changedRuns,
                        note = if (move.sourceRange != node.oldRange) "from ${shortLabel(move.source)}" else null,
                        endpoints = listOf(node),
                        moved = true,
                    ),
                )
            }
        }

        /** The note of a declaration whose code moved to other declarations (relationships/08 R13). */
        private fun codeMovedToNote(node: ChangeNode): String? =
            node.codeMovedTo.takeIf { it.isNotEmpty() }?.let { targets ->
                "code moved to " + targets.map(::shortLabel).let { names ->
                    names.take(MAX_NAMES).joinToString(", ") + if (names.size > MAX_NAMES) ", +${names.size - MAX_NAMES} more" else ""
                }
            }

        private fun uncertainItem(node: ChangeNode): OutlineItem {
            val candidates = node.candidates.map { candidate ->
                // A candidate's range is on the side where the uncertain node is absent.
                val onOldSide = node.oldRange == null
                OutlineItem(
                    ItemKind.CANDIDATE,
                    "candidate: ${title(candidate.subject)}",
                    oldRange = candidate.range.takeIf { onOldSide },
                    newRange = candidate.range.takeUnless { onOldSide },
                )
            }
            return marked(
                OutlineItem(ItemKind.CHANGE, title(node), tags(node), node.oldRange, node.newRange, node.details, candidates + children(node.children), node = node),
            )
        }

        /** A matched node whose only change is formatting, with nothing reported inside it. */
        private fun isFormattingOnly(node: ChangeNode): Boolean =
            node.presence == Presence.MATCHED && !node.moved && !node.signatureChanged &&
                node.content == Content.FORMATTING_ONLY && node.children.none { it.isReported } && !usages.hasUsages(node)
    }

    /**
     * The title of a code move row (relationships/08 R12): `moved code lines 40–46 → 12–18`, the old side's lines first,
     * counted from 1, or `line 40` for a move of one line.
     */
    internal fun codeMoveTitle(oldRange: TextRange, newRange: TextRange, oldText: CharSequence, newText: CharSequence): String {
        fun span(range: TextRange, text: CharSequence): Pair<Int, Int> {
            fun line(offset: Int): Int {
                var line = 1
                for (i in 0 until minOf(offset, text.length)) if (text[i] == '\n') line++
                return line
            }
            val first = line(range.start)
            return first to if (range.length == 0) first else line(range.end - 1)
        }
        val (oldFirst, oldLast) = span(oldRange, oldText)
        val (newFirst, newLast) = span(newRange, newText)
        fun lines(first: Int, last: Int) = if (first == last) "$first" else "$first–$last"
        val word = if (oldFirst == oldLast && newFirst == newLast) "line" else "lines"
        return "moved code $word ${lines(oldFirst, oldLast)} → ${lines(newFirst, newLast)}"
    }

    /** A declaration's name and signature as a note names it, such as `process(Order)`, or its title without a name. */
    internal fun shortLabel(subject: Subject): String {
        val declaration = subject as? Subject.Declaration ?: return title(subject)
        return if (declaration.name.isEmpty()) title(declaration) else name(declaration) + (declaration.signature?.let { "($it)" } ?: "")
    }

    /** 1-based lines of [range]: `line 3` or `lines 3-5`. */
    internal fun lines(range: TextRange, text: CharSequence): String {
        fun line(offset: Int): Int {
            var line = 1
            for (i in 0 until minOf(offset, text.length)) if (text[i] == '\n') line++
            return line
        }
        val first = line(range.start)
        val last = if (range.length == 0) first else line(range.end - 1)
        return if (first == last) "line $first" else "lines $first-$last"
    }
}

/** Declarations whose nested declarations are part of their body, so they are never followed as containers (outline/07 R12). */
private val CALLABLE_KINDS = setOf(
    DeclarationKind.METHOD, DeclarationKind.FUNCTION, DeclarationKind.CONSTRUCTOR, DeclarationKind.INITIALIZER,
    DeclarationKind.DESTRUCTOR, DeclarationKind.OPERATOR, DeclarationKind.SUBSCRIPT, DeclarationKind.INDEXER,
)
