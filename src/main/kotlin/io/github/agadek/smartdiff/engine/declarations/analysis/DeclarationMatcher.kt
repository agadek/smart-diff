package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import java.util.IdentityHashMap

/** The rule that matched a declaration to its counterpart (technical specification §7). */
enum class MatchRule {
    /** M1: same kind, path, name and signature. */
    IDENTITY,

    /** M2: same kind, name and signature in a different path. */
    PATH_CHANGED,

    /** M3: same kind, path and name, different signature. */
    SIGNATURE_CHANGED,
}

/** An old-side declaration and its counterpart on the new side. */
class Match(
    val old: Declaration,
    val new: Declaration,
    val rule: MatchRule,
    /** The containers are not a match of each other (the declaration moved to another container). */
    val containerChanged: Boolean,
    /** Outside the longest common subsequence of matches in its container (its order among siblings changed). */
    val orderChanged: Boolean,
) {
    val moved: Boolean get() = containerChanged || orderChanged

    override fun toString(): String = "$rule $old -> $new" + if (moved) " moved" else ""
}

/** A declaration whose counterpart is ambiguous (rule M4). [candidates] are on the other side, in source order. */
class Uncertain(val declaration: Declaration, val candidates: List<Declaration>)

/** The outcome of matching two declaration trees. Every declaration of both sides is in exactly one list. */
class Matching(
    /** In old-side source order. */
    val matches: List<Match>,
    /** Old-side uncertain declarations, in source order. */
    val uncertainOld: List<Uncertain>,
    /** New-side uncertain declarations, in source order. */
    val uncertainNew: List<Uncertain>,
    /** Old-side declarations without a counterpart, in source order. */
    val removed: List<Declaration>,
    /** New-side declarations without a counterpart, in source order. */
    val added: List<Declaration>,
) {
    private val byOld = IdentityHashMap<Declaration, Match>().apply { matches.forEach { put(it.old, it) } }
    private val byNew = IdentityHashMap<Declaration, Match>().apply { matches.forEach { put(it.new, it) } }

    fun ofOld(old: Declaration): Match? = byOld[old]

    fun ofNew(new: Declaration): Match? = byNew[new]
}

/**
 * Matches declarations globally over the file (technical specification §7), so a declaration can be matched across
 * containers. Rules M1 to M4 run in order, each over the declarations the earlier rules left unmatched, and pair only
 * declarations whose key occurs exactly once on each side. Declarations are processed in old-side source order, which
 * makes the result deterministic.
 */
object DeclarationMatcher {

    fun match(old: DeclarationTree, new: DeclarationTree, cancellation: Cancellation): Matching {
        val oldSide = Side(old)
        val newSide = Side(new)
        // Old declaration -> its counterpart and the rule that matched it; newMatched holds the counterparts.
        val pairs = IdentityHashMap<Declaration, Pair<Declaration, MatchRule>>()
        val newMatched = identitySet()

        fun pairUnique(rule: MatchRule, key: (Side, Declaration) -> Key) {
            val oldGroups = oldSide.groupUnmatched(pairs.keys, key, cancellation)
            val newGroups = newSide.groupUnmatched(newMatched, key, cancellation)
            for ((k, olds) in oldGroups) {
                val news = newGroups[k] ?: continue
                if (olds.size == 1 && news.size == 1) {
                    pairs[olds.single()] = news.single() to rule
                    newMatched += news.single()
                }
            }
        }
        val pathAndName = { side: Side, d: Declaration -> Key(d.kind, side.path(d), d.name, null) }
        pairUnique(MatchRule.IDENTITY) { side, d -> Key(d.kind, side.path(d), d.name, d.signature) }
        pairUnique(MatchRule.PATH_CHANGED) { _, d -> Key(d.kind, null, d.name, d.signature) }
        pairUnique(MatchRule.SIGNATURE_CHANGED, pathAndName)

        // M4: a path and name that both sides still have, but not exactly once each.
        val uncertainOld = IdentityHashMap<Declaration, Uncertain>()
        val uncertainNew = IdentityHashMap<Declaration, Uncertain>()
        val newAmbiguous = newSide.groupUnmatched(newMatched, pathAndName, cancellation)
        for ((k, olds) in oldSide.groupUnmatched(pairs.keys, pathAndName, cancellation)) {
            val news = newAmbiguous[k] ?: continue
            olds.forEach { uncertainOld[it] = Uncertain(it, news) }
            news.forEach { uncertainNew[it] = Uncertain(it, olds) }
        }

        // Both top level, or the old container is matched to the new one.
        fun containersMatch(o: Declaration, n: Declaration): Boolean {
            val oldParent = oldSide.parent(o)
            val newParent = newSide.parent(n)
            return if (oldParent == null || newParent == null) oldParent == null && newParent == null
            else pairs[oldParent]?.first === newParent
        }
        val orderChanged = orderChanges(old, new, pairs, cancellation)

        return Matching(
            matches = old.all.mapNotNull { o ->
                val (n, rule) = pairs[o] ?: return@mapNotNull null
                Match(o, n, rule, containerChanged = !containersMatch(o, n), orderChanged = o in orderChanged)
            },
            uncertainOld = old.all.mapNotNull { uncertainOld[it] },
            uncertainNew = new.all.mapNotNull { uncertainNew[it] },
            removed = old.all.filter { it !in pairs && it !in uncertainOld },
            added = new.all.filter { it !in newMatched && it !in uncertainNew },
        )
    }

