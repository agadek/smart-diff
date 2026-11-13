package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.ConfidencePolicy
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.EvidenceOrder
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.RelationshipEndpoint
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import java.util.EnumSet
import java.util.IdentityHashMap

/** The rule that matched a declaration to its counterpart (technical specification §7). */
enum class MatchRule {
    /** M1: same kind, path, name and signature. */
    IDENTITY,

    /** M2: same kind, name and signature in a different path. */
    PATH_CHANGED,

    /**
     * M3: same kind, path and name, different signature; or, when that path and name occurs more than once on a side
     * (overloads that all changed), the most similar parameter list and content of the group.
     */
    SIGNATURE_CHANGED,

    /**
     * M4b: a removed and an added declaration of the same name and kind family in matched containers, the only such
     * pair there, such as a trait turned into a class or a `def` into a `val`. Reported with the signature changed.
     */
    KIND_CHANGED,

    /**
     * M5: a removed and an added declaration of the same kind, in matched containers, with a different name and
     * similar content. May be wrong, so it is reported as a candidate.
     */
    RENAME_CANDIDATE,

    /**
     * M5: a Java constructor of a class paired by [RENAME_CANDIDATE]. Its name is the class's, so it follows the class
     * and is not a rename of its own.
     */
    CONTAINER_RENAMED,
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

/** An unpaired old and new declaration whose options are too close to pair, reported as a low-confidence rename (R6). */
private class LowTie(val old: Declaration, val new: Declaration, val score: Double)

/** The pairs, their scores and the low-confidence ties of one rename group. */
private class RenameOutcome(
    val pairs: List<Pair<Declaration, Declaration>>,
    val scores: List<Double>,
    val ties: List<LowTie>,
)

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
    /**
     * A relationship for each match that is a rename candidate, is moved or changed kind (relationships/02 R25, R26), in
     * old-side source order. Extraction links are added by [ExtractionDetector].
     */
    val relationships: List<Relationship> = emptyList(),
) {
    private val byOld = IdentityHashMap<Declaration, Match>().apply { matches.forEach { put(it.old, it) } }
    private val byNew = IdentityHashMap<Declaration, Match>().apply { matches.forEach { put(it.new, it) } }

    fun ofOld(old: Declaration): Match? = byOld[old]

    fun ofNew(new: Declaration): Match? = byNew[new]
}

/**
 * Matches declarations globally over the file (technical specification §7), so a declaration can be matched across
 * containers. Rules M1 to M5 run in order, each over the declarations the earlier rules left unmatched. M1 to M3 pair
 * declarations whose key occurs exactly once on each side, except that M1 first pairs same-key declarations with the
 * same content and M3 pairs the rest of a repeated path and name by similarity. M4 marks what is still ambiguous, M4b
 * pairs a declaration that changed kind within its family, and M5 pairs removed and added declarations with similar
 * content as rename candidates. Paths use kind families, so the members of a class turned into a trait keep their path. Declarations are processed in
 * old-side source order, and ties in similarity go to the closest position, which makes the result deterministic.
 */
object DeclarationMatcher {

    /** Matches with the default [ConfidencePolicy], as a test or a caller without a request does. */
    fun match(old: DeclarationTree, new: DeclarationTree, cancellation: Cancellation): Matching =
        match(old, new, ConfidencePolicy.DEFAULT, cancellation)

