package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
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
 * containers. Rules M1 to M5 run in order, each over the declarations the earlier rules left unmatched. M1 to M3 pair
 * declarations whose key occurs exactly once on each side, except that M1 first pairs same-key declarations with the
 * same content and M3 pairs the rest of a repeated path and name by similarity. M4 marks what is still ambiguous, M4b
 * pairs a declaration that changed kind within its family, and M5 pairs removed and added declarations with similar
 * content as rename candidates. Paths use kind families, so the members of a class turned into a trait keep their path. Declarations are processed in
 * old-side source order, and ties in similarity go to the closest position, which makes the result deterministic.
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

        val similarity = Similarity(cancellation)

        // M3, repeated path and name: overloads whose signatures all changed pair with the most similar one. What
        // remains of the group may then be unique and pair as above.
        val newOverloads = newSide.groupUnmatched(newMatched, pathAndName, cancellation)
        for ((k, olds) in oldSide.groupUnmatched(pairs.keys, pathAndName, cancellation)) {
            val news = newOverloads[k] ?: continue
            for ((o, n) in similarity.pairs(olds, news, OVERLOAD_THRESHOLD, similarity::overload)) {
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
        val containers = ArrayDeque<Pair<Declaration?, Declaration?>>()
        containers += null to null
        for (o in old.all) pairs[o]?.first?.let { n -> if (o.children.isNotEmpty() && n.children.isNotEmpty()) containers += o to n }
        while (containers.isNotEmpty()) {
            val (oldContainer, newContainer) = containers.removeFirst()
            val removed = (oldContainer?.children ?: old.topLevel).filter { it !in pairs && it !in uncertainOld }
            val added = (newContainer?.children ?: new.topLevel).filter { it !in newMatched && it !in uncertainNew }
            for ((kind, olds) in removed.groupBy { it.kind }) {
                val news = added.filter { it.kind == kind }
                for ((o, n) in similarity.pairs(olds, news, RENAME_THRESHOLD, similarity::rename, similarity::name)) {
                    val followsContainer = kind == DeclarationKind.CONSTRUCTOR &&
                        o.name == oldContainer?.name && n.name == newContainer?.name
                    pairs[o] = n to when {
                        followsContainer -> MatchRule.CONTAINER_RENAMED
                        o.name != n.name -> MatchRule.RENAME_CANDIDATE
                        o.signature != n.signature -> MatchRule.SIGNATURE_CHANGED
                        else -> MatchRule.IDENTITY
                    }
                    newMatched += n
                    if (o.children.isNotEmpty() && n.children.isNotEmpty()) containers += o to n
                }
            }
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

    /**
     * How alike two declarations are, from 0 to 1: the Dice coefficient of their bags of adjacent token pairs, which
     * is cheap for large declarations and still sensitive to order.
     */
    private class Similarity(private val cancellation: Cancellation) {
        private val bigrams = IdentityHashMap<Declaration, Bigrams>()

        /**
         * Pairs [olds] with [news], most similar first, where the similarity is at least [threshold] and beats every
         * other unpaired option of both declarations by [MARGIN]: a close second leaves both unpaired, since either
         * could be the counterpart, unless [tiebreak] tells them apart: it must be at least [TIEBREAK_MIN] for the pair
         * and beat every close rival by [TIEBREAK_MARGIN]. Groups with more than [MAX_SIMILARITY_PAIRS] options are left
         * unpaired.
         */
        fun pairs(
            olds: List<Declaration>,
            news: List<Declaration>,
            threshold: Double,
            similarity: (Declaration, Declaration) -> Double,
            tiebreak: ((Declaration, Declaration) -> Double)? = null,
        ): List<Pair<Declaration, Declaration>> {
            if (olds.isEmpty() || news.isEmpty() || olds.size.toLong() * news.size > MAX_SIMILARITY_PAIRS) return emptyList()
            val scores = Array(olds.size) { i ->
                DoubleArray(news.size) { j ->
                    cancellation.checkCanceled()
                    similarity(olds[i], news[j])
                }
            }
            class Option(val o: Int, val n: Int, val score: Double)
            val options = buildList {
                for (i in olds.indices) for (j in news.indices) if (scores[i][j] >= threshold) add(Option(i, j, scores[i][j]))
            }.sortedWith(compareByDescending<Option> { it.score }.thenBy { it.o }.thenBy { it.n })
            val partner = IntArray(olds.size) { -1 }
            val newTaken = BooleanArray(news.size)
            for (option in options) {
                if (partner[option.o] >= 0 || newTaken[option.n]) continue
                val close = option.score - MARGIN
                val rivals = olds.indices.filter { it != option.o && partner[it] < 0 && scores[it][option.n] > close }
                    .map { olds[it] to news[option.n] } +
                    news.indices.filter { it != option.n && !newTaken[it] && scores[option.o][it] > close }
                        .map { olds[option.o] to news[it] }
                if (rivals.isNotEmpty()) {
                    if (tiebreak == null) continue
                    val own = tiebreak(olds[option.o], news[option.n])
                    if (own < TIEBREAK_MIN || rivals.any { (o, n) -> tiebreak(o, n) > own - TIEBREAK_MARGIN }) continue
                }
                partner[option.o] = option.n
                newTaken[option.n] = true
            }
            return olds.indices.filter { partner[it] >= 0 }.map { olds[it] to news[partner[it]] }
        }

        /** Overloads: the parameter types (as a bag) and the content weigh the same. */
        fun overload(o: Declaration, n: Declaration): Double =
            (dice(parameters(o.signature), parameters(n.signature)) + content(o, n)) / 2

        /** Renames: the content, with each side's own name taken out, so a rename alone leaves them identical. */
        fun rename(o: Declaration, n: Declaration): Double = content(o, n)

        /**
         * How alike two names are: the Dice coefficient of their bags of adjacent character pairs, ignoring case, so
         * `testShowOwner` and `showOwner` are close. Only breaks ties between declarations with similar content.
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
            return if (a.size < MIN_CONTENT || b.size < MIN_CONTENT) 0.0 else dice(a.bag, b.bag)
        }

        private class Bigrams(val bag: Map<String, Int>, val size: Int)

        /** Adjacent pairs of the tokens other than whitespace, children included, with the declaration's name masked. */
        private fun bigrams(declaration: Declaration): Bigrams = bigrams.getOrPut(declaration) {
            val texts = mutableListOf<String>()
            fun visit(d: Declaration) {
                cancellation.checkCanceled()
                d.ownTokens.filter { it.kind != TokenKind.WHITESPACE }.mapTo(texts) { if (it.text == declaration.name) NAME else it.text }
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

        private companion object {
            const val NAME = "\u0000name"
            const val SEPARATOR = "\u0001"

            /** Declarations with fewer tokens than this, not counting their name, are not compared by content. */
            const val MIN_CONTENT = 6

            /** How much better than every rival a pair must be. */
            const val MARGIN = 0.1

            /** How alike the names of a pair with close rivals must be, and by how much more than the rivals' names. */
            const val TIEBREAK_MIN = 0.5
            const val TIEBREAK_MARGIN = 0.2
        }
    }

    /** The tokens of [declaration] and its children other than whitespace, for telling same-key declarations apart. */
    private fun content(declaration: Declaration): List<Any> =
        declaration.ownTokens.filter { it.kind != TokenKind.WHITESPACE }.map { it.type to it.text } + declaration.children.map(::content)

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

        init {
            fun visit(declaration: Declaration, path: List<Container>) {
                paths[declaration] = path
                val childPath = path + Container(family(declaration.kind)?.first() ?: declaration.kind, declaration.name)
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

    /** Overloads pair from this similarity of parameter types and content up. */
    private const val OVERLOAD_THRESHOLD = 0.5

    /** Removed and added declarations pair as rename candidates from this content similarity up. */
    private const val RENAME_THRESHOLD = 0.7

    /** Groups with more possible pairs than this are not compared, to keep matching fast. */
    private const val MAX_SIMILARITY_PAIRS = 10_000L
}
