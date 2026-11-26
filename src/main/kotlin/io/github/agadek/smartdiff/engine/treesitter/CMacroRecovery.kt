package io.github.agadek.smartdiff.engine.treesitter

import io.github.agadek.smartdiff.engine.api.TextRange

/**
 * One side's text with the macro patterns of engines/06 read as blank space: [text] has the length and line breaks of
 * the original, so the grammar's offsets are the original's (engines/06-c-cpp-macro-recovery-details §2).
 */
internal class MacroRecovery(
    /** The text the grammar parses: the original with every masked character but line breaks written as a space. */
    val text: String,
    /** The masked ranges, in the original's offsets, sorted by start. */
    val masks: List<MacroMask>,
    /** Line breaks that end or continue a masked directive line, which stay content (engines/06 R18). */
    val lineBreaks: Set<Int>,
    /**
     * The string and character literals and the comments inside masked ranges, sorted by start: each stays one token,
     * so respacing inside it is a change, as outside masks (R5).
     */
    val literals: List<MaskedLiteral> = emptyList(),
) {
    /** The masked ranges a declaration's range starts at when they come directly before it (R6), sorted. */
    val prefixes: List<TextRange> = masks.filter { it.prefix }.map { it.range }

    /** The last of [prefixes] that ends at or before [offset], found by binary search; null when none does. */
    fun prefixBefore(offset: Int): TextRange? {
        var low = 0
        var high = prefixes.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (prefixes[mid].end <= offset) low = mid + 1 else high = mid
        }
        return prefixes.getOrNull(low - 1)
    }
}

/** A literal or comment inside a masked range; [comment] is true for a comment. */
internal class MaskedLiteral(val range: TextRange, val comment: Boolean)

internal class MacroMask(val range: TextRange, val kind: MacroPattern, val prefix: Boolean = kind.prefix)

/** The patterns of engines/06 R7 to R17; [prefix] marks those that belong to the declaration after them (R6). */
internal enum class MacroPattern(val prefix: Boolean = false) {
    INCLUDE_GUARD,
    EXTERN_C,
    ATTRIBUTE(prefix = true),
    CLASS_ATTRIBUTE,
    TRAILING_ATTRIBUTE,
    MACRO_LINE,
    BLOCK_MACRO,
    TEMPLATE_PARAMETER,
    IF_ATTRIBUTE,
    INCLUDE_ARGUMENT,
    BODY_CONDITIONAL,
    SPECIFIER_CONDITIONAL(prefix = true),
}

/**
 * Macro recovery for C and C++ (engines/06): a lexer pass over one side's text and the pattern rules of
 * engines/06-c-cpp-macro-recovery-details §3. It reads no other file and expands nothing (R4).
 */
internal object CMacroRecovery {

    /** The recovered text of [text], or null when no pattern matches. */
    fun recover(text: CharSequence): MacroRecovery? {
        val tokens = lex(text)
        val masks = Rules(text, tokens).masks()
        if (masks.isEmpty()) return null
        val sorted = masks.sortedBy { it.range.start }
        val chars = CharArray(text.length) { text[it] }
        for (mask in sorted) {
            for (at in mask.range.start until mask.range.end) if (chars[at] != '\n' && chars[at] != '\r') chars[at] = ' '
        }
        val lineBreaks = HashSet<Int>()
        for (token in tokens) {
            if (token.kind != Kind.DIRECTIVE || sorted.none { token.start >= it.range.start && token.end <= it.range.end }) continue
            for (at in token.start until token.end) if (text[at] == '\n') lineBreaks += at
            if (token.end < text.length && text[token.end] == '\n') lineBreaks += token.end
        }
        return MacroRecovery(String(chars), sorted, lineBreaks, literals(text, sorted))
    }

    /** The literals and comments in [masks], scanned again so those inside masked directive lines are found too. */
    private fun literals(text: CharSequence, masks: List<MacroMask>): List<MaskedLiteral> = buildList {
        for (mask in masks) {
            var i = mask.range.start
            val end = mask.range.end
            while (i < end) {
                val c = text[i]
                val next = when {
                    text.startsWith("//", i) -> text.indexOf('\n', i).let { if (it < 0 || it > end) end else it }.also { add(MaskedLiteral(TextRange(i, it), true)) }
                    text.startsWith("/*", i) -> text.indexOf("*/", i + 2).let { if (it < 0 || it + 2 > end) end else it + 2 }.also { add(MaskedLiteral(TextRange(i, it), true)) }
                    c == '"' || c == '\'' -> minOf(end, skipString(text, i, raw = i > 0 && text[i - 1] == 'R' && c == '"'))
                        .also { add(MaskedLiteral(TextRange(i, it), false)) }
                    else -> i + 1
                }
                i = maxOf(next, i + 1)
            }
        }
    }

