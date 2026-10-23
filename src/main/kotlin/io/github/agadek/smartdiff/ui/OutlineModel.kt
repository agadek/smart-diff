package io.github.agadek.smartdiff.ui

import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
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
    UNANALYZED("unanalyzed"),
    SIGNATURE_CHANGED("signature changed"),
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
) {
    val isGroup: Boolean get() = kind == ItemKind.FORMATTING_GROUP || kind == ItemKind.UNCERTAIN_GROUP || kind == ItemKind.UNANALYZED_GROUP

    /** The row's text, which is also its accessible name: the title, then the change labels and the note. */
    val label: String get() {
        val labels = tags.map { it.text } + listOfNotNull(note)
        return if (labels.isEmpty()) title else "$title  ${labels.joinToString(", ")}"
    }

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
     * [oldText] and [newText] are read only to name the lines of unanalyzed ranges.
     */
    fun build(
        result: ComparisonResult,
        oldText: CharSequence,
        newText: CharSequence,
        formattingInline: Boolean,
        hidden: Set<ChangeType> = emptySet(),
    ): List<OutlineItem> {
        val uncertain = mutableListOf<OutlineItem>()
        val builder = Builder(formattingInline, hidden, uncertain, Links(result))
        val rows = mutableListOf<OutlineItem>()
        result.fileRegion?.takeIf { it.isReported && !isHidden(it, hidden) }?.let { region ->
            rows += OutlineItem(ItemKind.FILE_REGION, "File region", tags(region), region.oldRange, region.newRange, region.details)
        }
        rows += builder.children(result.changes)
        if (uncertain.isNotEmpty()) rows += OutlineItem(ItemKind.UNCERTAIN_GROUP, "Uncertain (${uncertain.size})", children = uncertain)
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
     * The file summary line, such as `3 changed · 1 extracted · 1 added · 2 removed · 1 moved · 2 formatting-only`. An
     * added declaration extracted from old code counts as extracted instead of added. It counts every change, hidden or
     * not, and ends with how many are [hidden], such as `· 2 hidden`.
     */
    fun summary(result: ComparisonResult, hidden: Set<ChangeType> = emptySet()): String {
        val counts = LinkedHashMap<String, Int>()
        listOf(Tag.CHANGED.text, EXTRACTED, Tag.ADDED.text, Tag.REMOVED.text, Tag.MOVED.text, Tag.FORMATTING_ONLY.text,
            Tag.RENAME_CANDIDATE.text, Tag.UNCERTAIN.text, Tag.UNANALYZED.text).forEach { counts[it] = 0 }
        fun count(node: ChangeNode) {
            if (node.isChangedItself) {
                tags(node).map { if (it == Tag.ADDED && node.extractedFrom.isNotEmpty()) EXTRACTED else it.text }
                    .filter { it in counts }.forEach { counts[it] = counts.getValue(it) + 1 }
            }
            node.children.forEach(::count)
        }
        result.fileRegion?.let(::count)
        result.changes.forEach(::count)
        counts[Tag.UNANALYZED.text] = counts.getValue(Tag.UNANALYZED.text) + result.unanalyzed.size
        val parts = counts.filterValues { it > 0 }.map { (label, n) -> "$n $label" } +
            listOfNotNull(hiddenCount(result, hidden).takeIf { it > 0 }?.let { "$it hidden" })
        return if (parts.isEmpty()) "No structural changes" else parts.joinToString(" · ")
    }

    /** How many changes [build] leaves out, or shows only as containers, because their types are [hidden]. */
    fun hiddenCount(result: ComparisonResult, hidden: Set<ChangeType>): Int {
        if (hidden.isEmpty()) return 0
        fun count(node: ChangeNode): Int =
            (if (node.isReported && isHidden(node, hidden)) 1 else 0) + node.children.sumOf(::count)
        return (result.fileRegion?.let(::count) ?: 0) + result.changes.sumOf(::count)
    }

    /** A change itself whose every label is of a [hidden] type; an uncertain, rename candidate or unanalyzed change never is. */
    fun isHidden(node: ChangeNode, hidden: Set<ChangeType>): Boolean {
        if (!node.isChangedItself || hidden.isEmpty()) return false
        val hiddenTags = hidden.flatMapTo(HashSet()) { it.tags }
        return tags(node).all { it in hiddenTags }
    }

    /**
     * A subject's title: kind, receiver, name and signature for a declaration (`method parse(String,int)`,
     * `function Date.formatIsoDate()`), or a region's description.
     */
    fun title(subject: Subject): String = when (subject) {
        is Subject.Declaration -> {
            val kind = kind(subject.kind)
            val name = name(subject)
            (if (name.isEmpty()) kind else "$kind $name") + (subject.signature?.let { "($it)" } ?: "")
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
        val kindFrom = node.kindChangedFrom?.let { kind(it) + " → " }.orEmpty()
        if (from == null || subject == null) return kindFrom + title(node.subject)
        return kindFrom + title(subject.copy(name = "${name(from)} → ${name(subject)}", receiver = null))
    }

    private fun kind(kind: DeclarationKind): String = kind.name.lowercase().replace('_', ' ')

    private fun name(subject: Subject.Declaration): String = (subject.receiver?.let { "$it." } ?: "") + subject.name

    private const val EXTRACTED = "extracted"

    /** Names after which a note says how many more there are. */
    private const val MAX_NAMES = 3

    /**
     * The extraction links of a result from both ends (extraction detection specification §3), with each node's
     * container, so a note can name the other end by its container when that differs.
     */
    private class Links(result: ComparisonResult) {
        private val parents = IdentityHashMap<ChangeNode, ChangeNode?>()

        /** Matched nodes by their old range, which an origin names its source by. */
        private val sources = HashMap<TextRange, ChangeNode>()

        /** The added nodes extracted from each source, by the source's old range. */
        private val targets = HashMap<TextRange, MutableList<ChangeNode>>()

        init {
            fun visit(node: ChangeNode, parent: ChangeNode?) {
                parents[node] = parent
                if (node.presence == Presence.MATCHED && node.oldRange != null) sources.putIfAbsent(node.oldRange, node)
                node.extractedFrom.forEach { targets.getOrPut(it.declarationRange, ::mutableListOf) += node }
                node.children.forEach { visit(it, node) }
            }
            result.changes.forEach { visit(it, null) }
        }

        /** `extracted from …` for an added node, `code extracted to …` for a source; null for neither. */
        fun note(node: ChangeNode): String? {
            if (node.extractedFrom.isNotEmpty()) {
                return "extracted from " + names(node.extractedFrom.map { origin ->
                    val source = sources[origin.declarationRange]
                    label(origin.subject, source?.let { parents[it] }, parents[node], qualify = source != null)
                })
            }
            if (node.extractedTo.isEmpty()) return null
            val extracted = node.oldRange?.let { targets[it] }.orEmpty()
            return "code extracted to " + names(node.extractedTo.map { subject ->
                val target = extracted.firstOrNull { it.subject == subject }
                label(subject, target?.let { parents[it] }, parents[node], qualify = target != null)
            })
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

    /** The change labels of a node that is a change itself; none for a node shown only as a container. */
    fun tags(node: ChangeNode): List<Tag> {
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
                Content.CHANGED -> add(Tag.CHANGED)
                Content.FORMATTING_ONLY -> add(Tag.FORMATTING_ONLY)
                Content.UNANALYZED -> add(Tag.UNANALYZED)
                Content.UNCHANGED -> {}
            }
            if (node.signatureChanged) add(Tag.SIGNATURE_CHANGED)
        }
    }

    private class Builder(
        private val formattingInline: Boolean,
        private val hidden: Set<ChangeType>,
        private val uncertain: MutableList<OutlineItem>,
        private val links: Links,
    ) {

        /** The rows for [nodes], the children of one container; uncertain nodes go to [uncertain] instead. */
        fun children(nodes: List<ChangeNode>): List<OutlineItem> {
            val rows = mutableListOf<OutlineItem>()
            val formatting = mutableListOf<OutlineItem>()
            for (node in nodes) {
                if (!node.isReported) continue
                if (node.presence == Presence.UNCERTAIN) {
                    uncertain += uncertainItem(node)
                    continue
                }
                val item = item(node) ?: continue
                if (!formattingInline && isFormattingOnly(node)) formatting += item else rows += item
            }
            if (formatting.isNotEmpty()) rows += OutlineItem(ItemKind.FORMATTING_GROUP, "Formatting-only (${formatting.size})", children = formatting)
            return rows
        }

        /**
         * The row for a reported node, or null for a container whose only reported children were uncertain or hidden. A
         * hidden node with shown children is a container without change labels.
         */
        private fun item(node: ChangeNode): OutlineItem? {
            val children = children(node.children)
            if (isHidden(node, hidden)) {
                if (children.isEmpty()) return null
                return OutlineItem(ItemKind.CHANGE, title(node), emptyList(), node.oldRange, node.newRange, children = children)
            }
            if (!node.isChangedItself && children.isEmpty()) return null
            // An extraction frames the code it came from on the old side, where it is otherwise absent.
            val oldRange = node.oldRange ?: node.extractedFrom.firstOrNull()?.range
            return OutlineItem(ItemKind.CHANGE, title(node), tags(node), oldRange, node.newRange, node.details, children, links.note(node))
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
            return OutlineItem(ItemKind.CHANGE, title(node), tags(node), node.oldRange, node.newRange, node.details, candidates + children(node.children))
        }

        /** A matched node whose only change is formatting, with nothing reported inside it. */
        private fun isFormattingOnly(node: ChangeNode): Boolean =
            node.presence == Presence.MATCHED && !node.moved && !node.signatureChanged &&
                node.content == Content.FORMATTING_ONLY && node.children.none { it.isReported }
    }

    /** 1-based lines of [range]: `line 3` or `lines 3-5`. */
    private fun lines(range: TextRange, text: CharSequence): String {
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