    /**
     * Matches with [confidence] classifying the scored pairs. The policy never changes which pairs are found: the
     * rename floor stays at [RENAME_THRESHOLD], and the policy only tells a high pair from a medium or a low one
     * (relationships/02 R9, R13, R25).
     */
    fun match(old: DeclarationTree, new: DeclarationTree, confidence: ConfidencePolicy, cancellation: Cancellation): Matching {
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
        val identity = { side: Side, d: Declaration -> Key(d.kind, side.path(d), d.name, d.signature) }
        val pathAndName = { side: Side, d: Declaration -> Key(d.kind, side.path(d), d.name, null) }

        // M1, repeated keys: a key that occurs more than once on a side (several static initializers, overloads whose
        // signatures normalize alike) pairs declarations with the same content in source order. What remains of the
        // group may then be unique and pair below.
        val oldRepeated = oldSide.groupUnmatched(pairs.keys, identity, cancellation)
        val newRepeated = newSide.groupUnmatched(newMatched, identity, cancellation)
        for ((k, olds) in oldRepeated) {
            val news = newRepeated[k] ?: continue
            if (olds.size == 1 && news.size == 1) continue
            val unpaired = news.mapTo(mutableListOf()) { it to content(it) }
            for (o in olds) {
                cancellation.checkCanceled()
                val content = content(o)
                val i = unpaired.indexOfFirst { it.second == content }
                if (i < 0) continue
                val n = unpaired.removeAt(i).first
                pairs[o] = n to MatchRule.IDENTITY
                newMatched += n
            }
        }
        pairUnique(MatchRule.IDENTITY, identity)
        pairUnique(MatchRule.PATH_CHANGED) { _, d -> Key(d.kind, null, d.name, d.signature) }
        pairUnique(MatchRule.SIGNATURE_CHANGED, pathAndName)

        val similarity = Similarity(oldSide, newSide, cancellation)

        // M3, repeated path and name: overloads whose signatures all changed are assigned by their parameter types and
        // content over the whole group, so one pair never takes the only option of another. What remains of the group
        // may then be unique and pair as above.
        val newOverloads = newSide.groupUnmatched(newMatched, pathAndName, cancellation)
        for ((k, olds) in oldSide.groupUnmatched(pairs.keys, pathAndName, cancellation)) {
            val news = newOverloads[k] ?: continue
            for ((o, n) in similarity.overloadPairs(olds, news)) {
                pairs[o] = n to MatchRule.SIGNATURE_CHANGED
                newMatched += n
            }
        }
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

        // M4b: in each pair of matched containers and at the top level, a removed and an added declaration with the same
        // name and kinds of one family, when they are the only such pair. Containers come before their children, so a
        // member that changed kind inside a container that changed kind is paired too.
        val kindContainers = ArrayDeque<Pair<Declaration?, Declaration?>>()
        kindContainers += null to null
        for (o in old.all) pairs[o]?.first?.let { n -> if (o.children.isNotEmpty() && n.children.isNotEmpty()) kindContainers += o to n }
        while (kindContainers.isNotEmpty()) {
            val (oldContainer, newContainer) = kindContainers.removeFirst()
            cancellation.checkCanceled()
            fun kindKey(d: Declaration) = family(d.kind)?.let { it to d.name }
            val removed = (oldContainer?.children ?: old.topLevel).filter { it !in pairs && it !in uncertainOld }
                .filter { kindKey(it) != null }.groupBy(::kindKey)
            val added = (newContainer?.children ?: new.topLevel).filter { it !in newMatched && it !in uncertainNew }
                .filter { kindKey(it) != null }.groupBy(::kindKey)
            for ((key, olds) in removed) {
                val o = olds.singleOrNull() ?: continue
                val n = added[key]?.singleOrNull() ?: continue
                if (o.kind == n.kind) continue
                pairs[o] = n to MatchRule.KIND_CHANGED
                newMatched += n
                if (o.children.isNotEmpty() && n.children.isNotEmpty()) kindContainers += o to n
            }
        }

        // M5: in each pair of matched containers and at the top level, removed and added declarations of one kind with
        // similar content. Containers come before their children, so the members of a renamed class are paired too.
        // The context feature reads only the pairs of M1, M2, M3 and M4b, so it never depends on M5's own output.
        val earlierMatches = IdentityHashMap<Declaration, Declaration>().apply {
            for ((o, pair) in pairs) put(o, pair.first)
        }
        val renameScores = IdentityHashMap<Declaration, Double>()
        val lowTies = mutableListOf<LowTie>()
        val containers = ArrayDeque<Pair<Declaration?, Declaration?>>()
        containers += null to null
        for (o in old.all) pairs[o]?.first?.let { n -> if (o.children.isNotEmpty() && n.children.isNotEmpty()) containers += o to n }
        while (containers.isNotEmpty()) {
            val (oldContainer, newContainer) = containers.removeFirst()
            val removed = (oldContainer?.children ?: old.topLevel).filter { it !in pairs && it !in uncertainOld }
            val added = (newContainer?.children ?: new.topLevel).filter { it !in newMatched && it !in uncertainNew }
            for ((kind, olds) in removed.groupBy { it.kind }) {
                val news = added.filter { it.kind == kind }
                val outcome = similarity.renamePairs(olds, news, earlierMatches)
                lowTies += outcome.ties
                for ((index, pair) in outcome.pairs.withIndex()) {
                    val (o, n) = pair
                    val followsContainer = kind == DeclarationKind.CONSTRUCTOR &&
                        o.name == oldContainer?.name && n.name == newContainer?.name
                    val rule = when {
                        followsContainer -> MatchRule.CONTAINER_RENAMED
                        o.name != n.name -> MatchRule.RENAME_CANDIDATE
                        o.signature != n.signature -> MatchRule.SIGNATURE_CHANGED
                        else -> MatchRule.IDENTITY
                    }
                    pairs[o] = n to rule
                    if (rule == MatchRule.RENAME_CANDIDATE) renameScores[o] = outcome.scores[index]
                    newMatched += n
                    if (o.children.isNotEmpty() && n.children.isNotEmpty()) containers += o to n
                }
            }
        }

        // A pair the policy calls low is shown as added and removed (relationships/02 R13). For its members it is an
        // unmatched container: they keep the pair M1 to M5 found and get a MOVE when their container differs, instead of
        // appearing unchanged under the added container. Only the pair's own fields and presence are low.
        val lowRenames = identitySet()
        for ((o, pair) in pairs) {
            val score = renameScores[o]
            if (pair.second == MatchRule.RENAME_CANDIDATE && score != null && confidence.classify(score) == Confidence.LOW) {
                lowRenames += o
            }
        }

        // Both top level, or the old container is matched to the new one at high or medium confidence.
        fun containersMatch(o: Declaration, n: Declaration): Boolean {
            val oldParent = oldSide.parent(o)
            val newParent = newSide.parent(n)
            return if (oldParent == null || newParent == null) oldParent == null && newParent == null
            else pairs[oldParent]?.first === newParent && oldParent !in lowRenames
        }
        val orderChanged = orderChanges(old, new, pairs, cancellation)

        val matches = old.all.mapNotNull { o ->
            val (n, rule) = pairs[o] ?: return@mapNotNull null
            Match(o, n, rule, containerChanged = !containersMatch(o, n), orderChanged = o in orderChanged)
        }

        return Matching(
            matches = matches,
            uncertainOld = old.all.mapNotNull { uncertainOld[it] },
            uncertainNew = new.all.mapNotNull { uncertainNew[it] },
            removed = old.all.filter { it !in pairs && it !in uncertainOld },
            added = new.all.filter { it !in newMatched && it !in uncertainNew },
            relationships = relationships(matches, oldSide, newSide, renameScores, lowTies, confidence),
        )
    }