    /**
     * For each matched container pair and the top level, the matched children outside the longest common subsequence
     * of the old and new child order (§7, order within a container). Of several longest subsequences, the one that
     * keeps the earliest old-side declarations wins.
     */
    private fun orderChanges(
        old: DeclarationTree,
        new: DeclarationTree,
        pairs: Map<Declaration, Pair<Declaration, MatchRule>>,
        cancellation: Cancellation,
    ): Set<Declaration> {
        val changed = identitySet()
        fun order(oldChildren: List<Declaration>, newChildren: List<Declaration>) {
            cancellation.checkCanceled()
            val newIndex = IdentityHashMap<Declaration, Int>().apply { newChildren.forEachIndexed { i, d -> put(d, i) } }
            val siblings = oldChildren.filter { o -> pairs[o]?.let { it.first in newIndex } == true }
            val kept = earliestLongestIncreasing(siblings.map { newIndex.getValue(pairs.getValue(it).first) })
            siblings.forEachIndexed { i, o -> if (i !in kept) changed += o }
        }
        order(old.topLevel, new.topLevel)
        for (o in old.all) {
            val (n, _) = pairs[o] ?: continue
            if (o.children.isNotEmpty() && n.children.isNotEmpty()) order(o.children, n.children)
        }
        return changed
    }

    /**
     * Indices of a longest strictly increasing subsequence of [values]; among those, the lexicographically smallest
     * set of indices. O(n log n).
     */
    internal fun earliestLongestIncreasing(values: List<Int>): Set<Int> {
        // longestFrom[i]: length of the longest increasing subsequence that starts at i.
        val longestFrom = IntArray(values.size)
        // starts[k]: the largest first value of an increasing subsequence of length k + 1 seen so far (decreasing in k).
        val starts = mutableListOf<Int>()
        for (i in values.indices.reversed()) {
            var low = 0
            var high = starts.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (starts[mid] > values[i]) low = mid + 1 else high = mid
            }
            if (low == starts.size) starts += values[i] else starts[low] = values[i]
            longestFrom[i] = low + 1
        }
        val kept = mutableSetOf<Int>()
        var need = starts.size
        var last = Int.MIN_VALUE
        for (i in values.indices) {
            if (need > 0 && longestFrom[i] == need && values[i] > last) {
                kept += i
                last = values[i]
                need--
            }
        }
        return kept
    }

    /** One element of a path: a container's kind and name. */
    private data class Container(val kind: DeclarationKind, val name: String)

    /** A matching key; a null [path] or [signature] is left out of the key. */
    private data class Key(val kind: DeclarationKind, val path: List<Container>?, val name: String, val signature: String?)

    private class Side(val tree: DeclarationTree) {
        private val parents = IdentityHashMap<Declaration, Declaration>()
        private val paths = IdentityHashMap<Declaration, List<Container>>()

        init {
            fun visit(declaration: Declaration, path: List<Container>) {
                paths[declaration] = path
                val childPath = path + Container(declaration.kind, declaration.name)
                declaration.children.forEach {
                    parents[it] = declaration
                    visit(it, childPath)
                }
            }
            tree.topLevel.forEach { visit(it, emptyList()) }
        }

        fun parent(declaration: Declaration): Declaration? = parents[declaration]

        fun path(declaration: Declaration): List<Container> = paths.getValue(declaration)

        /** The declarations not in [matched], grouped by [key]; keys and groups in source order. */
        fun groupUnmatched(matched: Set<Declaration>, key: (Side, Declaration) -> Key, cancellation: Cancellation): Map<Key, List<Declaration>> {
            val groups = LinkedHashMap<Key, MutableList<Declaration>>()
            for (declaration in tree.all) {
                cancellation.checkCanceled()
                if (declaration !in matched) groups.getOrPut(key(this, declaration)) { mutableListOf() } += declaration
            }
            return groups
        }
    }

    private fun identitySet(): MutableSet<Declaration> = java.util.Collections.newSetFromMap(IdentityHashMap())
}
