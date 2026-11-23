package io.github.agadek.smartdiff.engine.declarations.psi

import io.github.agadek.smartdiff.engine.api.TextRange
import java.util.BitSet

/*
 * The pure parts of the fine-grained differencing validation (declarations/10 R21): reading Difftastic's and GumTree's
 * JSON output, mapping their offsets to character offsets, and the character-set arithmetic. [FineGrainedBaselinesTest]
 * runs the tools; these parts are tested on tiny inputs without them.
 */

/** Characters marked on each side of a compared file, as character (UTF-16) offsets into the texts. */
class BaselineMarks(val old: BitSet = BitSet(), val new: BitSet = BitSet())

/** A minimal JSON reader: objects become maps, arrays lists, numbers doubles. Enough for the tools' output. */
object MiniJson {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipSpace()
        require(reader.at == text.length) { "trailing text at ${reader.at}" }
        return value
    }

    private class Reader(val text: String) {
        var at = 0

        fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        fun value(): Any? {
            skipSpace()
            require(at < text.length) { "unexpected end" }
            return when (val c = text[at]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $at")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(text.startsWith(word, at)) { "expected $word at $at" }
            at += word.length
            return value
        }

        private fun number(): Double {
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
            return text.substring(start, at).toDouble()
        }

        private fun string(): String {
            at++ // opening quote
            val out = StringBuilder()
            while (true) {
                require(at < text.length) { "unterminated string" }
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        when (val e = text[at++]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'u' -> {
                                out.append(text.substring(at, at + 4).toInt(16).toChar())
                                at += 4
                            }
                            else -> out.append(e)
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun array(): List<Any?> {
            at++
            val out = mutableListOf<Any?>()
            skipSpace()
            if (text[at] == ']') { at++; return out }
            while (true) {
                out += value()
                skipSpace()
                when (text[at++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> error("expected , or ] at ${at - 1}")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            at++
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (text[at] == '}') { at++; return out }
            while (true) {
                skipSpace()
                val key = string()
                skipSpace()
                require(text[at++] == ':') { "expected : at ${at - 1}" }
                out[key] = value()
                skipSpace()
                when (text[at++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> error("expected , or } at ${at - 1}")
                }
            }
        }
    }
}

/** Maps between a text's character (UTF-16) offsets, its lines and its UTF-8 byte offsets. */
class TextOffsets(private val text: String) {
    private val lineStarts: IntArray = buildList {
        add(0)
        text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
    }.toIntArray()

    /** The character offset of 0-based [line]'s start; past the last line, the text's length. */
    fun lineStart(line: Int): Int = if (line < lineStarts.size) lineStarts[line] else text.length

    /** The character offset reached from character offset [from] after [bytes] UTF-8 bytes, stopping at the text's end. */
    fun advanceBytes(from: Int, bytes: Int): Int {
        var offset = from
        var left = bytes
        while (left > 0 && offset < text.length) {
            val codePoint = text.codePointAt(offset)
            left -= utf8Length(codePoint)
            offset += Character.charCount(codePoint)
        }
        return offset
    }

    /** The character offset of the byte [column] of 0-based [line], as Difftastic reports positions. */
    fun lineColumn(line: Int, column: Int): Int = advanceBytes(lineStart(line), column).coerceAtMost(lineEnd(line))

    private fun lineEnd(line: Int): Int = if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else text.length

    private fun utf8Length(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }
}

/**
 * Difftastic's `--display json` output for one file (`DFT_UNSTABLE=yes`): the characters of every change entry, on
 * each side. Lines are 0-based and columns are UTF-8 byte offsets inside the line.
 */
object DifftasticJson {
    /** [language] is what Difftastic parsed the file as; it starts with `Text` when Difftastic fell back to a line diff. */
    class Result(val marks: BaselineMarks, val language: String, val status: String) {
        /** Whether Difftastic compared syntax trees, not lines, so its marks are tokens. */
        val structural: Boolean get() = !language.startsWith("Text")
    }

    fun parse(json: String, old: String, new: String): Result {
        val root = MiniJson.parse(json) as Map<*, *>
        val marks = BaselineMarks()
        val oldOffsets = TextOffsets(old)
        val newOffsets = TextOffsets(new)
        for (chunk in root["chunks"] as? List<*> ?: emptyList<Any>()) {
            for (entry in chunk as List<*>) {
                entry as Map<*, *>
                mark(entry["lhs"], oldOffsets, marks.old)
                mark(entry["rhs"], newOffsets, marks.new)
            }
        }
        return Result(marks, root["language"] as? String ?: "", root["status"] as? String ?: "")
    }

    private fun mark(side: Any?, offsets: TextOffsets, into: BitSet) {
        side as? Map<*, *> ?: return
        val line = (side["line_number"] as Double).toInt()
        for (change in side["changes"] as? List<*> ?: emptyList<Any>()) {
            change as Map<*, *>
            if (change["highlight"] == "none") continue
            val start = offsets.lineColumn(line, (change["start"] as Double).toInt())
            val end = offsets.lineColumn(line, (change["end"] as Double).toInt())
            if (start < end) into.set(start, end)
        }
    }
}

/**
 * GumTree's `textdiff -f JSON` output: the characters of every node an action touches. An `insert-node`,
 * `delete-node` or `update-node` marks the node's own characters, its range minus the ranges of the other nodes the
 * output names inside it on that side; an `insert-tree`, `delete-tree` or `move-tree` marks the whole subtree. An
 * update or a move also marks the node's match on the new side.
 *
 * GumTree prints a node as `type: label [start,end]` or `type [start,end]`. The start is a character offset; with
 * [endIsByteLength], as the tree-sitter generators print it, `end - start` is the node's length in UTF-8 bytes,
 * otherwise, as the JDT generator prints it, `end` is a character offset too.
 */
object GumTreeJson {
    private val RANGE = Regex("""\[(\d+),(\d+)]\s*$""")

    fun parse(json: String, old: String, new: String, endIsByteLength: Boolean): BaselineMarks {
        val root = MiniJson.parse(json) as Map<*, *>
        val oldOffsets = TextOffsets(old)
        val newOffsets = TextOffsets(new)
        fun range(node: String, offsets: TextOffsets): TextRange {
            val match = requireNotNull(RANGE.find(node)) { "no range in '$node'" }
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toInt()
            return if (endIsByteLength) TextRange(start, offsets.advanceBytes(start, end - start)) else TextRange(start, end)
        }
        val matches = (root["matches"] as? List<*> ?: emptyList<Any>()).map { it as Map<*, *> }
        val toNew = matches.associate { it["src"] as String to it["dest"] as String }
        val actions = (root["actions"] as? List<*> ?: emptyList<Any>()).map { it as Map<*, *> }
        val oldNodes = (matches.map { it["src"] as String } + actions.filter { side(it) == Side.OLD }.map { it["tree"] as String })
            .map { range(it, oldOffsets) }.distinct()
        val newNodes = (matches.map { it["dest"] as String } + actions.filter { side(it) == Side.NEW }.map { it["tree"] as String })
            .map { range(it, newOffsets) }.distinct()
        val marks = BaselineMarks()
        fun own(range: TextRange, nodes: List<TextRange>, into: BitSet) {
            val set = BitSet().apply { set(range.start, range.end) }
            nodes.filter { it != range && it.start >= range.start && it.end <= range.end }.forEach { set.clear(it.start, it.end) }
            into.or(set)
        }
        fun whole(range: TextRange, into: BitSet) = into.set(range.start, range.end)
        for (action in actions) {
            val tree = action["tree"] as String
            when (action["action"]) {
                "insert-node" -> own(range(tree, newOffsets), newNodes, marks.new)
                "insert-tree" -> whole(range(tree, newOffsets), marks.new)
                "delete-node" -> own(range(tree, oldOffsets), oldNodes, marks.old)
                "delete-tree" -> whole(range(tree, oldOffsets), marks.old)
                "update-node" -> {
                    own(range(tree, oldOffsets), oldNodes, marks.old)
                    toNew[tree]?.let { own(range(it, newOffsets), newNodes, marks.new) }
                }
                "move-tree" -> {
                    whole(range(tree, oldOffsets), marks.old)
                    toNew[tree]?.let { whole(range(it, newOffsets), marks.new) }
                }
            }
        }
        return marks
    }

    private enum class Side { OLD, NEW }

    private fun side(action: Map<*, *>): Side? = when (action["action"]) {
        "insert-node", "insert-tree" -> Side.NEW
        "delete-node", "delete-tree" -> Side.OLD
        else -> null
    }
}

/** Character-set arithmetic over one side of a declaration, counting only non-whitespace characters. */
object CharSets {
    /** The characters of [range] outside every one of [children]. */
    fun own(range: TextRange, children: List<TextRange>): BitSet {
        val set = BitSet().apply { set(range.start, range.end) }
        children.forEach { set.clear(it.start, it.end) }
        return set
    }

    /** The characters of [ranges] together. */
    fun of(ranges: List<TextRange>): BitSet = BitSet().apply { ranges.forEach { set(it.start, it.end) } }

    /** The non-whitespace characters of [text] in [set] and in [within]. */
    fun restrict(set: BitSet, within: BitSet, text: CharSequence): BitSet {
        val out = set.clone() as BitSet
        out.and(within)
        var i = out.nextSetBit(0)
        while (i >= 0) {
            if (i >= text.length || text[i].isWhitespace()) out.clear(i)
            i = out.nextSetBit(i + 1)
        }
        return out
    }

    /** The characters in [baselines] all, and not in [ours]: what every baseline marks changed and our runs miss. */
    fun missed(ours: BitSet, baselines: List<BitSet>): BitSet {
        if (baselines.isEmpty()) return BitSet()
        val out = baselines.first().clone() as BitSet
        baselines.drop(1).forEach { out.and(it) }
        out.andNot(ours)
        return out
    }

    /**
     * The non-whitespace characters of [text] in [own] and outside [runs], in order. When every textual difference of a
     * declaration lies in its changed runs, this text is the same on both sides: a difference here is an edit outside
     * the runs (declarations/10 R22). Each whole-word occurrence of one of [names] outside the runs is written as `§`,
     * so a rename candidate, whose old name the comparison reads as its new name, compares equal when both names are
     * given on both sides.
     */
    fun outside(text: CharSequence, own: BitSet, runs: BitSet, names: List<String> = emptyList()): String {
        val named = HashMap<Int, Int>()
        for (name in names.filter { it.isNotEmpty() }) {
            Regex("(?<![\\w$])" + Regex.escape(name) + "(?![\\w$])").findAll(text).forEach { named[it.range.first] = it.range.last + 1 }
        }
        val out = StringBuilder()
        var i = own.nextSetBit(0)
        while (i >= 0 && i < text.length) {
            val end = named[i]
            if (end != null && !runs[i]) {
                out.append('§')
                i = own.nextSetBit(end)
                continue
            }
            if (!runs[i] && !text[i].isWhitespace()) out.append(text[i])
            i = own.nextSetBit(i + 1)
        }
        return out.toString()
    }

    /** The median of [values], 0 for none. */
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    /**
     * The lines of [text] in [lines] (the own range) that hold a character of any of [marks], each followed by one row
     * per mark set with `^` under its marked characters, for the report's snippets. Tabs print as one space so the
     * markers line up. At most [limit] lines.
     */
    fun snippet(text: String, own: BitSet, marks: List<Pair<String, BitSet>>, limit: Int = 40): String {
        val offsets = TextOffsets(text)
        val any = BitSet().apply { marks.forEach { or(it.second) } }
        any.and(own)
        val out = StringBuilder()
        var line = 0
        var shown = 0
        while (offsets.lineStart(line) < text.length || line == 0) {
            val start = offsets.lineStart(line)
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val next = any.nextSetBit(start)
            if (next in start until end) {
                if (shown == limit) {
                    out.append("      …\n")
                    break
                }
                shown++
                out.append("%5d| ".format(line + 1)).append(text.substring(start, end).replace('\t', ' ')).append('\n')
                for ((name, set) in marks) {
                    val row = StringBuilder()
                    for (i in start until end) row.append(if (set[i] && own[i]) '^' else ' ')
                    if (row.isNotBlank()) out.append("%5s| ".format(name)).append(row.trimEnd()).append('\n')
                }
            }
            if (end >= text.length) break
            line++
        }
        return out.toString()
    }
}
