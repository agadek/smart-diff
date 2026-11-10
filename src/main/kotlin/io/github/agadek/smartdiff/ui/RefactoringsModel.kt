package io.github.agadek.smartdiff.ui

import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy

/**
 * The refactorings view of the structural outline (relationships/06): one row per relationship, then the changes no
 * shown relationship covers under `Other changes`. It reads the same [ComparisonResult] and request texts as the
 * container tree, so switching views never compares a file again.
 */
object RefactoringsModel {

    const val REFACTORINGS = "Refactorings"
    const val OTHER_CHANGES = "Other changes"
    const val NO_REFACTORINGS = "No refactorings found"

    /**
     * The rows of the refactorings view. A relationship whose every kind is [hiddenKinds] is left out and its
     * declarations fall back to [OTHER_CHANGES]; a low-confidence relationship is shown as a possible row and also
     * under each of its declarations. [hidden] is the change type filter, applied to the same rows as the container
     * tree.
     */
    fun build(
        result: ComparisonResult,
        oldText: CharSequence,
        newText: CharSequence,
        formattingInline: Boolean,
        hidden: Set<ChangeType> = emptySet(),
        hiddenKinds: Set<RelationshipKind> = emptySet(),
        whitespacePolicy: WhitespacePolicy = WhitespacePolicy.INSIGNIFICANT,
    ): List<OutlineItem> {
        val relationships = visibleRelationships(result, hiddenKinds)
        val colours = renameColours(result, hiddenKinds)
        val rows = relationships.map { link -> relationship(link, colours[link], newText) }
        val coverage = Coverage.of(relationships)
        val lowLinks = lowLinks(relationships)
        val containerTree = OutlineModel.build(result, oldText, newText, formattingInline, hidden, whitespacePolicy)
        val other = containerTree.mapNotNull { otherChanges(it, coverage, lowLinks) }
        val refactorings = rows.ifEmpty { listOf(OutlineItem(ItemKind.EVIDENCE, NO_REFACTORINGS)) }
        return listOf(
            OutlineItem(ItemKind.SECTION, REFACTORINGS, children = refactorings),
            OutlineItem(ItemKind.SECTION, OTHER_CHANGES, children = other),
        )
    }

    /**
     * The relationships the view lists, sorted by their first new-side position and otherwise in the result's fixed
     * order. A relationship is hidden only when every one of its kinds is hidden (R14).
     */
    fun visibleRelationships(result: ComparisonResult, hiddenKinds: Set<RelationshipKind> = emptySet()): List<Relationship> =
        result.relationships
            .filter { relationship -> relationship.kinds.any { it !in hiddenKinds } }
            .sortedBy { it.new.first().range.start }

    /**
     * The palette index of every shown rename, in row order (R12). A rename whose kind is hidden gets no colour; the
     * seventh colour reuses the first.
     */
    fun renameColours(result: ComparisonResult, hiddenKinds: Set<RelationshipKind> = emptySet()): Map<Relationship, Int> {
        val colours = LinkedHashMap<Relationship, Int>()
        var next = 0
        for (relationship in visibleRelationships(result, hiddenKinds)) {
            if (RelationshipKind.RENAME !in relationship.kinds || RelationshipKind.RENAME in hiddenKinds) continue
            colours[relationship] = next % RelationshipPalette.SIZE
            next++
        }
        return colours
    }

    /**
     * The usage ranges of every rename that is shown, with the colour it has in the view, for the editors' highlights.
     * A rename that is hidden by kind or has no grouped usage adds nothing.
     */
    fun usageHighlights(result: ComparisonResult, hiddenKinds: Set<RelationshipKind> = emptySet()): List<UsageHighlight> {
        val colours = renameColours(result, hiddenKinds)
        val highlights = mutableListOf<UsageHighlight>()
        for ((relationship, colour) in colours) {
            for (reference in relationship.evidence.filterIsInstance<Evidence.Reference>()) {
                highlights += UsageHighlight(reference.oldRange, reference.newRange, colour)
            }
        }
        return highlights.sortedWith(compareBy({ it.oldRange.start }, { it.newRange.start }, { it.colour }))
    }

    /** One usage's exact ranges on both sides and the palette index of its rename. */
    data class UsageHighlight(val oldRange: TextRange, val newRange: TextRange, val colour: Int)

