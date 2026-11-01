package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.Candidate
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.Origin
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.FileRegion
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.declarations.model.WhitespacePolicy
import java.util.IdentityHashMap

/**
 * Compares two declaration trees and builds the engine-neutral result (technical specification §5, §6.7, §7, §8).
 *
 * The result tree follows the new side's containment. A declaration present only on the old side (removed, or
 * uncertain) is placed under its old container's counterpart, or under its old container's own node when that
 * container is only on the old side too, or at the top level; among its new siblings it follows the counterpart of its
 * nearest preceding old sibling. Every declaration of both sides appears in the result exactly once.
 */
class DeclarationAnalysis(private val engineId: EngineId, private val engineVersion: String) {

    fun compare(old: DeclarationTree, new: DeclarationTree, maxDeclarations: Int, cancellation: Cancellation): ComparisonResult {
        val largest = maxOf(old.all.size, new.all.size)
        if (largest > maxDeclarations) {
            return result(Status.TOO_LARGE, reason = "file has $largest declarations, the limit is $maxDeclarations")
        }
        unparsable(old, "old")?.let { return result(Status.FAILED, reason = it) }
        unparsable(new, "new")?.let { return result(Status.FAILED, reason = it) }

        val matching = DeclarationMatcher.match(old, new, cancellation)
        val changes = Tree(old, new, matching, cancellation).apply { detectExtractions() }.build()
        val fileRegion = fileRegion(old, new)
        val partial = fileRegion?.content == Content.UNANALYZED || changes.any { it.hasUnanalyzed() }
        return result(
            status = if (partial) Status.PARTIAL else Status.ANALYZED,
            fileRegion = fileRegion,
            changes = changes,
            reason = if (partial) "some source could not be parsed and was not analyzed" else null,
        )
    }

    private fun result(status: Status, fileRegion: ChangeNode? = null, changes: List<ChangeNode> = emptyList(), reason: String?) =
        ComparisonResult(engineId, engineVersion, status, fileRegion, changes, fallbackReason = reason)

    /** §6.7: a side with a parse error, no declarations and source other than whitespace cannot be analyzed at all. */
    private fun unparsable(tree: DeclarationTree, side: String): String? {
        val region = tree.fileRegion
        val blank = region.tokens.all { it.kind == TokenKind.WHITESPACE }
        return if (region.hasParseError && tree.topLevel.isEmpty() && !blank) "the $side side could not be parsed" else null
    }

    /**
     * The file region as one node spanning all its ranges on each side; [ChangeNode.details] holds the separate ranges.
     * Null when its tokens are identical. A side without file region gets an empty range at offset 0.
     */
    private fun fileRegion(old: DeclarationTree, new: DeclarationTree): ChangeNode? {
        val content = ContentClassifier.classify(
            old.fileRegion.tokens,
            new.fileRegion.tokens,
            new.whitespacePolicy,
            parseError = old.fileRegion.hasParseError || new.fileRegion.hasParseError,
        )
        if (content == Content.UNCHANGED) return null
        return ChangeNode(
            subject = Subject.Region(FILE_REGION),
            oldRange = span(old.fileRegion),
            newRange = span(new.fileRegion),
            presence = Presence.MATCHED,
            content = content,
            details = old.fileRegion.ranges.map { RangePair(it, null) } + new.fileRegion.ranges.map { RangePair(null, it) },
        )
    }

    private fun span(region: FileRegion): TextRange =
        if (region.ranges.isEmpty()) TextRange(0, 0)
        else TextRange(region.ranges.minOf { it.start }, region.ranges.maxOf { it.end })

    private fun ChangeNode.hasUnanalyzed(): Boolean = content == Content.UNANALYZED || children.any { it.hasUnanalyzed() }