    /**
     * The rename, move and kind change relationships of [matches] (relationships/02, `Today's relationships`). A rename
     * candidate's confidence comes from its assignment score through [policy]; a move and a kind change are high, and a
     * relationship with several kinds takes its lowest one (R25, R26). A tie the assignment leaves unpaired is a
     * low-confidence rename that pairs nothing (R6).
     */
    private fun relationships(
        matches: List<Match>,
        oldSide: Side,
        newSide: Side,
        renameScores: Map<Declaration, Double>,
        lowTies: List<LowTie>,
        policy: ConfidencePolicy,
    ): List<Relationship> {
        val relationships = mutableListOf<Relationship>()
        for (match in matches) {
            val kinds = EnumSet.noneOf(RelationshipKind::class.java)
            val evidence = mutableListOf<Evidence>()
            val renameScore = if (match.rule == MatchRule.RENAME_CANDIDATE) renameScores[match.old] else null
            if (renameScore != null) {
                kinds += RelationshipKind.RENAME
                evidence += Evidence.Name(match.old.name, match.new.name)
                evidence += Evidence.Score(renameScore)
            }
            if (match.moved) kinds += RelationshipKind.MOVE
            if (match.old.kind != match.new.kind) kinds += RelationshipKind.KIND_CHANGE
            if (kinds.isEmpty()) continue
            if (match.moved || match.old.kind != match.new.kind) {
                evidence += Evidence.Structure(
                    oldPath = path(oldSide, match.old),
                    newPath = path(newSide, match.new),
                    oldKind = match.old.kind,
                    newKind = match.new.kind,
                )
            }
            if (RelationshipKind.RENAME in kinds || RelationshipKind.MOVE in kinds) {
                evidence += Evidence.Position(oldSide.position(match.old), newSide.position(match.new))
            }
            relationships += Relationship(
                kinds = kinds,
                old = listOf(RelationshipEndpoint(declarationSubject(match.old), match.old.range)),
                new = listOf(RelationshipEndpoint(declarationSubject(match.new), match.new.range)),
                confidence = renameScore?.let(policy::classify) ?: Confidence.HIGH,
                evidence = evidence.sortedBy { EvidenceOrder.of(it) },
            )
        }
        for (tie in lowTies) {
            relationships += Relationship(
                kinds = EnumSet.of(RelationshipKind.RENAME),
                old = listOf(RelationshipEndpoint(declarationSubject(tie.old), tie.old.range)),
                new = listOf(RelationshipEndpoint(declarationSubject(tie.new), tie.new.range)),
                confidence = Confidence.LOW,
                evidence = listOf(Evidence.Name(tie.old.name, tie.new.name), Evidence.Score(tie.score)),
            )
        }
        return relationships
    }