    /** The title of a relationship row, without the confidence prefix or the usage count. */
    fun title(relationship: Relationship): String {
        val oldName = name(relationship.old.first().subject)
        val newName = name(relationship.new.first().subject)
        val kinds = relationship.kinds
        val base = when {
            kinds == setOf(RelationshipKind.RENAME) -> "rename $oldName → $newName"
            kinds == setOf(RelationshipKind.MOVE) -> if (oldName == newName) "moved $oldName" else "moved $oldName → $newName"
            kinds == setOf(RelationshipKind.KIND_CHANGE) -> kindChangeTitle(relationship, oldName, newName)
            kinds == setOf(RelationshipKind.EXTRACT) -> if (oldName == newName) "extracted $oldName" else "extracted $newName from $oldName"
            kinds == setOf(RelationshipKind.INLINE) -> "inlined $oldName into $newName"
            else -> {
                val words = kinds.joinToString(" and ") { kindWord(it) }
                if (oldName == newName) "$words $oldName" else "$words $oldName → $newName"
            }
        }
        val more = (relationship.old.size - 1) + (relationship.new.size - 1)
        return base + if (more > 0) " +$more more" else ""
    }

    private fun kindChangeTitle(relationship: Relationship, oldName: String, newName: String): String {
        val oldKind = (relationship.old.first().subject as? Subject.Declaration)?.kind?.let(OutlineModel::kindName)
        val newKind = (relationship.new.first().subject as? Subject.Declaration)?.kind?.let(OutlineModel::kindName)
        return when {
            oldKind == null || newKind == null -> if (oldName == newName) "kind change $oldName" else "kind change $oldName → $newName"
            oldName == newName -> "kind change $oldKind → $newKind $oldName"
            else -> "kind change $oldKind $oldName → $newKind $newName"
        }
    }

    private fun kindWord(kind: RelationshipKind): String = when (kind) {
        RelationshipKind.RENAME -> "rename"
        RelationshipKind.MOVE -> "move"
        RelationshipKind.KIND_CHANGE -> "kind change"
        RelationshipKind.EXTRACT -> "extraction"
        RelationshipKind.INLINE -> "inlining"
    }

    private fun name(subject: Subject): String = when (subject) {
        is Subject.Declaration -> if (subject.name.isEmpty()) OutlineModel.kindName(subject.kind) else OutlineModel.name(subject)
        is Subject.Region -> subject.description
    }

    private fun relationship(relationship: Relationship, colour: Int?, newText: CharSequence): OutlineItem {
        val references = relationship.evidence.filterIsInstance<Evidence.Reference>()
        val content = relationship.evidence.filterIsInstance<Evidence.Content>()
        val primaryOld = relationship.old.first().range
        val primaryNew = relationship.new.first().range
        val frames = relationship.old.drop(1).map { RangePair(it.range, null) } +
            relationship.new.drop(1).map { RangePair(null, it.range) }
        val details = content.map { RangePair(it.oldRange, it.newRange) } + references.map { RangePair(it.oldRange, it.newRange) }
        val usages = references.map { reference ->
            OutlineItem(
                ItemKind.USAGE,
                "usage  ${OutlineModel.lines(reference.newRange, newText)}",
                oldRange = reference.oldRange,
                newRange = reference.newRange,
                note = "resolves to ${OutlineModel.title(reference.symbol)}",
            )
        }
        val evidence = evidence(relationship)
        return OutlineItem(
            ItemKind.RELATIONSHIP,
            titleWithConfidence(relationship) + if (references.isEmpty()) "" else ", ${count(references.size)}",
            oldRange = primaryOld,
            newRange = primaryNew,
            details = details,
            children = usages + evidence,
            confidence = relationship.confidence,
            frames = frames,
            colour = colour,
        )
    }

    private fun titleWithConfidence(relationship: Relationship): String {
        val prefix = when (relationship.confidence) {
            Confidence.HIGH -> ""
            Confidence.MEDIUM -> "candidate "
            Confidence.LOW -> "possible "
        }
        return prefix + title(relationship)
    }

    private fun count(n: Int): String = if (n == 1) "1 usage" else "$n usages"

    private fun evidence(relationship: Relationship): List<OutlineItem> = buildList {
        for (item in relationship.evidence) {
            when (item) {
                is Evidence.Name -> add(OutlineItem(ItemKind.EVIDENCE, "name  ${item.old} → ${item.new}"))
                is Evidence.Content -> add(
                    OutlineItem(
                        ItemKind.EVIDENCE,
                        "content  ${item.shared} of ${item.total} tokens",
                        oldRange = item.oldRange,
                        newRange = item.newRange,
                    ),
                )
                is Evidence.Structure -> add(OutlineItem(ItemKind.EVIDENCE, "container  ${item.oldPath} → ${item.newPath}"))
                is Evidence.Position -> add(OutlineItem(ItemKind.EVIDENCE, "position  ${item.oldIndex + 1} → ${item.newIndex + 1}"))
                is Evidence.Reference -> {}
                is Evidence.Score -> {}
            }
        }
        // The old and new kinds of a kind change are structure evidence too, as the row title already shows them.
        val oldKind = (relationship.old.first().subject as? Subject.Declaration)?.kind
        val newKind = (relationship.new.first().subject as? Subject.Declaration)?.kind
        if (RelationshipKind.KIND_CHANGE in relationship.kinds && oldKind != null && newKind != null && oldKind != newKind) {
            add(OutlineItem(ItemKind.EVIDENCE, "kind  ${OutlineModel.kindName(oldKind)} → ${OutlineModel.kindName(newKind)}"))
        }
    }