    /** Builds the result tree from a matching. */
    private class Tree(
        private val old: DeclarationTree,
        private val new: DeclarationTree,
        private val matching: Matching,
        private val cancellation: Cancellation,
    ) {
        /** A node under construction; its children can still be inserted. */
        private inner class Builder(val build: (List<ChangeNode>) -> ChangeNode) {
            val children = mutableListOf<Builder>()

            fun toNode(): ChangeNode = build(children.map { it.toNode() })
        }

        private val uncertainOld = IdentityHashMap<Declaration, Uncertain>().apply { matching.uncertainOld.forEach { put(it.declaration, it) } }
        private val uncertainNew = IdentityHashMap<Declaration, Uncertain>().apply { matching.uncertainNew.forEach { put(it.declaration, it) } }

        /** The builder of each new-side declaration, and of each old-side declaration that is only on the old side. */
        private val ofNew = IdentityHashMap<Declaration, Builder>()
        private val ofOldOnly = IdentityHashMap<Declaration, Builder>()

        /** The content of each match, classified once (§8). */
        private val contents = IdentityHashMap<Match, Content>()

        private var extractions = Extractions.NONE

        /** Links added declarations to the code they were extracted from, after matching and classification. */
        fun detectExtractions() {
            extractions = ExtractionDetector.detect(matching, { content(it) == Content.CHANGED }, cancellation)
        }

        fun build(): List<ChangeNode> {
            val topLevel = new.topLevel.mapTo(mutableListOf()) { newSide(it) }
            placeOldOnly(old.topLevel, topLevel)
            return topLevel.map { it.toNode() }
        }

        private fun newSide(declaration: Declaration): Builder {
            cancellation.checkCanceled()
            val match = matching.ofNew(declaration)
            val uncertain = uncertainNew[declaration]
            val builder = when {
                match != null -> Builder { children -> matched(match, children) }
                uncertain != null -> Builder { children -> uncertain(uncertain, onOldSide = false, children) }
                else -> Builder { children ->
                    val origins = extractions.ofTarget[declaration].orEmpty().map { Origin(subject(it.source.new), it.source.old.range, it.origin) }
                    node(declaration, null, declaration.range, Presence.ADDED, children).copy(extractedFrom = origins)
                }
            }
            ofNew[declaration] = builder
            declaration.children.mapTo(builder.children) { newSide(it) }
            return builder
        }

        /** Inserts the old-only declarations among [oldChildren] into [target], then does the same one level down. */
        private fun placeOldOnly(oldChildren: List<Declaration>, target: MutableList<Builder>) {
            cancellation.checkCanceled()
            var insertAt = 0
            for (child in oldChildren) {
                val counterpart = matching.ofOld(child)?.new?.let { ofNew.getValue(it) }
                if (counterpart != null) {
                    val index = target.indexOf(counterpart)
                    if (index >= 0) insertAt = index + 1
                } else {
                    val builder = oldOnly(child)
                    ofOldOnly[child] = builder
                    target.add(insertAt++, builder)
                }
            }
            for (child in oldChildren) {
                if (child.children.isEmpty()) continue
                val container = matching.ofOld(child)?.new?.let { ofNew.getValue(it) } ?: ofOldOnly.getValue(child)
                placeOldOnly(child.children, container.children)
            }
        }

        private fun oldOnly(declaration: Declaration): Builder {
            val uncertain = uncertainOld[declaration]
            return if (uncertain != null) Builder { children -> uncertain(uncertain, onOldSide = true, children) }
            else Builder { children -> node(declaration, declaration.range, null, Presence.REMOVED, children) }
        }

        private fun matched(match: Match, children: List<ChangeNode>): ChangeNode {
            val extracted = extractions.ofSource[match.old].orEmpty()
            val node = node(
                declaration = match.new,
                oldRange = match.old.range,
                newRange = match.new.range,
                oldDeclaration = match.old,
                newDeclaration = match.new,
                presence = Presence.MATCHED,
                children = children,
                moved = match.moved,
                signatureChanged = signatureChanged(match),
                renamedFrom = if (match.rule == MatchRule.RENAME_CANDIDATE) subject(match.old) else null,
                kindChangedFrom = match.old.kind.takeIf { it != match.new.kind },
                content = content(match),
            )
            if (extracted.isEmpty()) return node
            // The extracted code is highlighted on the old side, apart from the lines that really changed.
            return node.copy(
                extractedTo = extracted.map { subject(it.target) },
                details = node.details + extracted.map { RangePair(it.origin, null) }.distinct(),
            )
        }

        private fun content(match: Match): Content = contents.getOrPut(match) {
            val oldTokens = renamed(match.old.ownTokens, match.old.name, match.new.name)
            val newTokens = match.new.ownTokens
            val policy = new.whitespacePolicy
            val parseError = match.old.hasParseError || match.new.hasParseError
            val changedSignature = signatureChanged(match)
            val classified = ContentClassifier.classify(oldTokens, newTokens, policy, parseError, changedSignature)
            if (classified != Content.CHANGED || parseError || changedSignature || policy == WhitespacePolicy.SIGNIFICANT) {
                return@getOrPut classified
            }
            // The whitespace before a declaration is layout, even a line break that the policy would otherwise count
            // (declarations/02 R14). If the source from the first token on is the same, only that layout differs.
            val oldLeading = leadingTokens(match.old)
            val newLeading = leadingTokens(match.new)
            val sameSource =
                ContentClassifier.normalize(oldTokens.drop(oldLeading.size), policy) ==
                    ContentClassifier.normalize(newTokens.drop(newLeading.size), policy)
            // Scala 3 indentation is significant, the indentation on the declaration's first line included.
            val sameIndentation =
                policy != WhitespacePolicy.INDENTATION_SIGNIFICANT ||
                    indentation(oldLeading) == indentation(newLeading)
            if (sameSource && sameIndentation) Content.FORMATTING_ONLY else Content.CHANGED
        }

        /** The whitespace tokens at the start of [declaration]'s own tokens, its leading whitespace (§6.1). */
        private fun leadingTokens(declaration: Declaration): List<Token> =
            declaration.ownTokens.takeWhile { it.kind == TokenKind.WHITESPACE }

        /** The text after the last line break of [tokens], as the indentation policy compares it. */
        private fun indentation(tokens: List<Token>): String {
            val text = tokens.joinToString("") { it.text }
            val lastBreak = maxOf(text.lastIndexOf('\n'), text.lastIndexOf('\r'))
            return if (lastBreak >= 0) text.substring(lastBreak + 1) else ""
        }

        /** A different signature, or a different kind (rule M4b), which changes how the declaration is used. */
        private fun signatureChanged(match: Match): Boolean =
            match.old.signature != match.new.signature || match.old.kind != match.new.kind

        /**
         * The old side's [tokens] with the old [from] name written as the new [to] name, so a rename (or a constructor
         * that follows its class's rename) is not a content change by itself; the rename has its own label.
         */
        private fun renamed(tokens: List<Token>, from: String, to: String): List<Token> =
            if (from == to || from.isEmpty()) tokens
            else tokens.map { if (it.kind == TokenKind.OTHER && it.text == from) it.copy(text = to) else it }

        private fun uncertain(uncertain: Uncertain, onOldSide: Boolean, children: List<ChangeNode>): ChangeNode {
            val range = uncertain.declaration.range
            return node(
                declaration = uncertain.declaration,
                oldRange = range.takeIf { onOldSide },
                newRange = range.takeUnless { onOldSide },
                presence = Presence.UNCERTAIN,
                children = children,
                candidates = uncertain.candidates.map { Candidate(subject(it), it.range) },
            )
        }

        private fun node(
            declaration: Declaration,
            oldRange: TextRange?,
            newRange: TextRange?,
            presence: Presence,
            children: List<ChangeNode>,
            moved: Boolean = false,
            signatureChanged: Boolean = false,
            renamedFrom: Subject? = null,
            kindChangedFrom: DeclarationKind? = null,
            content: Content = Content.UNCHANGED,
            candidates: List<Candidate> = emptyList(),
            oldDeclaration: Declaration? = declaration.takeIf { oldRange != null },
            newDeclaration: Declaration? = declaration.takeIf { newRange != null },
        ): ChangeNode {
            val node = ChangeNode(
                subject = subject(declaration),
                oldRange = oldRange,
                newRange = newRange,
                presence = presence,
                moved = moved,
                signatureChanged = signatureChanged,
                content = content,
                candidates = candidates,
                children = children,
                renamedFrom = renamedFrom,
                kindChangedFrom = kindChangedFrom,
            )
            // A change covers the declaration's leading whitespace too, which is part of its own tokens (§8).
            val oldLeading = oldDeclaration?.leadingWhitespace
            val newLeading = newDeclaration?.leadingWhitespace
            return if (node.isChangedItself && (oldLeading != null || newLeading != null)) {
                node.copy(details = listOf(RangePair(oldLeading, newLeading)))
            } else {
                node
            }
        }

        private fun subject(declaration: Declaration) =
            Subject.Declaration(declaration.kind, declaration.name, declaration.displaySignature, declaration.receiver)
    }

    companion object {
        const val FILE_REGION = "File region"
    }
}
