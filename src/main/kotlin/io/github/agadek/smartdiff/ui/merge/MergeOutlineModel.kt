package io.github.agadek.smartdiff.ui.merge

import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.merge.JoinedResult
import io.github.agadek.smartdiff.merge.MergeGroup
import io.github.agadek.smartdiff.merge.MergeNode
import io.github.agadek.smartdiff.merge.MergeSide
import io.github.agadek.smartdiff.ui.ChangeType
import io.github.agadek.smartdiff.ui.ItemKind
import io.github.agadek.smartdiff.ui.Tag
import io.github.agadek.smartdiff.ui.OutlineItem
import io.github.agadek.smartdiff.ui.OutlineModel

/** How the merge outline lists its rows (merge outline spec 02 R1-R3). */
enum class MergeOutlineLayout {
    GROUPED_BY_SIDE,
    CONTAINER_TREE,
}

/** One side's labels on a merge row. [tags] drive colors and filtering; [value] is the text after the side name. */
data class MergeSideLabel(
    val name: String,
    val value: String,
    val tags: List<Tag>,
    val fullTitle: String,
)

/** One row of the merge outline. Ranges are on the base, the left pane and the right pane. */
data class MergeOutlineItem(
    val kind: Kind,
    val title: String,
    val group: MergeGroup? = null,
    val left: MergeSideLabel? = null,
    val right: MergeSideLabel? = null,
    val note: String? = null,
    val baseRange: TextRange? = null,
    val leftRange: TextRange? = null,
    val rightRange: TextRange? = null,
    val children: List<MergeOutlineItem> = emptyList(),
    val tooltip: String? = null,
) {
    enum class Kind { CHANGE, FILE_REGION, GROUP, CANDIDATE }

    val isGroup: Boolean get() = kind == Kind.GROUP

    /** The row text, also used as the accessible name together with the full side titles. */
    val label: String
        get() {
            val labels = buildList {
                left?.let { add("${it.name}: ${it.value}") }
                right?.let { add("${it.name}: ${it.value}") }
                note?.let(::add)
            }
            return if (labels.isEmpty()) title else "$title  ${labels.joinToString("  ")}"
        }

    /** The full side titles, which the side-name tooltip and accessibility use (spec 02 R11). */
    val accessibleName: String
        get() = tooltip?.let { "$label ($it)" } ?: label

    override fun toString(): String = accessibleName
}

/** The rows and summary line of one joined merge. */
data class MergeOutlineResult(
    val summary: String,
    val items: List<MergeOutlineItem>,
    val hiddenCount: Int = 0,
    /** The two results read the base differently; [items] holds each side's own list instead of joined rows. */
    val baseMismatch: Boolean = false,
)

/**
 * Builds the merge outline from a [JoinedResult] (merge outline spec 02, three-sided diff spec 03). It only knows the
 * engine API and the join model, so the grouped and container layouts are both testable without an IDE.
 */
object MergeOutlineModel {

    fun build(
        joined: JoinedResult,
        baseText: CharSequence,
        leftText: CharSequence,
        rightText: CharSequence,
        leftName: MergeSideName,
        rightName: MergeSideName,
        layout: MergeOutlineLayout,
        hidden: Set<ChangeType> = emptySet(),
        hideBothSides: Boolean = false,
    ): MergeOutlineResult {
        if (joined.baseMismatch) return mismatch(joined, baseText, leftText, rightText, leftName, rightName, hidden)

        val rows = mutableListOf<MergeNode>()
        collect(joined.nodes, rows)
        joined.fileRegion?.let(rows::add)
        val hiddenCount = rows.count { it.group != MergeGroup.UNCHANGED && isHidden(it, hidden, hideBothSides, layout) }
        val items = when (layout) {
            MergeOutlineLayout.GROUPED_BY_SIDE -> grouped(
                rows, joined.left, joined.right, leftName, rightName, hidden, hideBothSides, layout,
                joined.isAnalyzed(MergeSide.LEFT), joined.isAnalyzed(MergeSide.RIGHT),
            )
            MergeOutlineLayout.CONTAINER_TREE -> container(joined, leftName, rightName, hidden, hideBothSides, layout)
        }
        return MergeOutlineResult(summary(rows, leftName, rightName, hiddenCount, joined.isAnalyzed(MergeSide.LEFT), joined.isAnalyzed(MergeSide.RIGHT)), items, hiddenCount)
    }