    /** The ranges of every endpoint a shown, high or medium relationship covers, except rows that keep their labels (R15). */
    private class Coverage(val old: Set<TextRange>, val new: Set<TextRange>) {
        fun covers(item: OutlineItem): Boolean =
            item.oldRange?.let { it in old } == true || item.newRange?.let { it in new } == true

        companion object {
            fun of(relationships: List<Relationship>): Coverage {
                val coveredOld = HashSet<TextRange>()
                val coveredNew = HashSet<TextRange>()
                for (relationship in relationships) {
                    if (relationship.confidence == Confidence.LOW) continue
                    relationship.old.forEach { coveredOld += it.range }
                    relationship.new.forEach { coveredNew += it.range }
                }
                return Coverage(coveredOld, coveredNew)
            }
        }
    }

    /**
     * One `possible …` row for each declaration of a low-confidence relationship, keyed by the declaration's range on
     * its own side (R8). Old and new ranges are kept apart because a `TextRange` is only offsets; the same offsets on
     * the other side must not match. A relationship that is hidden by kind adds no link.
     */
    private fun lowLinks(relationships: List<Relationship>): LowLinks {
        val old = HashMap<TextRange, MutableList<OutlineItem>>()
        val new = HashMap<TextRange, MutableList<OutlineItem>>()
        for (relationship in relationships) {
            if (relationship.confidence != Confidence.LOW) continue
            val item = possible(relationship)
            relationship.old.forEach { old.getOrPut(it.range, ::mutableListOf) += item }
            relationship.new.forEach { new.getOrPut(it.range, ::mutableListOf) += item }
        }
        return LowLinks(old, new)
    }

    /** Low-confidence links looked up only on the side whose range they belong to. */
    private class LowLinks(
        private val old: Map<TextRange, List<OutlineItem>>,
        private val new: Map<TextRange, List<OutlineItem>>,
    ) {
        fun at(item: OutlineItem): List<OutlineItem> =
            (item.oldRange?.let { old[it] }.orEmpty() + item.newRange?.let { new[it] }.orEmpty()).distinct()
    }

    private fun possible(relationship: Relationship): OutlineItem = OutlineItem(
        ItemKind.EVIDENCE,
        "possible ${title(relationship)}",
        oldRange = relationship.old.first().range,
        newRange = relationship.new.first().range,
        frames = relationship.old.drop(1).map { RangePair(it.range, null) } +
            relationship.new.drop(1).map { RangePair(null, it.range) },
        details = relationship.evidence.filterIsInstance<Evidence.Reference>().map { RangePair(it.oldRange, it.newRange) },
        confidence = Confidence.LOW,
    )

    /**
     * The container tree's row [item] as `Other changes` shows it: a covered declaration is left out, a covered
     * container keeps the rows inside it, and a low-confidence link is added under each of its declarations. An
     * uncertain, unanalyzed or changed row is never covered, because a relationship must not hide it (R15).
     */
    private fun otherChanges(
        item: OutlineItem,
        coverage: Coverage,
        lowLinks: LowLinks,
    ): OutlineItem? {
        val children = item.children.mapNotNull { otherChanges(it, coverage, lowLinks) }
        val links = lowLinks.at(item)
        val allChildren = children + links
        val covered = !keepsItsOwnLabels(item) && coverage.covers(item)
        if (covered) {
            if (allChildren.isEmpty()) return null
            return item.asContainer(allChildren)
        }
        // A container with no labels of its own disappears when every child is covered, as with the change type filter.
        if (item.tags.isEmpty() && item.children.isNotEmpty() && allChildren.isEmpty()) return null
        return if (allChildren == item.children) item else item.withChildren(allChildren)
    }

    /**
     * A row whose change must stay in `Other changes` even when a relationship links it: an uncertain, unanalyzed or
     * changed declaration keeps its own labels and notes beside the relationship row (R15).
     */
    private fun keepsItsOwnLabels(item: OutlineItem): Boolean = item.tags.any {
        it == Tag.UNCERTAIN || it == Tag.UNANALYZED || it == Tag.CHANGED || it == Tag.SIGNATURE_CHANGED ||
            it == Tag.FORMATTING_ONLY || it == Tag.USAGES_RENAMED
    }
}
