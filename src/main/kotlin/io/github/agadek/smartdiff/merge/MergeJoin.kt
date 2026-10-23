package io.github.agadek.smartdiff.merge

import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange

/** Which of the two compared versions of a merge: the left pane's or the right pane's (three-way merge plan §4). */
enum class MergeSide { LEFT, RIGHT }

/** The group a merge row is listed in (plan §4). */
enum class MergeGroup {
    /** Each side changed the declaration itself: a likely conflict. */
    CHANGED_ON_BOTH_SIDES,
    LEFT_ONLY,
    RIGHT_ONLY,

    /** Uncertain on at least one side; never resolved with the other side's result. */
    UNCERTAIN,

    /** Changed on neither side itself; kept only as the container of changed declarations. */
    UNCHANGED,
}

/**
 * One row of the merge outline: a base declaration with its node in each side's result, or a declaration added on one
 * side or, with the same key, on both.
 */
data class MergeNode(
    /** The base subject, or the subject of an added declaration. */
    val subject: Subject,
    /** The declaration's range in the base; null for an added declaration. */
    val baseRange: TextRange?,
    /** The node in the base → left result; null where the left side has none, or its result was not analyzed. */
    val left: ChangeNode?,
    /** The node in the base → right result; null where the right side has none, or its result was not analyzed. */
    val right: ChangeNode?,
    val group: MergeGroup,
    /** Only for [MergeGroup.CHANGED_ON_BOTH_SIDES]: both sides end with the same text, or both removed it. */
    val sameChange: Boolean = false,
    val children: List<MergeNode> = emptyList(),
) {
    fun node(side: MergeSide): ChangeNode? = if (side == MergeSide.LEFT) left else right
}

/** The join of a merge's two comparisons, base → left and base → right (plan §5). */
data class JoinedResult(
    val left: ComparisonResult,
    val right: ComparisonResult,
    /**
     * The two results read the base differently (plan §5.2 step 2): no rows are joined, and the outline lists each
     * side's result on its own.
     */
    val baseMismatch: Boolean = false,
    /** The file region of both sides; null when neither side changed it. */
    val fileRegion: MergeNode? = null,
    /** Top-level rows, by base containment; added declarations follow the base ones. */
    val nodes: List<MergeNode> = emptyList(),
) {
    fun result(side: MergeSide): ComparisonResult = if (side == MergeSide.LEFT) left else right

    /** Whether the outline lists [side]'s changes: its result was analyzed, fully or in part. */
    fun isAnalyzed(side: MergeSide): Boolean = result(side).status.let { it == Status.ANALYZED || it == Status.PARTIAL }
}

/**
 * Joins the two results of a merge by their base-side ranges (three-way merge plan §5.2). A pure function of its inputs:
 * deterministic and free of IntelliJ API.
 *
 * Every node that either result reports as changed itself appears in exactly one row, so nothing either side changed
 * is hidden. A declaration is changed on a side when its node there is a change by itself
 * ([ChangeNode.isChangedItself]); a container whose members changed is only their container.
 */
object MergeJoin {

    fun join(left: ComparisonResult, right: ComparisonResult, leftText: CharSequence, rightText: CharSequence): JoinedResult {
        val sides = listOf(SideIndex(MergeSide.LEFT, left, leftText), SideIndex(MergeSide.RIGHT, right, rightText))
        val (leftIndex, rightIndex) = sides
        if (leftIndex.analyzed && rightIndex.analyzed && !sameBase(leftIndex, rightIndex)) {
            return JoinedResult(left, right, baseMismatch = true)
        }
        val builder = TreeBuilder(leftIndex, rightIndex)
        return JoinedResult(left, right, fileRegion = fileRegion(leftIndex, rightIndex), nodes = builder.build())
    }

    /** Both results hold the same base declarations: the same ranges, each with the same base kind and name. */
    private fun sameBase(left: SideIndex, right: SideIndex): Boolean =
        left.byBaseRange.keys == right.byBaseRange.keys &&
            left.byBaseRange.all { (range, node) -> baseIdentity(node) == baseIdentity(right.byBaseRange.getValue(range)) }