    internal enum class Kind { IDENTIFIER, NUMBER, STRING, PUNCTUATION, DIRECTIVE }

    /** One token of the lexer pass; [lineStart] is true when only whitespace or comments come before it on its line. */
    internal class Token(val kind: Kind, val text: String, val start: Int, val end: Int, val lineStart: Boolean)

    private val STRING_PREFIXES = setOf("L", "u", "U", "u8", "R", "LR", "uR", "UR", "u8R")
    private val TWO_CHARACTER = setOf("::", "->", "&&", "||", "==", "!=", "<=", ">=", "++", "--", "<<", ">>")

    /**
     * Identifiers, numbers, string and character literals, punctuation and whole directive lines with their
     * continuations; comments and whitespace are skipped (details §3.1).
     */
    internal fun lex(text: CharSequence): List<Token> {
        val tokens = mutableListOf<Token>()
        val n = text.length
        var i = 0
        var lineStart = true
        fun startsWith(prefix: String, at: Int) = text.startsWith(prefix, at)
        while (i < n) {
            val c = text[i]
            when {
                c == '\n' -> { lineStart = true; i++; continue }
                c.isWhitespace() -> { i++; continue }
                startsWith("//", i) -> { i = text.indexOf('\n', i).let { if (it < 0) n else it }; continue }
                startsWith("/*", i) -> { i = text.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }; continue }
                c == '#' && lineStart -> {
                    var end = i
                    while (true) {
                        val lineEnd = text.indexOf('\n', end)
                        if (lineEnd < 0) { end = n; break }
                        var before = lineEnd - 1
                        if (before >= 0 && text[before] == '\r') before--
                        if (before >= 0 && text[before] == '\\') { end = lineEnd + 1; continue }
                        end = lineEnd
                        break
                    }
                    tokens += Token(Kind.DIRECTIVE, text.substring(i, end), i, end, true)
                    i = end
                    continue
                }
            }
            val start = i
            val first = lineStart
            lineStart = false
            when {
                c.isLetter() || c == '_' -> {
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    val word = text.substring(start, i)
                    if (i < n && (text[i] == '"' || text[i] == '\'') && word in STRING_PREFIXES) {
                        i = skipString(text, i, raw = word.endsWith("R"))
                        tokens += Token(Kind.STRING, text.substring(start, i), start, i, first)
                    } else {
                        tokens += Token(Kind.IDENTIFIER, word, start, i, first)
                    }
                }
                c.isDigit() -> {
                    while (i < n && (text[i].isLetterOrDigit() || text[i] in "._'")) i++
                    tokens += Token(Kind.NUMBER, text.substring(start, i), start, i, first)
                }
                c == '"' || c == '\'' -> {
                    i = skipString(text, i, raw = false)
                    tokens += Token(Kind.STRING, text.substring(start, i), start, i, first)
                }
                else -> {
                    val two = if (i + 1 < n) text.substring(i, i + 2) else ""
                    i += if (two in TWO_CHARACTER) 2 else 1
                    tokens += Token(Kind.PUNCTUATION, text.substring(start, i), start, i, first)
                }
            }
        }
        return tokens
    }

    /** The end of the literal that starts with the quote at [at]; an unclosed one ends at its line's end. */
    private fun skipString(text: CharSequence, at: Int, raw: Boolean): Int {
        val n = text.length
        val quote = text[at]
        if (raw && quote == '"') {
            val open = text.indexOf('(', at)
            if (open < 0) return n
            val close = text.indexOf(")" + text.substring(at + 1, open) + "\"", open)
            return if (close < 0) n else close + open - at + 1
        }
        var i = at + 1
        while (i < n && text[i] != quote && text[i] != '\n') {
            if (text[i] == '\\') i++
            i++
        }
        return minOf(n, i + 1)
    }

    private val CAPITALS = Regex("[A-Z_][A-Z0-9_]*[A-Z0-9]")

    /** Words that start a declaration (details §3.2). */
    private val SPECIFIERS = setOf(
        "static", "inline", "extern", "constexpr", "consteval", "constinit", "void", "int", "char", "short", "long",
        "float", "double", "signed", "unsigned", "bool", "auto", "struct", "class", "union", "enum", "typename",
        "template", "virtual", "explicit", "friend", "typedef", "using", "namespace", "register", "thread_local",
        "_Noreturn", "__inline", "__inline__", "mutable", "operator", "wchar_t", "char8_t", "char16_t", "char32_t",
    )
    private val QUALIFIERS = setOf("const", "volatile")

    /** Words that are never a macro, though some are written in capitals. */
    private val NOT_MACROS = SPECIFIERS + QUALIFIERS + setOf(
        "if", "while", "for", "switch", "catch", "return", "sizeof", "alignof", "decltype", "defined", "else", "do",
        "case", "goto", "new", "delete", "throw", "typeid", "static_assert", "_Static_assert", "noexcept", "requires",
        "NULL", "EOF", "TRUE", "FALSE",
    )
    private val TRAILING_FOLLOWERS = setOf(";", "{", "=", ":", "const", "noexcept", "override", "final", "->", "&", "&&", "volatile")
    private val AFTER_PARAMETERS = setOf(")", "const", "noexcept", "override", "final")
    private val TYPE_KEYWORDS = setOf("class", "struct", "union", "enum")
    private val ACCESS = setOf("public", "private", "protected")
    private val DIRECTIVE_NAME = Regex("#\\s*(\\w+)")
    private val GUARD_OPEN = Regex("#\\s*(?:ifndef\\s+(\\w+)|if\\s+!\\s*defined\\s*\\(?\\s*(\\w+)\\s*\\)?)")
    private val GUARD_DEFINE = Regex("#\\s*define\\s+(\\w+).*", RegexOption.DOT_MATCHES_ALL)
    private val CPLUSPLUS = Regex("#\\s*(?:ifdef\\s+__cplusplus|if\\s+defined\\s*\\(?\\s*__cplusplus\\s*\\)?)")
    private val CONDITION = Regex("#\\s*(?:if|elif)\\b.*", RegexOption.DOT_MATCHES_ALL)
    private val HEADER_ARGUMENT = Regex("\\(\\s*(<[^<>()\\n]*>)\\s*\\)")

    private enum class Context { TOP, SCOPE, ENUM, INIT, CODE }

    /** A preprocessor conditional: the token indices of its `#if`, its `#else` and `#elif` lines and its `#endif`. */
    private class Conditional(val open: Int, val branches: List<Int>, val close: Int)

    private class Rules(private val text: CharSequence, private val tokens: List<Token>) {
        private val n = tokens.size
        private val masks = mutableListOf<MacroMask>()
        private lateinit var context: Array<Context>
        private val inTemplate = HashSet<Int>()

        /** Token indices right after a masked macro line, which start a statement as after a `;` (R11). */
        private val afterMacroLine = HashSet<Int>()

        fun masks(): List<MacroMask> {
            val conditionals = conditionals()
            context = contexts(conditionals)
            templateParameters()
            includeGuard()
            externC()
            includeArguments()
            bodyConditionals(conditionals)
            specifierConditionals(conditionals)
            val directiveMasks = masks.toList()
            // Two passes: the first learns which macros the file uses as attributes, so a constructor before the
            // first such use is recognized too (R9).
            val learned = HashSet<String>()
            repeat(2) {
                masks.clear()
                afterMacroLine.clear()
                masks += directiveMasks
                tokenRules(learned)
            }
            return masks
        }

        private fun text(at: Int): String? = tokens.getOrNull(at)?.text
        private fun isIdentifier(at: Int) = tokens.getOrNull(at)?.kind == Kind.IDENTIFIER
        private fun isDirective(at: Int) = tokens.getOrNull(at)?.kind == Kind.DIRECTIVE

        private fun isMacro(at: Int): Boolean {
            val token = tokens.getOrNull(at) ?: return false
            return token.kind == Kind.IDENTIFIER && token.text !in NOT_MACROS && CAPITALS.matches(token.text)
        }

        private fun mask(from: Int, to: Int, kind: MacroPattern, prefix: Boolean = kind.prefix) {
            if (to > from) masks += MacroMask(TextRange(from, to), kind, prefix)
        }

        /** The index after the `)` that closes the `(` at [at], or null when a `;`, a brace or a directive comes first. */
        private fun close(at: Int): Int? {
            var depth = 0
            for (k in at until n) {
                val token = tokens[k]
                if (token.kind == Kind.DIRECTIVE) return null
                if (token.kind == Kind.PUNCTUATION) {
                    when (token.text) {
                        ";", "{", "}" -> return null
                        "(" -> depth++
                        ")" -> if (--depth == 0) return k + 1
                    }
                }
            }
            return null
        }

        /** The index after the macro at [at] and its argument list, if it has one. */
        private fun callEnd(at: Int): Int? = if (text(at + 1) == "(") close(at + 1) else at + 1

        /** Whether a declaration starts at [at]: a specifier, or a type name followed by a declarator (details §3.2). */
        private fun declarationHead(at: Int): Boolean {
            var k = at
            while (text(k) in QUALIFIERS) k++
            if (!isIdentifier(k)) return false
            if (text(k) in SPECIFIERS) return true
            if (text(k) in NOT_MACROS) return false
            return if (isIdentifier(k + 1)) text(k + 1) !in QUALIFIERS else text(k + 1) in setOf("*", "&", "&&", "::", "<")
        }

        private fun directive(at: Int): String = DIRECTIVE_NAME.find(tokens[at].text)?.groupValues?.get(1).orEmpty()

        /** The directive's text without a trailing comment. */
        private fun directiveCode(at: Int): String = tokens[at].text.substringBefore("//").substringBefore("/*").trim()

        private fun conditionals(): List<Conditional> {
            val result = mutableListOf<Conditional>()
            val open = ArrayDeque<Pair<Int, MutableList<Int>>>()
            for (k in 0 until n) {
                if (!isDirective(k)) continue
                when (directive(k)) {
                    "if", "ifdef", "ifndef" -> open.addLast(k to mutableListOf())
                    "else", "elif", "elifdef", "elifndef" -> open.lastOrNull()?.second?.add(k)
                    "endif" -> open.removeLastOrNull()?.let { (start, branches) -> result += Conditional(start, branches, k) }
                }
            }
            return result
        }

        /**
         * The context of each token: in a function body, an enum body, an initializer or another scope. Braces in a
         * conditional's later branches are not counted, as the parse with R16 does not see them (details §3.3).
         */
        private fun contexts(conditionals: List<Conditional>): Array<Context> {
            val later = BooleanArray(n)
            for (conditional in conditionals) {
                val first = conditional.branches.firstOrNull() ?: continue
                for (k in first until conditional.close) later[k] = true
            }
            val result = Array(n) { Context.TOP }
            val stack = ArrayDeque<Context>()
            var previous: String? = null
            var enumHead = false
            for (k in 0 until n) {
                val current = stack.lastOrNull() ?: Context.TOP
                result[k] = current
                val token = tokens[k]
                if (later[k] || token.kind == Kind.DIRECTIVE) continue
                if (token.kind == Kind.PUNCTUATION && token.text == "{") {
                    stack.addLast(
                        when {
                            current == Context.CODE -> Context.CODE
                            enumHead -> Context.ENUM
                            previous in setOf("=", ",", "(", "[", "return") -> Context.INIT
                            previous in setOf(")", "const", "noexcept", "override", "final", "mutable", "else", "do", "try") -> Context.CODE
                            else -> Context.SCOPE
                        },
                    )
                    enumHead = false
                } else if (token.kind == Kind.PUNCTUATION && token.text == "}") {
                    stack.removeLastOrNull()
                } else if (token.text == "enum") {
                    enumHead = true
                } else if (token.text in setOf(";", "(", ")", ",")) {
                    enumHead = false
                }
                previous = token.text
            }
            return result
        }

        /** The tokens directly inside a `template <…>` parameter list, outside parentheses (R13). */
        private fun templateParameters() {
            for (k in 0 until n) {
                if (text(k) != "template" || text(k + 1) != "<") continue
                var depth = 0
                var parentheses = 0
                var at = k + 1
                while (at < n) {
                    val word = tokens[at].text
                    if (word == ";" || word == "{" || word == "}" || isDirective(at)) break
                    when {
                        word == "(" -> parentheses++
                        word == ")" -> parentheses--
                        parentheses == 0 && word == "<" -> depth++
                        parentheses == 0 && (word == ">" || word == ">>") -> {
                            depth -= word.length
                            if (depth <= 0) break
                        }
                    }
                    if (parentheses == 0 && depth == 1) inTemplate += at
                    at++
                }
            }
        }

        /** R7: `#ifndef X` and `#define X` first, and the matching `#endif` last, with no `#else` between. */
        private fun includeGuard() {
            if (n < 3 || !isDirective(0) || !isDirective(1) || !isDirective(n - 1) || directive(n - 1) != "endif") return
            val open = GUARD_OPEN.matchEntire(directiveCode(0)) ?: return
            val name = open.groupValues[1].ifEmpty { open.groupValues[2] }
            if (GUARD_DEFINE.matchEntire(tokens[1].text)?.groupValues?.get(1) != name) return
            var depth = 0
            for (k in 0 until n) {
                if (!isDirective(k)) continue
                when (directive(k)) {
                    "if", "ifdef", "ifndef" -> depth++
                    "endif" -> if (--depth == 0 && k != n - 1) return
                    "else", "elif", "elifdef", "elifndef" -> if (depth == 1) return
                }
            }
            mask(tokens[0].start, tokens[0].end, MacroPattern.INCLUDE_GUARD)
            mask(tokens[n - 1].start, tokens[n - 1].end, MacroPattern.INCLUDE_GUARD)
        }

        /** R8: `#ifdef __cplusplus` around only `extern "C" {` or only `}`. */
        private fun externC() {
            for (k in 0 until n - 2) {
                if (!isDirective(k) || !CPLUSPLUS.matches(directiveCode(k))) continue
                val end = when {
                    text(k + 1) == "extern" && tokens.getOrNull(k + 2)?.kind == Kind.STRING && text(k + 3) == "{" -> k + 4
                    text(k + 1) == "}" -> k + 2
                    else -> continue
                }
                if (isDirective(end) && directive(end) == "endif") mask(tokens[k].start, tokens[end].end, MacroPattern.EXTERN_C)
            }
        }

        /** R15: `#if __has_include(<version>)` reads as `#if __has_include(         )`. */
        private fun includeArguments() {
            for (token in tokens) {
                if (token.kind != Kind.DIRECTIVE || !CONDITION.matches(token.text)) continue
                for (match in HEADER_ARGUMENT.findAll(token.text)) {
                    val group = match.groups[1] ?: continue
                    mask(token.start + group.range.first, token.start + group.range.last + 1, MacroPattern.INCLUDE_ARGUMENT)
                }
            }
        }

        /** R16: in a function body, the directive lines and every branch after the first. */
        private fun bodyConditionals(conditionals: List<Conditional>) {
            for (conditional in conditionals) {
                if (context[conditional.open] != Context.CODE || context[conditional.close] != Context.CODE) continue
                mask(tokens[conditional.open].start, tokens[conditional.open].end, MacroPattern.BODY_CONDITIONAL)
                val from = conditional.branches.firstOrNull() ?: conditional.close
                mask(tokens[from].start, tokens[conditional.close].end, MacroPattern.BODY_CONDITIONAL)
            }
        }

        /** R17: `#ifndef _WIN32` `static` `#endif` reads as `static`. */
        private fun specifierConditionals(conditionals: List<Conditional>) {
            for (conditional in conditionals) {
                if (conditional.branches.isNotEmpty() || context[conditional.open] == Context.CODE) continue
                if (conditional.close == conditional.open + 1) continue
                var k = conditional.open + 1
                while (k < conditional.close) {
                    val word = text(k)
                    k = when {
                        word in QUALIFIERS || (word in SPECIFIERS && word !in DECLARING) -> k + 1
                        tokens[k].kind == Kind.STRING && text(k - 1) == "extern" -> k + 1
                        word == "__attribute__" || word == "__declspec" || isMacro(k) -> callEnd(k) ?: break
                        else -> break
                    }
                }
                if (k != conditional.close) continue
                mask(tokens[conditional.open].start, tokens[conditional.open].end, MacroPattern.SPECIFIER_CONDITIONAL)
                mask(tokens[conditional.close].start, tokens[conditional.close].end, MacroPattern.SPECIFIER_CONDITIONAL, prefix = false)
            }
        }

        /** R9 to R14, in one pass over the tokens. */
        private fun tokenRules(learned: MutableSet<String>) {
            var i = 0
            while (i < n) {
                i = tokenRule(i, learned) ?: (i + 1)
            }
        }

        /** The index after the pattern that starts at [i], or null when none does. */
        private fun tokenRule(i: Int, learned: MutableSet<String>): Int? {
            val token = tokens[i]
            if (token.kind != Kind.IDENTIFIER) return null
            val previous = text(i - 1)
            val statementStart = i == 0 || isDirective(i - 1) || previous == ";" || previous == "{" || previous == "}" ||
                (previous == ":" && text(i - 2) in ACCESS) || i in afterMacroLine
            if (isMacro(i)) {
                val end = callEnd(i)
                if (end != null) macroRule(i, end, previous, statementStart, learned)?.let { return it }
            }
            // R12: a macro that takes a block in a function body.
            if (context[i] == Context.CODE && (statementStart || previous == "else") && token.text !in NOT_MACROS) {
                if (text(i + 1) == "(") {
                    val end = close(i + 1)
                    if (end != null && text(end) == "{") {
                        mask(token.start, tokens[end - 1].end, MacroPattern.BLOCK_MACRO)
                        return end
                    }
                } else if (isMacro(i) && text(i + 1) == "{") {
                    mask(token.start, token.end, MacroPattern.BLOCK_MACRO)
                    return i + 1
                }
            }
            return null
        }

        private fun macroRule(i: Int, end: Int, previous: String?, statementStart: Boolean, learned: MutableSet<String>): Int? {
            val token = tokens[i]
            // R11: an invocation that fills its own lines, without a `;`.
            if (statementStart && token.lineStart && context[i] != Context.ENUM && context[i] != Context.INIT) {
                val next = tokens.getOrNull(end)
                if (next == null || next.lineStart || next.kind == Kind.DIRECTIVE) {
                    val followed = next == null || next.kind != Kind.PUNCTUATION || next.text in setOf("}", "~", "*", "&", "::", "[")
                    // `TYPE` alone on a line above `name(…)` is a return type, unless the file uses it as an attribute or
                    // the name is a macro name too.
                    val returnType = next != null && end == i + 1 && next.kind == Kind.IDENTIFIER && !isMacro(end) && text(end + 1) == "(" &&
                        token.text !in learned
                    if (followed && !returnType) {
                        mask(token.start, tokens[end - 1].end, MacroPattern.MACRO_LINE)
                        afterMacroLine += end
                        return end
                    }
                }
            }
            // R14: `if MACRO (`.
            if (previous == "if" && text(i + 1) == "(") {
                mask(token.start, token.end, MacroPattern.IF_ATTRIBUTE)
                return i + 1
            }
            // R9: between `class` and the class name.
            if (previous in TYPE_KEYWORDS && isIdentifier(end) && text(end + 1) in setOf(":", "{", "final")) {
                mask(token.start, tokens[end - 1].end, MacroPattern.CLASS_ATTRIBUTE)
                learned += token.text
                return end
            }
            if (previous !in setOf(".", "->", "::") && previous !in TYPE_KEYWORDS) {
                // A run of macros, which ends at the first that is itself a declaration's type.
                var k = end
                while (isMacro(k) && !declarationHead(k)) k = callEnd(k) ?: break
                // R9: before a declaration's specifiers or type.
                if (declarationHead(k)) {
                    mask(token.start, tokens[k - 1].end, MacroPattern.ATTRIBUTE)
                    learned += token.text
                    return k
                }
                // R9: before a constructor or destructor, once the file uses the macro as an attribute.
                if (token.text in learned && ((isIdentifier(k) && text(k + 1) == "(") || text(k) == "~")) {
                    mask(token.start, tokens[k - 1].end, MacroPattern.ATTRIBUTE)
                    return k
                }
            }
            // R10: after a parameter list, outside function bodies.
            if (context[i] != Context.CODE && previous in AFTER_PARAMETERS && end < n && (text(end) in TRAILING_FOLLOWERS || isMacro(end))) {
                mask(token.start, tokens[end - 1].end, MacroPattern.TRAILING_ATTRIBUTE)
                return end
            }
            // R13: a whole template parameter, with the comma before it.
            if (i in inTemplate && (previous == "," || previous == "<") && text(i + 1) == "(" && text(end) in setOf(",", ">", ">>")) {
                when {
                    previous == "," -> mask(tokens[i - 1].start, tokens[end - 1].end, MacroPattern.TEMPLATE_PARAMETER)
                    text(end) == "," -> mask(token.start, tokens[end].end, MacroPattern.TEMPLATE_PARAMETER)
                    else -> mask(token.start, tokens[end - 1].end, MacroPattern.TEMPLATE_PARAMETER)
                }
                return end
            }
            return null
        }

        private companion object {
            /** Specifiers that start their own declaration, and so are not a specifier-only conditional's content. */
            val DECLARING = setOf("struct", "class", "union", "enum", "namespace", "typedef", "using", "template", "operator")
        }
    }
}