    /** Every row, including containers whose own group is unchanged. */
    private fun collect(nodes: List<MergeNode>, out: MutableList<MergeNode>) {
        for (node in nodes) {
            out += node
            collect(node.children, out)
        }
    }

    private fun grouped(
        rows: List<MergeNode>,
        leftResult: ComparisonResult,
        rightResult: ComparisonResult,
        leftName: MergeSideName,
        rightName: MergeSideName,
        hidden: Set<ChangeType>,
        hideBothSides: Boolean,
        layout: MergeOutlineLayout,
        leftAnalyzed: Boolean,
        rightAnalyzed: Boolean,
    ): List<MergeOutlineItem> {
        val byGroup = rows.filter { it.group != MergeGroup.UNCHANGED && !isHidden(it, hidden, hideBothSides, layout) }
            .groupBy { it.group }
        val groups = listOf(MergeGroup.CHANGED_ON_BOTH_SIDES, MergeGroup.LEFT_ONLY, MergeGroup.RIGHT_ONLY, MergeGroup.UNCERTAIN)
        return groups.mapNotNull { group ->
            val children = byGroup[group].orEmpty()
                .sortedWith(compareBy<MergeNode> { it.sameChange }.thenBy { rows.indexOf(it) })
                .map { row(it, candidates(it, leftName, rightName), leftName, rightName, leftResult, rightResult, hidden = false, hiddenTypes = hidden) }
            if (children.isEmpty()) null else MergeOutlineItem(
                MergeOutlineItem.Kind.GROUP,
                groupTitle(group, leftName, rightName, leftAnalyzed, rightAnalyzed),
                group = group,
                children = children,
            )
        }
    }

    private fun container(
        joined: JoinedResult,
        leftName: MergeSideName,
        rightName: MergeSideName,
        hidden: Set<ChangeType>,
        hideBothSides: Boolean,
        layout: MergeOutlineLayout,
    ): List<MergeOutlineItem> {
        val rows = mutableListOf<MergeOutlineItem>()
        joined.fileRegion?.let { row ->
            if (!isHidden(row, hidden, hideBothSides, layout)) {
                rows += row(row, candidates(row, leftName, rightName), leftName, rightName, joined.left, joined.right, hidden = false, hiddenTypes = hidden)
            }
        }
        fun visit(nodes: List<MergeNode>): List<MergeOutlineItem> = nodes.mapNotNull { node ->
            val children = visit(node.children)
            val candidates = candidates(node, leftName, rightName)
            val hiddenNode = isHidden(node, hidden, hideBothSides, layout)
            if (node.group == MergeGroup.UNCHANGED && children.isEmpty()) return@mapNotNull null
            if (hiddenNode && candidates.isEmpty() && children.isEmpty()) return@mapNotNull null
            row(node, emptyList(), leftName, rightName, joined.left, joined.right, hiddenNode, hidden).copy(children = candidates + children)
        }
        rows += visit(joined.nodes)
        return rows
    }