    private fun fileRegion(left: SideIndex, right: SideIndex): MergeNode? {
        val leftRegion = left.result.fileRegion?.takeIf { left.analyzed && it.isChangedItself }
        val rightRegion = right.result.fileRegion?.takeIf { right.analyzed && it.isChangedItself }
        val subject = (leftRegion ?: rightRegion)?.subject ?: return null
        val group = group(leftRegion, rightRegion)
        val same = group == MergeGroup.CHANGED_ON_BOTH_SIDES && regionText(leftRegion!!, left.text) == regionText(rightRegion!!, right.text)
        return MergeNode(subject, null, leftRegion, rightRegion, group, same)
    }

    /** The file region's new-side text: its separate ranges, in `details`, or else its whole range. */
    private fun regionText(region: ChangeNode, text: CharSequence): String {
        val ranges = region.details.mapNotNull { it.new }.ifEmpty { listOfNotNull(region.newRange) }
        return ranges.joinToString("\u0000") { text.substring(it) }
    }

    private fun group(left: ChangeNode?, right: ChangeNode?): MergeGroup {
        if (left?.presence == Presence.UNCERTAIN || right?.presence == Presence.UNCERTAIN) return MergeGroup.UNCERTAIN
        val leftChanged = left?.isChangedItself == true
        val rightChanged = right?.isChangedItself == true
        return when {
            leftChanged && rightChanged -> MergeGroup.CHANGED_ON_BOTH_SIDES
            leftChanged -> MergeGroup.LEFT_ONLY
            rightChanged -> MergeGroup.RIGHT_ONLY
            else -> MergeGroup.UNCHANGED
        }
    }

    /** Both sides end with the same text for the declaration, or both removed it (plan §5.2 step 4). */
    private fun sameChange(left: ChangeNode, right: ChangeNode, leftText: CharSequence, rightText: CharSequence): Boolean {
        val leftRange = left.newRange
        val rightRange = right.newRange
        if (leftRange == null || rightRange == null) return leftRange == null && rightRange == null
        return leftText.substring(leftRange) == rightText.substring(rightRange)
    }

    private fun CharSequence.substring(range: TextRange): String = subSequence(range.start, range.end).toString()

    /**
     * The kind and name a node's subject had in the base. The base signature is not in the result when the signature
     * changed, so it is left out.
     */
    private fun baseIdentity(node: ChangeNode): Any {
        val base = node.renamedFrom ?: node.subject
        if (base !is Subject.Declaration) return base
        return (node.kindChangedFrom ?: base.kind) to base.name
    }

    /** One side's result, indexed. */
    private class SideIndex(val side: MergeSide, val result: ComparisonResult, val text: CharSequence) {
        val analyzed = result.status == Status.ANALYZED || result.status == Status.PARTIAL

        /** Nodes present in the base (matched, removed, or uncertain on the base side), by base range. */
        val byBaseRange = LinkedHashMap<TextRange, ChangeNode>()

        /** Nodes absent from the base (added, or uncertain on the side), in preorder, with their ancestors. */
        val sideOnly = mutableListOf<Pair<ChangeNode, List<ChangeNode>>>()

        init {
            if (analyzed) result.changes.forEach { visit(it, emptyList()) }
        }

        private fun visit(node: ChangeNode, ancestors: List<ChangeNode>) {
            val baseRange = node.oldRange
            if (baseRange != null) byBaseRange[baseRange] = node else sideOnly += node to ancestors
            val path = ancestors + node
            node.children.forEach { visit(it, path) }
        }
    }

    private class Row(
        val subject: Subject,
        val baseRange: TextRange?,
        var left: ChangeNode? = null,
        var right: ChangeNode? = null,
        var sameChange: Boolean = false,
    ) {
        val children = mutableListOf<Row>()

        fun set(side: MergeSide, node: ChangeNode) {
            if (side == MergeSide.LEFT) left = node else right = node
        }
    }

    private class TreeBuilder(private val left: SideIndex, private val right: SideIndex) {
        private val roots = mutableListOf<Row>()
        private val baseRows = LinkedHashMap<TextRange, Row>()

        /** The rows of side-only nodes, by node identity, so their own side-only children nest under them. */
        private val sideRows = HashMap<SideNode, Row>()

        fun build(): List<MergeNode> {
            buildBase()
            buildSideOnly()
            return roots.mapNotNull { freeze(it) }
        }

