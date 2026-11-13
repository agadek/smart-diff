package io.github.agadek.smartdiff.ui

import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.Content
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
) {
    val isGroup: Boolean
        get() = kind == ItemKind.FORMATTING_GROUP || kind == ItemKind.UNCERTAIN_GROUP ||
            kind == ItemKind.UNANALYZED_GROUP || kind == ItemKind.SECTION

    /** The row's text, which is also its accessible name: the title, then the change labels, the note and the confidence. */
    val label: String get() {
        val labels = tags.map { it.text } + listOfNotNull(note, confidence?.name?.lowercase())
        return if (labels.isEmpty()) title else "$title  ${labels.joinToString(", ")}"
    }

    /** A copy of this row with new children, which keeps every other field. */
    internal fun withChildren(children: List<OutlineItem>): OutlineItem =
        OutlineItem(kind, title, tags, oldRange, newRange, details, children, note, confidence, frames, colour)

    /** A copy of this row without its own labels or details, as a container of the rows that remain. */
    internal fun asContainer(children: List<OutlineItem>): OutlineItem =
        OutlineItem(kind, title, emptyList(), oldRange, newRange, emptyList(), children, note = null, confidence = null, frames = emptyList())

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
        whitespacePolicy: WhitespacePolicy = WhitespacePolicy.INSIGNIFICANT,
    ): List<OutlineItem> {
        val uncertain = mutableListOf<OutlineItem>()
        val usages = Usages(result, oldText, newText, whitespacePolicy)
        val builder = Builder(formattingInline, hidden, uncertain, Links(result), usages)
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
     * The file summary line, such as `3 changed · 1 extracted · 1 added · 1 inlined · 1 removed · 1 moved`. An added
     * declaration extracted from old code counts as extracted instead of added; a removed declaration inlined into
     * another counts as inlined instead of removed. It counts every change, hidden or not, and ends with how many are
     * [hidden], such as `· 2 hidden`.
     */
    fun summary(
        result: ComparisonResult,
        hidden: Set<ChangeType> = emptySet(),
        oldText: CharSequence = "",
        newText: CharSequence = "",
        whitespacePolicy: WhitespacePolicy = WhitespacePolicy.INSIGNIFICANT,
    ): String {
        val usages = Usages(result, oldText, newText, whitespacePolicy)
        val counts = LinkedHashMap<String, Int>()
        listOf(Tag.CHANGED.text, EXTRACTED, Tag.ADDED.text, INLINED, Tag.REMOVED.text, Tag.MOVED.text, Tag.FORMATTING_ONLY.text,
            Tag.RENAME_CANDIDATE.text, Tag.USAGES_RENAMED.text, Tag.UNCERTAIN.text, Tag.UNANALYZED.text).forEach { counts[it] = 0 }
        fun count(node: ChangeNode) {
            if (node.isChangedItself || usages.hasUsages(node)) {
                tags(node, usages.isRenameOnly(node)).map {
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
        val parts = counts.filterValues { it > 0 }.map { (label, n) -> "$n $label" } +
            listOfNotNull(hiddenCount(result, hidden).takeIf { it > 0 }?.let { "$it hidden" }) +
            if (result.status == Status.ANALYZED || result.status == Status.PARTIAL)
                listOfNotNull(result.analysisNote) else emptyList()
        return if (parts.isEmpty()) "No structural changes" else parts.joinToString(" · ")
    }

    /** How many changes [build] leaves out, or shows only as containers, because their types are [hidden]. */
    fun hiddenCount(result: ComparisonResult, hidden: Set<ChangeType>): Int {
        if (hidden.isEmpty()) return 0
        val usages = Usages(result, "", "", WhitespacePolicy.INSIGNIFICANT)
        fun count(node: ChangeNode): Int =
            (if (node.isReported && isHidden(node, hidden, usages.hasUsages(node))) 1 else 0) + node.children.sumOf(::count)
        return (result.fileRegion?.let(::count) ?: 0) + result.changes.sumOf(::count)
    }

    /** The nodes whose innermost matched declaration contains a grouped usage, for the native diff's hidden lines (R8). */
    fun groupedUsageNodes(result: ComparisonResult): Set<ChangeNode> =
        Usages(result, "", "", WhitespacePolicy.INSIGNIFICANT).nodesWithUsages()

    /** A change itself whose every label is of a [hidden] type; an uncertain, rename candidate, grouped usage or unanalyzed change never is. */
    fun isHidden(node: ChangeNode, hidden: Set<ChangeType>, groupedUsages: Boolean = false): Boolean {
        if (groupedUsages || !node.isChangedItself || hidden.isEmpty()) return false
        val hiddenTags = hidden.flatMapTo(HashSet()) { it.tags }
        return tags(node).all { it in hiddenTags }
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
        if (kind == DeclarationKind.ITEM) "" else kind.name.lowercase().replace('_', ' ')

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

    /** The change labels of a node that is a change itself; none for a node shown only as a container. */
    fun tags(node: ChangeNode, usagesRenamed: Boolean = false): List<Tag> {
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
                Content.CHANGED -> add(Tag.CHANGED)
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
    ) {

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
            if (formatting.isNotEmpty()) rows += OutlineItem(ItemKind.FORMATTING_GROUP, "Formatting-only (${formatting.size})", children = formatting)
            return rows
        }

        /**
         * The row for a reported node, or null for a container whose only reported children were uncertain or hidden. A
         * hidden node with shown children is a container without change labels. A node with grouped usages is never
         * hidden and its row frames the usages inside it as well.
         */
        private fun item(node: ChangeNode): OutlineItem? {
            val children = children(node.children)
            val grouped = usages.references(node)
            if (isHidden(node, hidden, grouped.isNotEmpty())) {
                if (children.isEmpty()) return null
                return OutlineItem(ItemKind.CHANGE, title(node), emptyList(), node.oldRange, node.newRange, children = children)
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
            return OutlineItem(
                ItemKind.CHANGE, title(node), tags(node, usages.isRenameOnly(node)), oldRange, newRange,
                details.distinct(), children, listOfNotNull(usageNote, links.note(node)).joinToString(", ").ifEmpty { null },
            )
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
                node.content == Content.FORMATTING_ONLY && node.children.none { it.isReported } && !usages.hasUsages(node)
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