    private fun row(
        node: MergeNode,
        candidates: List<MergeOutlineItem>,
        leftName: MergeSideName,
        rightName: MergeSideName,
        leftResult: ComparisonResult,
        rightResult: ComparisonResult,
        hidden: Boolean,
        hiddenTypes: Set<ChangeType>,
    ): MergeOutlineItem {
        val changed = node.group != MergeGroup.UNCHANGED
        val left = node.left?.takeIf { changed && !hidden }?.let { sideLabel(it, leftName, leftResult, hiddenTypes) }
        val right = node.right?.takeIf { changed && !hidden }?.let { sideLabel(it, rightName, rightResult, hiddenTypes) }
        val baseRange = node.baseRange ?: node.left?.extractedFrom?.firstOrNull()?.declarationRange
            ?: node.right?.extractedFrom?.firstOrNull()?.declarationRange
            ?: node.left?.oldRange ?: node.right?.oldRange
        return MergeOutlineItem(
            kind = if (node.subject is io.github.agadek.smartdiff.engine.api.Subject.Region) {
                MergeOutlineItem.Kind.FILE_REGION
            } else {
                MergeOutlineItem.Kind.CHANGE
            },
            title = OutlineModel.title(node.subject),
            group = node.group,
            left = left,
            right = right,
            note = if (!hidden && node.sameChange && changed) "same change on both sides" else null,
            baseRange = baseRange,
            leftRange = node.left?.newRange,
            rightRange = node.right?.newRange,
            children = candidates,
            tooltip = "${leftName.fullTitle} | ${rightName.fullTitle}",
        )
    }

    /** A side's labels; a rename candidate shows its new name after the label, as the merge plan's example does. */
    private fun sideLabel(
        node: ChangeNode,
        name: MergeSideName,
        result: ComparisonResult,
        hidden: Set<ChangeType>,
    ): MergeSideLabel? {
        val hiddenTags = hidden.flatMapTo(HashSet()) { it.tags }
        val tags = OutlineModel.tags(node).filter { it !in hiddenTags }
        val note = OutlineModel.note(result, node)
        val value = when {
            node.renamedFrom != null -> "rename candidate → ${OutlineModel.title(node.subject)}"
            tags.isEmpty() && note == null -> return null
            else -> (tags.map { it.text } + listOfNotNull(note)).joinToString(", ")
        }
        return MergeSideLabel(name.display, value, tags, name.fullTitle)
    }

    /** Candidates of an uncertain declaration, on the side where they live and with that side named. */
    private fun candidates(node: MergeNode, leftName: MergeSideName, rightName: MergeSideName): List<MergeOutlineItem> =
        candidates(node.left, MergeSide.LEFT, leftName) + candidates(node.right, MergeSide.RIGHT, rightName)

    private fun candidates(node: ChangeNode?, side: MergeSide, name: MergeSideName): List<MergeOutlineItem> {
        if (node?.presence != Presence.UNCERTAIN) return emptyList()
        val onBase = node.oldRange == null
        return node.candidates.map { candidate ->
            MergeOutlineItem(
                MergeOutlineItem.Kind.CANDIDATE,
                "candidate: ${OutlineModel.title(candidate.subject)}",
                baseRange = candidate.range.takeIf { onBase },
                leftRange = candidate.range.takeIf { !onBase && side == MergeSide.LEFT },
                rightRange = candidate.range.takeIf { !onBase && side == MergeSide.RIGHT },
                note = "from ${name.display}",
            )
        }
    }

    /** A row is hidden when every side label is hidden, or, in the container tree, by the changed-on-both-sides filter. */
    private fun isHidden(node: MergeNode, hidden: Set<ChangeType>, hideBothSides: Boolean, layout: MergeOutlineLayout): Boolean {
        if (node.group == MergeGroup.UNCHANGED) return false
        if (layout == MergeOutlineLayout.CONTAINER_TREE && hideBothSides && node.group == MergeGroup.CHANGED_ON_BOTH_SIDES) return true
        val sides = listOfNotNull(node.left, node.right).filter { it.isChangedItself }
        return sides.isNotEmpty() && sides.all { OutlineModel.isHidden(it, hidden) }
    }

    private fun groupTitle(
        group: MergeGroup,
        leftName: MergeSideName,
        rightName: MergeSideName,
        leftAnalyzed: Boolean,
        rightAnalyzed: Boolean,
    ): String = when (group) {
        MergeGroup.CHANGED_ON_BOTH_SIDES -> "Changed on both sides"
        MergeGroup.LEFT_ONLY -> "Changed in ${leftName.display}" + if (rightAnalyzed) " only" else ""
        MergeGroup.RIGHT_ONLY -> "Changed in ${rightName.display}" + if (leftAnalyzed) " only" else ""
        MergeGroup.UNCERTAIN -> "Uncertain"
        MergeGroup.UNCHANGED -> "Unchanged"
    }