        /** Base declarations nest by containment of their base ranges, in base order. */
        private fun buildBase() {
            val ranges = (left.byBaseRange.keys + right.byBaseRange.keys).sortedWith(compareBy<TextRange> { it.start }.thenByDescending { it.end })
            val open = ArrayDeque<Row>()
            for (range in ranges) {
                val leftNode = left.byBaseRange[range]
                val rightNode = right.byBaseRange[range]
                val row = Row(baseSubject(leftNode ?: rightNode!!), range, leftNode, rightNode)
                if (leftNode != null && rightNode != null && leftNode.isChangedItself && rightNode.isChangedItself) {
                    row.sameChange = sameChange(leftNode, rightNode, left.text, right.text)
                }
                while (open.isNotEmpty() && !open.last().baseRange!!.encloses(range)) open.removeLast()
                (open.lastOrNull()?.children ?: roots) += row
                baseRows[range] = row
                open.addLast(row)
            }
        }

        /**
         * Added declarations go under the row of their nearest container in their side's result. An added declaration
         * whose key occurs exactly once among each side's added declarations pairs with the other side's (plan §5.2
         * step 3); uncertain ones never pair.
         */
        private fun buildSideOnly() {
            val leftKeys = keys(left)
            val rightKeys = keys(right)
            val leftUnique = leftKeys.values.groupingBy { it }.eachCount().filterValues { it == 1 }.keys
            val rightUnique = rightKeys.values.groupingBy { it }.eachCount().filterValues { it == 1 }.keys
            val paired = leftUnique intersect rightUnique
            val pairedRows = HashMap<AddedKey, Row>()

            for (index in listOf(left, right)) {
                val keysOfSide = if (index === left) leftKeys else rightKeys
                for ((node, ancestors) in index.sideOnly) {
                    val key = keysOfSide[SideNode(node)]
                    val pairedRow = key?.takeIf { it in paired }?.let { pairedRows[it] }
                    if (pairedRow != null) {
                        pairedRow.set(index.side, node)
                        val leftNode = pairedRow.left!!
                        pairedRow.sameChange = sameChange(leftNode, node, left.text, right.text)
                        sideRows[SideNode(node)] = pairedRow
                        continue
                    }
                    val row = Row(node.subject, null).also { it.set(index.side, node) }
                    (container(ancestors)?.children ?: roots) += row
                    sideRows[SideNode(node)] = row
                    if (key != null && key in paired) pairedRows[key] = row
                }
            }
        }

        /** The row of the nearest ancestor that has one: a base declaration or an added container. */
        private fun container(ancestors: List<ChangeNode>): Row? {
            for (ancestor in ancestors.asReversed()) {
                val row = ancestor.oldRange?.let { baseRows[it] } ?: sideRows[SideNode(ancestor)]
                if (row != null) return row
            }
            return null
        }

        /** The pairing key of each added node of a side: its containers, by base range or key, then its subject. */
        private fun keys(index: SideIndex): Map<SideNode, AddedKey> {
            val keys = LinkedHashMap<SideNode, AddedKey>()
            for ((node, ancestors) in index.sideOnly) {
                if (node.presence != Presence.ADDED) continue
                val path = ancestors.map { ancestor -> ancestor.oldRange ?: keys[SideNode(ancestor)] ?: ancestor.subject }
                keys[SideNode(node)] = AddedKey(path, node.subject)
            }
            return keys
        }

        /** The finished row, or null when neither it nor any row under it is a change. */
        private fun freeze(row: Row): MergeNode? {
            val children = row.children.mapNotNull { freeze(it) }
            val group = group(row.left, row.right)
            if (group == MergeGroup.UNCHANGED && children.isEmpty()) return null
            val sameChange = row.sameChange && group == MergeGroup.CHANGED_ON_BOTH_SIDES
            return MergeNode(row.subject, row.baseRange, row.left, row.right, group, sameChange, children)
        }

        private fun baseSubject(node: ChangeNode): Subject {
            val base = node.renamedFrom ?: node.subject
            val kind = node.kindChangedFrom
            return if (kind != null && base is Subject.Declaration) base.copy(kind = kind) else base
        }

        private fun TextRange.encloses(other: TextRange): Boolean = start <= other.start && other.end <= end
    }

    /** A change node by identity: equal nodes, such as two unchanged empty methods, are still different declarations. */
    private class SideNode(val node: ChangeNode) {
        override fun equals(other: Any?): Boolean = other is SideNode && other.node === node
        override fun hashCode(): Int = System.identityHashCode(node)
    }

    private data class AddedKey(val path: List<Any>, val subject: Subject)
}