    /** A declaration's container path on its side, as `class Order / method total(int)`. */
    private fun path(side: Side, declaration: Declaration): String =
        side.path(declaration).joinToString(" / ") { container ->
            container.kind.name.lowercase().replace('_', ' ') + (if (container.name.isEmpty()) "" else " " + container.name)
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

    /**
     * How alike two declarations are (relationships/03, `Score for rename candidates` and `Score for overloads`): the
     * Dice coefficient of their bags of adjacent token pairs, their character-pair names, their parameter types, their
     * nearest matched siblings and their order.
     */
    private class Similarity(
        private val oldSide: Side,
        private val newSide: Side,
        private val cancellation: Cancellation,
    ) {
        private val bigrams = IdentityHashMap<Declaration, Bigrams>()

        /**
         * Pairs [olds] with [news] over the whole group by the overload score, where the score is at least
         * [OVERLOAD_THRESHOLD] and beats every rival by [ASSIGNMENT_MARGIN]. The names are equal in such a group, so
         * equal totals go to the old side's order, then the new side's (R11). A group with more than
         * [MAX_SIMILARITY_PAIRS] options is left unpaired (R12).
         */
        fun overloadPairs(olds: List<Declaration>, news: List<Declaration>): List<Pair<Declaration, Declaration>> {
            if (olds.isEmpty() || news.isEmpty() || olds.size.toLong() * news.size > MAX_SIMILARITY_PAIRS) return emptyList()
            val scores = Array(olds.size) { i ->
                DoubleArray(news.size) { j ->
                    cancellation.checkCanceled()
                    overload(olds[i], news[j])
                }
            }
            return GlobalAssignment.solve(scores, OVERLOAD_THRESHOLD, null, cancellation)
                .map { (o, n) -> olds[o] to news[n] }
        }

        /**
         * Assigns [olds] to [news] by the rename score, where the score is at least [RENAME_THRESHOLD] and passes the
         * assignment's margin or name tie-break. Declarations the assignment leaves unpaired keep their close options as
         * low-confidence ties (R6). [earlier] holds the matches of M1, M2, M3 and M4b, the only siblings the context
         * feature may read.
         */
        fun renamePairs(
            olds: List<Declaration>,
            news: List<Declaration>,
            earlier: Map<Declaration, Declaration>,
        ): RenameOutcome {
            if (olds.isEmpty() || news.isEmpty() || olds.size.toLong() * news.size > MAX_SIMILARITY_PAIRS) {
                return RenameOutcome(emptyList(), emptyList(), emptyList())
            }
            // A body that another declaration on the same side in this group repeats cannot identify its counterpart.
            val oldBags = olds.map { bigrams(it).bag }
            val newBags = news.map { bigrams(it).bag }
            val oldBagCounts = HashMap<Map<String, Int>, Int>()
            val newBagCounts = HashMap<Map<String, Int>, Int>()
            for (bag in oldBags) oldBagCounts.merge(bag, 1, Int::plus)
            for (bag in newBags) newBagCounts.merge(bag, 1, Int::plus)
            val duplicateOld = BooleanArray(olds.size) { (oldBagCounts[oldBags[it]] ?: 0) > 1 }
            val duplicateNew = BooleanArray(news.size) { (newBagCounts[newBags[it]] ?: 0) > 1 }
            val scores = Array(olds.size) { i ->
                DoubleArray(news.size) { j ->
                    cancellation.checkCanceled()
                    val content = content(olds[i], news[j])
                    val name = name(olds[i], news[j])
                    val parameters = dice(parameters(olds[i].signature), parameters(news[j].signature))
                    val context = if (sameNeighbour(olds[i], news[j], earlier)) 1.0 else 0.0
                    val order = order(olds[i], news[j])
                    if (duplicateOld[i] || duplicateNew[j]) {
                        // The same body on this side makes content unable to identify this declaration (R7): the
                        // other features are renormalised to sum to 1.
                        val other = WEIGHTS[1] + WEIGHTS[2] + WEIGHTS[3] + WEIGHTS[4]
                        (WEIGHTS[1] * name + WEIGHTS[2] * parameters + WEIGHTS[3] * context + WEIGHTS[4] * order) / other
                    } else {
                        WEIGHTS[0] * content + WEIGHTS[1] * name + WEIGHTS[2] * parameters +
                            WEIGHTS[3] * context + WEIGHTS[4] * order
                    }
                }
            }
            val names = Array(olds.size) { i -> DoubleArray(news.size) { j -> name(olds[i], news[j]) } }
            val partner = GlobalAssignment.solve(scores, RENAME_THRESHOLD, names, cancellation)
            val pairedNew = BooleanArray(news.size)
            val pairedOld = BooleanArray(olds.size)
            for ((o, n) in partner) {
                pairedOld[o] = true
                pairedNew[n] = true
            }
            val ties = LinkedHashSet<Pair<Int, Int>>()
            val transposed = transpose(scores)
            for (i in 0 until olds.size) {
                if (pairedOld[i]) continue
                addTies(i, scores, pairedNew, ties, oldSide = true)
            }
            for (j in 0 until news.size) {
                if (pairedNew[j]) continue
                addTies(j, transposed, pairedOld, ties, oldSide = false)
            }
            return RenameOutcome(
                pairs = partner.map { (o, n) -> olds[o] to news[n] },
                scores = partner.map { (o, n) -> scores[o][n] },
                ties = ties.map { (old, new) -> LowTie(olds[old], news[new], scores[old][new]) },
            )
        }

        /**
         * Adds the edges from one unpaired declaration to the other side's unpaired ones that are close to its best.
         * [scores] is indexed by this declaration first; [oldSide] says whether that index is an old or a new one.
         */
        private fun addTies(
            index: Int,
            scores: Array<DoubleArray>,
            otherPaired: BooleanArray,
            ties: MutableSet<Pair<Int, Int>>,
            oldSide: Boolean,
        ) {
            val otherCount = scores[index].size
            var best = Double.NEGATIVE_INFINITY
            for (other in 0 until otherCount) {
                if (otherPaired[other]) continue
                best = maxOf(best, scores[index][other])
            }
            if (best < RENAME_THRESHOLD) return
            for (other in 0 until otherCount) {
                if (otherPaired[other]) continue
                val score = scores[index][other]
                if (score >= RENAME_THRESHOLD && score >= best - ASSIGNMENT_MARGIN) {
                    ties += if (oldSide) index to other else other to index
                }
            }
        }

        private fun transpose(scores: Array<DoubleArray>): Array<DoubleArray> =
            Array(scores[0].size) { j -> DoubleArray(scores.size) { i -> scores[i][j] } }

        /** Overloads: the parameter types (as a bag) and the content weigh the same, as before this feature (R8). */
        fun overload(o: Declaration, n: Declaration): Double =
            (dice(parameters(o.signature), parameters(n.signature)) + content(o, n)) / 2

        /**
         * How alike two names are: the Dice coefficient of their bags of adjacent character pairs, ignoring case, so
         * `testShowOwner` and `showOwner` are close. It is a score feature and also the tie-break for a near-tie.
         */
        fun name(o: Declaration, n: Declaration): Double {
            val a = characterPairs(o.name)
            val b = characterPairs(n.name)
            return if (a.isEmpty() || b.isEmpty()) 0.0 else dice(a, b)
        }

        private fun characterPairs(name: String): Map<String, Int> {
            val lower = name.lowercase()
            val bag = HashMap<String, Int>()
            for (i in 1 until lower.length) bag.merge(lower.substring(i - 1, i + 1), 1, Int::plus)
            return bag
        }

        /** 0 when either side is too small for its content to tell it apart, such as an empty method. */
        private fun content(o: Declaration, n: Declaration): Double {
            val a = bigrams(o)
            val b = bigrams(n)
            // A key's value is its whole content, so even a short one, such as `1`, tells two keys apart; code needs
            // more than a few tokens before content is meaningful.
            val tooSmall = o.kind != DeclarationKind.KEY && n.kind != DeclarationKind.KEY &&
                (a.size < MIN_CONTENT || b.size < MIN_CONTENT)
            return if (tooSmall || a.bag.isEmpty() || b.bag.isEmpty()) 0.0 else dice(a.bag, b.bag)
        }

        private class Bigrams(val bag: Map<String, Int>, val size: Int)

        /** Adjacent pairs of the tokens other than whitespace and separators, children included, with the name masked. */
        private fun bigrams(declaration: Declaration): Bigrams = bigrams.getOrPut(declaration) {
            val texts = mutableListOf<String>()
            fun visit(d: Declaration) {
                cancellation.checkCanceled()
                val nameRange = d.nameRange
                d.ownTokens.forEachIndexed { index, token ->
                    if (token.kind == TokenKind.WHITESPACE || token.kind == TokenKind.SEPARATOR) return@forEachIndexed
                    val start = d.ownTokenStarts.getOrNull(index)
                    val ownName = start != null && nameRange != null && start >= nameRange.start && start < nameRange.end
                    texts += if (ownName || token.text == declaration.name) NAME else token.text
                }
                d.children.forEach(::visit)
            }
            visit(declaration)
            val bag = HashMap<String, Int>()
            for (i in 1 until texts.size) bag.merge(texts[i - 1] + SEPARATOR + texts[i], 1, Int::plus)
            Bigrams(bag, texts.count { it != NAME })
        }

        /** The parameter types of a signature, split at top-level commas, as a bag. */
        private fun parameters(signature: String?): Map<String, Int> {
            val bag = HashMap<String, Int>()
            if (signature.isNullOrEmpty()) return bag
            var depth = 0
            var start = 0
            for ((i, c) in signature.withIndex()) {
                when (c) {
                    '<', '(', '[' -> depth++
                    '>', ')', ']' -> depth--
                    ',' -> if (depth == 0) {
                        bag.merge(signature.substring(start, i), 1, Int::plus)
                        start = i + 1
                    }
                }
            }
            bag.merge(signature.substring(start), 1, Int::plus)
            return bag
        }

        private fun dice(a: Map<String, Int>, b: Map<String, Int>): Double {
            val total = a.values.sum() + b.values.sum()
            if (total == 0) return 1.0
            val (small, large) = if (a.size <= b.size) a to b else b to a
            val shared = small.entries.sumOf { (key, count) -> minOf(count, large[key] ?: 0) }
            return 2.0 * shared / total
        }

        /**
         * 1 when the nearest sibling before, or the nearest sibling after, is one of [earlier]'s pairs on both sides,
         * 0 otherwise (relationships/03, `Score for rename candidates`). The containers differ, so only siblings count.
         */
        private fun sameNeighbour(o: Declaration, n: Declaration, earlier: Map<Declaration, Declaration>): Boolean {
            val oldSiblings = oldSide.siblings(o)
            val newSiblings = newSide.siblings(n)
            val oldPosition = oldSide.position(o)
            val newPosition = newSide.position(n)
            val before = oldPosition > 0 && newPosition > 0 &&
                earlier[oldSiblings[oldPosition - 1]] === newSiblings[newPosition - 1]
            if (before) return true
            return oldPosition + 1 < oldSiblings.size && newPosition + 1 < newSiblings.size &&
                earlier[oldSiblings[oldPosition + 1]] === newSiblings[newPosition + 1]
        }

        /** 1 minus the normalised distance between the two positions among siblings (relationships/03, `Order`). */
        private fun order(o: Declaration, n: Declaration): Double {
            val distance = kotlin.math.abs(oldSide.position(o) - newSide.position(n))
            val count = maxOf(oldSide.siblings(o).size, newSide.siblings(n).size)
            return if (count == 0) 1.0 else 1.0 - minOf(1.0, distance.toDouble() / count)
        }

        private companion object {
            const val NAME = "\u0000name"
            const val SEPARATOR = "\u0001"

            /** Declarations with fewer tokens than this, not counting their name, are not compared by content. */
            const val MIN_CONTENT = 6

            /**
             * Content, name, parameter types, context and order (relationships/03, `Score for rename candidates`),
             * tuned on the tuning split only (R20). Content stays high because the rename floor 0.7 was calibrated to
             * it; the other four features together add up to 0.18.
             */
            val WEIGHTS: DoubleArray = doubleArrayOf(0.83, 0.03, 0.05, 0.03, 0.06)
        }
    }

    /** The tokens of [declaration] and its children other than whitespace, for telling same-key declarations apart. */
    private fun content(declaration: Declaration): List<Any> =
        declaration.ownTokens.filter { it.kind != TokenKind.WHITESPACE && it.kind != TokenKind.SEPARATOR }
            .map { it.type to it.text } + declaration.children.map(::content)

    /**
     * One element of a path: a container's kind and name. A type's kind stands for its whole family (rule M4b), so the
     * members of a class turned into a trait keep their path; two types of one name cannot share a container anyway.
     */
    private data class Container(val kind: DeclarationKind, val name: String)

    /** The kinds a declaration can change between and stay one declaration (rule M4b); null for the others. */
    private fun family(kind: DeclarationKind): Set<DeclarationKind>? = KIND_FAMILIES.firstOrNull { kind in it }

    /**
     * Types, and members that hold or compute a value (a Scala `def` turned into a `val`). An object is no type here:
     * a class and its companion object share a name.
     */
    private val KIND_FAMILIES = listOf(
        setOf(
            DeclarationKind.CLASS, DeclarationKind.TRAIT, DeclarationKind.INTERFACE, DeclarationKind.ENUM,
            DeclarationKind.RECORD, DeclarationKind.ANNOTATION_TYPE,
        ),
        setOf(DeclarationKind.METHOD, DeclarationKind.FUNCTION, DeclarationKind.FIELD, DeclarationKind.PROPERTY),
    )

    /** A matching key; a null [path] or [signature] is left out of the key. */
    private data class Key(val kind: DeclarationKind, val path: List<Container>?, val name: String, val signature: String?)

    private class Side(val tree: DeclarationTree) {
        private val parents = IdentityHashMap<Declaration, Declaration>()
        private val paths = IdentityHashMap<Declaration, List<Container>>()
        private val siblingLists = IdentityHashMap<Declaration, List<Declaration>>()
        private val positions = IdentityHashMap<Declaration, Int>()

        init {
            fun visit(declaration: Declaration, path: List<Container>, siblings: List<Declaration>, position: Int) {
                paths[declaration] = path
                siblingLists[declaration] = siblings
                positions[declaration] = position
                val childPath = path + Container(family(declaration.kind)?.first() ?: declaration.kind, declaration.name)
                declaration.children.forEachIndexed { index, child ->
                    parents[child] = declaration
                    visit(child, childPath, declaration.children, index)
                }
            }
            tree.topLevel.forEachIndexed { index, declaration -> visit(declaration, emptyList(), tree.topLevel, index) }
        }

        fun parent(declaration: Declaration): Declaration? = parents[declaration]

        fun path(declaration: Declaration): List<Container> = paths.getValue(declaration)

        /** The index of [declaration] among its siblings on this side, or its top-level index. */
        fun position(declaration: Declaration): Int = positions.getValue(declaration)

        /** The siblings of [declaration] on this side, itself included, or the top level. */
        fun siblings(declaration: Declaration): List<Declaration> = siblingLists.getValue(declaration)

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

    /** Overloads pair from this similarity of parameter types and content up. */
    private const val OVERLOAD_THRESHOLD = 0.5

    /** Removed and added declarations pair as rename candidates from this content similarity up. */
    private const val RENAME_THRESHOLD = 0.7

    /** Groups with more possible pairs than this are not compared, to keep matching fast. */
    private const val MAX_SIMILARITY_PAIRS = 10_000L
}