    private fun summary(
        rows: List<MergeNode>,
        leftName: MergeSideName,
        rightName: MergeSideName,
        hiddenCount: Int,
        leftAnalyzed: Boolean,
        rightAnalyzed: Boolean,
    ): String {
        val counts = rows.filter { it.group != MergeGroup.UNCHANGED }.groupingBy { it.group }.eachCount()
        val parts = listOfNotNull(
            counts[MergeGroup.CHANGED_ON_BOTH_SIDES]?.let { "$it changed on both sides" },
            counts[MergeGroup.LEFT_ONLY]?.let { "$it in ${leftName.display}" + if (rightAnalyzed) " only" else "" },
            counts[MergeGroup.RIGHT_ONLY]?.let { "$it in ${rightName.display}" + if (leftAnalyzed) " only" else "" },
            counts[MergeGroup.UNCERTAIN]?.let { "$it uncertain" },
            hiddenCount.takeIf { it > 0 }?.let { "$it hidden" },
        )
        return if (parts.isEmpty()) "No structural changes" else parts.joinToString(" · ")
    }

    /** When the two results read the base differently, show each side's two-sided rows under its own heading. */
    private fun mismatch(
        joined: JoinedResult,
        baseText: CharSequence,
        leftText: CharSequence,
        rightText: CharSequence,
        leftName: MergeSideName,
        rightName: MergeSideName,
        hidden: Set<ChangeType>,
    ): MergeOutlineResult {
        val left = OutlineModel.build(joined.left, baseText, leftText, formattingInline = false, hidden = hidden)
            .map { sideItem(it, leftName, MergeSide.LEFT) }
        val right = OutlineModel.build(joined.right, baseText, rightText, formattingInline = false, hidden = hidden)
            .map { sideItem(it, rightName, MergeSide.RIGHT) }
        val hiddenCount = OutlineModel.hiddenCount(joined.left, hidden) + OutlineModel.hiddenCount(joined.right, hidden)
        val groups = listOfNotNull(
            left.takeIf { it.isNotEmpty() }?.let {
                MergeOutlineItem(MergeOutlineItem.Kind.GROUP, "Left: ${leftName.display}", children = it)
            },
            right.takeIf { it.isNotEmpty() }?.let {
                MergeOutlineItem(MergeOutlineItem.Kind.GROUP, "Right: ${rightName.display}", children = it)
            },
        )
        val summary = "the base was read differently for each side" + if (hiddenCount > 0) " · $hiddenCount hidden" else ""
        return MergeOutlineResult(summary, groups, hiddenCount, baseMismatch = true)
    }

    private fun sideItem(item: OutlineItem, name: MergeSideName, side: MergeSide): MergeOutlineItem {
        val label = item.tags.takeIf { it.isNotEmpty() }?.let { tags ->
            MergeSideLabel(name.display, tags.joinToString(", ") { it.text }, tags, name.fullTitle)
        }
        return MergeOutlineItem(
            kind = when {
                item.kind == ItemKind.FILE_REGION -> MergeOutlineItem.Kind.FILE_REGION
                item.isGroup -> MergeOutlineItem.Kind.GROUP
                else -> MergeOutlineItem.Kind.CHANGE
            },
            title = item.title,
            left = label.takeIf { side == MergeSide.LEFT },
            right = label.takeIf { side == MergeSide.RIGHT },
            baseRange = item.oldRange,
            leftRange = item.newRange.takeIf { side == MergeSide.LEFT },
            rightRange = item.newRange.takeIf { side == MergeSide.RIGHT },
            note = item.note,
            children = item.children.map { sideItem(it, name, side) },
            tooltip = name.fullTitle,
        )
    }
}
