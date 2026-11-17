package io.github.agadek.smartdiff.engine.declarations.analysis

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.Candidate
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.ConfidencePolicy
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.api.EquivalenceRule
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.Inlined
import io.github.agadek.smartdiff.engine.api.Origin
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RangePair
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.model.EquivalenceRuleImpl
import io.github.agadek.smartdiff.engine.declarations.model.FileRegion
import io.github.agadek.smartdiff.engine.declarations.model.Token
import io.github.agadek.smartdiff.engine.declarations.model.TokenKind
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
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

    /**
     * Compares the two trees. [confidence] classifies the scored pairs; it never changes which pairs are found
     * (relationships/02 R9, R14, R25).
     */
    fun compare(
        old: DeclarationTree,
        new: DeclarationTree,
        maxDeclarations: Int,
        cancellation: Cancellation,
        confidence: ConfidencePolicy = ConfidencePolicy.DEFAULT,
        languageId: String? = null,
        equivalenceRules: List<EquivalenceRuleImpl> = emptyList(),
    ): ComparisonResult {
        val largest = maxOf(old.all.size, new.all.size)
        if (largest > maxDeclarations) {
            return result(Status.TOO_LARGE, reason = "file has $largest declarations, the limit is $maxDeclarations")
        }
        unparsable(old, "old")?.let { return result(Status.FAILED, reason = it) }
        unparsable(new, "new")?.let { return result(Status.FAILED, reason = it) }

        val matching = DeclarationMatcher.match(old, new, confidence, languageId, cancellation)
        val tree = Tree(old, new, matching, cancellation, equivalenceRules)
        tree.detectLinks()
        val changes = tree.build()
        val fileRegion = fileRegion(old, new, equivalenceRules, cancellation)
        val partial = fileRegion?.content == Content.UNANALYZED || changes.any { it.hasUnanalyzed() }
        return result(
            status = if (partial) Status.PARTIAL else Status.ANALYZED,
            fileRegion = fileRegion,
            changes = changes,
            relationships = tree.relationships,
            reason = if (partial) "some source could not be parsed and was not analyzed" else null,
        )
    }

    private fun result(
        status: Status,
        fileRegion: ChangeNode? = null,
        changes: List<ChangeNode> = emptyList(),
        relationships: List<Relationship> = emptyList(),
        reason: String?,
    ) = ComparisonResult(engineId, engineVersion, status, fileRegion, changes, fallbackReason = reason, relationships = relationships)

    /** §6.7: a side with a parse error, no declarations and source other than whitespace cannot be analyzed at all. */
    private fun unparsable(tree: DeclarationTree, side: String): String? {
        val region = tree.fileRegion
        val blank = region.tokens.all { it.kind == TokenKind.WHITESPACE }
        return if (region.hasParseError && tree.topLevel.isEmpty() && !blank) "the $side side could not be parsed" else null
    }

    /**
     * The file region as one node spanning all its ranges on each side; [ChangeNode.details] holds the separate ranges.
     * Null when its tokens are identical. A region whose only difference is a separator, such as a JSON comma, stays as
     * an unchanged node: it is not reported, and the nothing-hidden check covers the separator through its ranges. A
     * side without file region gets an empty range at offset 0.
     */
    private fun fileRegion(
        old: DeclarationTree,
        new: DeclarationTree,
        rules: List<EquivalenceRuleImpl>,
        cancellation: Cancellation,
    ): ChangeNode? {
        val content = ContentClassifier.classify(
            old.fileRegion.tokens,
            new.fileRegion.tokens,
            new.whitespacePolicy,
            parseError = old.fileRegion.hasParseError || new.fileRegion.hasParseError,
        )
        val separatorOnly = content == Content.UNCHANGED
        val separators = old.fileRegion.tokens.any { it.kind == TokenKind.SEPARATOR } ||
            new.fileRegion.tokens.any { it.kind == TokenKind.SEPARATOR }
        if (separatorOnly && !separators && old.fileRegion.tokens == new.fileRegion.tokens &&
            old.fileRegion.ranges == new.fileRegion.ranges) return null
        // An unchanged region exists only to cover its exact ranges for the nothing-hidden check, so its span is the
        // first range, not the whole region; a reported region keeps its span for the outline.
        val runs = tokenRuns(
            content,
            old.fileRegion.tokens, old.fileRegion.tokenStarts,
            new.fileRegion.tokens, new.fileRegion.tokenStarts,
            new.whitespacePolicy,
            parseError = old.fileRegion.hasParseError || new.fileRegion.hasParseError,
            cancellation,
            oldOwn = old.fileRegion.ranges,
            newOwn = new.fileRegion.ranges,
        )
        return ChangeNode(
            subject = Subject.Region(FILE_REGION),
            oldRange = if (separatorOnly) old.fileRegion.ranges.firstOrNull() ?: TextRange(0, 0) else span(old.fileRegion),
            newRange = if (separatorOnly) new.fileRegion.ranges.firstOrNull() ?: TextRange(0, 0) else span(new.fileRegion),
            presence = Presence.MATCHED,
            content = content,
            details = old.fileRegion.ranges.map { RangePair(it, null) } + new.fileRegion.ranges.map { RangePair(null, it) },
            // outline/11 R7: the file region is comment-only under the same rule.
            commentOnly = content == Content.CHANGED && ContentClassifier.isCommentOnly(
                old.fileRegion.tokens,
                new.fileRegion.tokens,
                new.whitespacePolicy,
                parseError = old.fileRegion.hasParseError || new.fileRegion.hasParseError,
            ),
            // declarations/08 R9: the file region is rewrite-only under the same rule.
            rewriteRules = if (content == Content.CHANGED) ContentClassifier.rewriteRules(
                old.fileRegion.tokens,
                new.fileRegion.tokens,
                new.whitespacePolicy,
                parseError = old.fileRegion.hasParseError || new.fileRegion.hasParseError,
                rules = rules,
            ) else emptySet(),
            changedRuns = runs.orEmpty(),
            comparedByTokens = runs != null,
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
        private val equivalenceRules: List<EquivalenceRuleImpl>,
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

        /** The relationships of the result, sorted as relationships/02 R19 requires. */
        var relationships: List<Relationship> = emptyList()
            private set

        /**
         * Links added declarations to the code they were extracted from, and removed declarations to the changed
         * declarations their code was inlined into, after matching and classification (relationships/05 R1, R12).
         */
        fun detectLinks() {
            val changed = { match: Match -> content(match) == Content.CHANGED }
            val extractions = ExtractionDetector.detectLinks(matching, changed, cancellation).map { it.relationship }
            // A low pair is shown as added and removed, so its new declaration is not a target (relationships/02 R13).
            val inlinings = ExtractionDetector.detectInliningLinks(matching, { changed(it) && !isLow(it) }, cancellation)
                .map { it.relationship }
            relationships = (matching.relationships + extractions + inlinings).sortedWith(ORDER)
        }

        /** The MATCH relationship of a pair, high or medium, which carries its rename, move and kind change. */
        private val matchLinks: Map<Pair<TextRange, TextRange>, Relationship> by lazy {
            usableMatches.associateBy { it.old.first().range to it.new.first().range }
        }

        /** The pairs whose match is low: the matcher paired them, but the tree leaves both sides added and removed (R13). */
        private val lowMatches: Set<Pair<TextRange, TextRange>> by lazy {
            matching.relationships
                .filter { RelationshipKind.RENAME in it.kinds && it.confidence == Confidence.LOW }
                .mapTo(HashSet()) { it.old.first().range to it.new.first().range }
        }

        private fun isLow(match: Match): Boolean = (match.old.range to match.new.range) in lowMatches

        /** The extraction links, by the source's old range and by the target's new range (R18). */
        private val extractionBySource: Map<TextRange, List<Relationship>> by lazy {
            usableExtractions.groupBy { it.old.first().range }
        }
        private val extractionByTarget: Map<TextRange, List<Relationship>> by lazy {
            usableExtractions.groupBy { it.new.first().range }
        }

        /** The inlining links, by the removed declaration's old range and by the target's new range (R18, R36). */
        private val inliningBySource: Map<TextRange, List<Relationship>> by lazy {
            usableInlinings.groupBy { it.old.first().range }
        }
        private val inliningByTarget: Map<TextRange, List<Relationship>> by lazy {
            usableInlinings.groupBy { it.new.first().range }
        }

        /**
         * The subject each matched declaration shows, by its old range. An extraction's [Origin] names its source as
         * the outline shows it, on the matched node, so the note keeps the name and signature it has today (R9).
         */
        private val matchedSubjects: Map<TextRange, Subject> =
            matching.matches.associate { it.old.range to declarationSubject(it.new) }

        /** The high or medium matches; a low one stays in the list and sets no field (R13). */
        private val usableMatches: List<Relationship> by lazy {
            relationships.filter {
                it.kinds.none { kind -> kind == RelationshipKind.EXTRACT || kind == RelationshipKind.INLINE } &&
                    it.confidence != Confidence.LOW
            }
        }

        /** A low link stays in the list but sets none of the computed fields (R13). */
        private val usableExtractions: List<Relationship> by lazy {
            relationships.filter { RelationshipKind.EXTRACT in it.kinds && it.confidence != Confidence.LOW }
        }

        private val usableInlinings: List<Relationship> by lazy {
            relationships.filter { RelationshipKind.INLINE in it.kinds && it.confidence != Confidence.LOW }
        }

        fun build(): List<ChangeNode> {
            val topLevel = new.topLevel.mapTo(mutableListOf()) { newSide(it) }
            placeOldOnly(old.topLevel, topLevel)
            return topLevel.map { it.toNode() }
        }

        private fun newSide(declaration: Declaration): Builder {
            cancellation.checkCanceled()
            // A low pair is found by the matcher but shown as added and removed (R13).
            val match = matching.ofNew(declaration)?.takeIf { !isLow(it) }
            val uncertain = uncertainNew[declaration]
            val builder = when {
                match != null -> Builder { children -> matched(match, children) }
                uncertain != null -> Builder { children -> uncertain(uncertain, onOldSide = false, children) }
                else -> Builder { children ->
                    val origins = sourcesOf(declaration.range).map { link ->
                        val source = link.old.first()
                        Origin(matchedSubjects[source.range] ?: source.subject, source.range, contentOf(link).oldRange)
                    }
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
                val counterpart = matching.ofOld(child)?.takeIf { !isLow(it) }?.new?.let { ofNew.getValue(it) }
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
                val container = matching.ofOld(child)?.takeIf { !isLow(it) }?.new?.let { ofNew.getValue(it) } ?: ofOldOnly.getValue(child)
                placeOldOnly(child.children, container.children)
            }
        }

        private fun oldOnly(declaration: Declaration): Builder {
            val uncertain = uncertainOld[declaration]
            return if (uncertain != null) Builder { children -> uncertain(uncertain, onOldSide = true, children) }
            else Builder { children ->
                val node = node(declaration, declaration.range, null, Presence.REMOVED, children)
                val inlined = targetsOf(declaration.range)
                if (inlined.isEmpty()) node
                else node.copy(
                    inlinedInto = inlined.map { link ->
                        val target = link.new.first()
                        Inlined(target.subject, target.range, contentOf(link).oldRange, contentOf(link).newRange)
                    },
                    details = node.details + inlined.map { RangePair(null, contentOf(it).newRange) }.distinct(),
                )
            }
        }

        private fun matched(match: Match, children: List<ChangeNode>): ChangeNode {
            val link = matchLinks[match.old.range to match.new.range]
            val extracted = extractionBySource[match.old.range].orEmpty()
            val inlined = inliningsOf(match.new.range)
            val node = node(
                declaration = match.new,
                oldRange = match.old.range,
                newRange = match.new.range,
                oldDeclaration = match.old,
                newDeclaration = match.new,
                presence = Presence.MATCHED,
                children = children,
                moved = link != null && RelationshipKind.MOVE in link.kinds,
                signatureChanged = signatureChanged(match),
                renamedFrom = link?.takeIf { RelationshipKind.RENAME in it.kinds }?.old?.first()?.subject,
                kindChangedFrom = link?.takeIf { RelationshipKind.KIND_CHANGE in it.kinds }
                    ?.old?.first()?.subject?.let { (it as? Subject.Declaration)?.kind },
                content = content(match),
            ).let { if (it.content == Content.CHANGED && commentOnly(match)) it.copy(commentOnly = true) else it }
                .let { if (it.content == Content.CHANGED) it.copy(rewriteRules = rewriteRules(match)) else it }
                .let { node -> changedRuns(match, node.content)?.let { node.copy(changedRuns = it, comparedByTokens = true) } ?: node }
            // The extracted code is highlighted on the old side, apart from the lines that really changed.
            val withExtractions = if (extracted.isEmpty()) node else node.copy(
                extractedTo = extracted.map { it.new.first().subject },
                details = node.details + extracted.map { RangePair(contentOf(it).oldRange, null) }.distinct(),
            )
            // The inlined code is highlighted on the new side, where it was added to this declaration.
            return if (inlined.isEmpty()) withExtractions else withExtractions.copy(
                inlinedFrom = inlined.map { link ->
                    val source = link.old.first()
                    Inlined(source.subject, source.range, contentOf(link).oldRange, contentOf(link).newRange)
                },
                details = withExtractions.details + inlined.map { RangePair(null, contentOf(it).newRange) }.distinct(),
            )
        }

        /**
         * A target's sources in the order the extraction detector measured them, largest covered share first and then
         * in old-side source order, which is the order the outline has always named them in (R9, R18). The list itself
         * is in the relationship order (R19), so the field is ordered here.
         */
        private fun sourcesOf(target: TextRange): List<Relationship> =
            extractionByTarget[target].orEmpty().sortedWith(
                compareByDescending<Relationship> { contentOf(it).share() }.thenBy { it.old.first().range.start },
            )

        /** A removed declaration's inlining targets: largest covered share first, then in new-side order (R6, R18). */
        private fun targetsOf(source: TextRange): List<Relationship> =
            inliningBySource[source].orEmpty().sortedWith(
                compareByDescending<Relationship> { contentOf(it).share() }.thenBy { it.new.first().range.start },
            )

        /** A target's inlining sources: largest covered share first, then in old-side order (R7, R18). */
        private fun inliningsOf(target: TextRange): List<Relationship> =
            inliningByTarget[target].orEmpty().sortedWith(
                compareByDescending<Relationship> { contentOf(it).share() }.thenBy { it.old.first().range.start },
            )

        private fun contentOf(extraction: Relationship): Evidence.Content =
            extraction.evidence.filterIsInstance<Evidence.Content>().single()

        /** The share of the target's tokens the tiles covered, which the detector compares with its threshold. */
        private fun Evidence.Content.share(): Double = if (total == 0) 0.0 else shared.toDouble() / total

        private fun content(match: Match): Content = contents.getOrPut(match) {
            val oldTokens = renamed(match)
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
            if (policy == WhitespacePolicy.SCALA3_INDENTATION) {
                // Scala 3 keeps indentation significant, but a whole declaration re-indented by one amount is
                // formatting-only (declarations/07 R8). Use the shift of the whole declaration, leading run included,
                // for both the body and the leading run so the relaxed check agrees with the main classification.
                // A failure in the rule is conservative: CHANGED (declarations/07 details, Failure).
                val shifted = runCatching {
                    val oldShift = ContentClassifier.indentationShift(ContentClassifier.normalize(oldTokens, policy))
                    val newShift = ContentClassifier.indentationShift(ContentClassifier.normalize(newTokens, policy))
                    val sameSource =
                        ContentClassifier.applyIndentShift(ContentClassifier.normalize(oldTokens.drop(oldLeading.size), policy), oldShift) ==
                            ContentClassifier.applyIndentShift(ContentClassifier.normalize(newTokens.drop(newLeading.size), policy), newShift)
                    val sameIndentation =
                        indentation(oldLeading).removePrefix(oldShift ?: "") == indentation(newLeading).removePrefix(newShift ?: "")
                    sameSource && sameIndentation
                }.getOrDefault(false)
                return@getOrPut if (shifted) Content.FORMATTING_ONLY else Content.CHANGED
            }
            val sameSource =
                ContentClassifier.normalize(oldTokens.drop(oldLeading.size), policy) ==
                    ContentClassifier.normalize(newTokens.drop(newLeading.size), policy)
            // Scala 3's plain sibling compares the first line's indentation as written; the new Scala 3 policy
            // applies the whole-declaration shift instead.
            val sameIndentation =
                policy != WhitespacePolicy.INDENTATION_SIGNIFICANT ||
                    indentation(oldLeading) == indentation(newLeading)
            if (sameSource && sameIndentation) Content.FORMATTING_ONLY else Content.CHANGED
        }

        /**
         * Whether a changed match differs only in comments and formatting (outline/11 R3 to R5). As for formatting-only,
         * the whitespace before the first token is layout (declarations/02 R14), except for significant indentation.
         */
        private fun commentOnly(match: Match): Boolean {
            val oldTokens = renamed(match)
            val newTokens = match.new.ownTokens
            val policy = new.whitespacePolicy
            val parseError = match.old.hasParseError || match.new.hasParseError
            val changedSignature = signatureChanged(match)
            if (ContentClassifier.isCommentOnly(oldTokens, newTokens, policy, parseError, changedSignature)) return true
            if (policy == WhitespacePolicy.SIGNIFICANT) return false
            val oldLeading = leadingTokens(match.old)
            val newLeading = leadingTokens(match.new)
            // Like formatting-only, Scala 3 keeps indentation significant but allows a whole declaration to shift
            // by one amount (declarations/07 R8). Kotlin and Scala 2 never compare indentation here.
            val sameIndentation = runCatching {
                when (policy) {
                    WhitespacePolicy.SCALA3_INDENTATION -> {
                        val oldShift = ContentClassifier.indentationShift(ContentClassifier.normalize(oldTokens, policy))
                        val newShift = ContentClassifier.indentationShift(ContentClassifier.normalize(newTokens, policy))
                        indentation(oldLeading).removePrefix(oldShift ?: "") == indentation(newLeading).removePrefix(newShift ?: "")
                    }
                    else -> policy != WhitespacePolicy.INDENTATION_SIGNIFICANT ||
                        indentation(oldLeading) == indentation(newLeading)
                }
            }.getOrDefault(false)
            return sameIndentation && ContentClassifier.isCommentOnly(
                oldTokens.drop(oldLeading.size), newTokens.drop(newLeading.size), policy, parseError, changedSignature,
            )
        }

        /**
         * The equivalence rules a changed match needs to be rewrite-only (declarations/08 R4 to R7), empty when none
         * make it so. As for comment-only, the whitespace before the first token is layout (declarations/02 R14).
         */
        private fun rewriteRules(match: Match): Set<EquivalenceRule> {
            if (equivalenceRules.isEmpty()) return emptySet()
            val oldTokens = renamed(match)
            val newTokens = match.new.ownTokens
            val policy = new.whitespacePolicy
            val parseError = match.old.hasParseError || match.new.hasParseError
            val changedSignature = signatureChanged(match)
            val whole = ContentClassifier.rewriteRules(oldTokens, newTokens, policy, parseError, changedSignature, equivalenceRules)
            if (whole.isNotEmpty() || policy == WhitespacePolicy.SIGNIFICANT) return whole
            val oldLeading = leadingTokens(match.old)
            val newLeading = leadingTokens(match.new)
            val sameIndentation = runCatching {
                when (policy) {
                    WhitespacePolicy.SCALA3_INDENTATION -> {
                        val oldShift = ContentClassifier.indentationShift(ContentClassifier.normalize(oldTokens, policy))
                        val newShift = ContentClassifier.indentationShift(ContentClassifier.normalize(newTokens, policy))
                        indentation(oldLeading).removePrefix(oldShift ?: "") == indentation(newLeading).removePrefix(newShift ?: "")
                    }
                    else -> policy != WhitespacePolicy.INDENTATION_SIGNIFICANT ||
                        indentation(oldLeading) == indentation(newLeading)
                }
            }.getOrDefault(false)
            if (!sameIndentation) return emptySet()
            return ContentClassifier.rewriteRules(
                oldTokens.drop(oldLeading.size), newTokens.drop(newLeading.size), policy, parseError, changedSignature, equivalenceRules,
            )
        }

        /**
         * The changed runs of a changed or formatting-only match (declarations/10 R2, R3), compared from the first token
         * of the declaration on: its leading whitespace belongs to the line before (§6.1). Null when not compared.
         */
        private fun changedRuns(match: Match, content: Content): List<RangePair>? {
            val start = { declaration: Declaration -> declaration.range.start }
            fun own(declaration: Declaration, tokens: List<Token>): Pair<List<Token>, List<Int>> {
                if (declaration.ownTokenStarts.size != tokens.size) return tokens to emptyList()
                val kept = tokens.indices.filter { declaration.ownTokenStarts[it] >= start(declaration) }
                return kept.map { tokens[it] } to kept.map { declaration.ownTokenStarts[it] }
            }
            val renamedOld = renamed(match)
            val (oldTokens, oldStarts) = own(match.old, renamedOld)
            val (newTokens, newStarts) = own(match.new, match.new.ownTokens)
            // A renamed token's text is the new name; its range keeps the old token's length.
            val (oldSource, _) = own(match.old, match.old.ownTokens)
            return tokenRuns(
                content, oldTokens, oldStarts, newTokens, newStarts, new.whitespacePolicy,
                parseError = match.old.hasParseError || match.new.hasParseError,
                cancellation,
                oldOwn = ownRanges(match.old),
                newOwn = ownRanges(match.new),
                oldLengths = oldSource.map { it.text.length },
                // Scala 3 measures the shift with the leading whitespace, as classification does (declarations/07 R8).
                oldShiftSource = renamedOld,
                newShiftSource = match.new.ownTokens,
            )
        }

        /** [declaration]'s range minus its children's, with their leading whitespace and layout (§6.1). */
        private fun ownRanges(declaration: Declaration): List<TextRange> {
            val ranges = mutableListOf<TextRange>()
            var start = declaration.range.start
            for (child in declaration.children) {
                val childStart = child.leadingWhitespace?.start ?: child.range.start
                val childEnd = child.layout?.end ?: child.range.end
                if (childStart > start) ranges += TextRange(start, minOf(childStart, declaration.range.end))
                start = maxOf(start, childEnd)
            }
            if (declaration.range.end > start) ranges += TextRange(start, declaration.range.end)
            return ranges
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
         * The old side with its name written as the new name, so a rename (or a constructor that follows its class's
         * rename) is not a content change by itself; the rename has its own label. A key's name tokens differ from its
         * name, such as a quoted JSON key, so its [Declaration.nameRange] finds them.
         */
        private fun renamed(match: Match): List<Token> {
            val oldName = match.old.nameRange
            val newName = match.new.nameRange
            if (oldName != null && newName != null) {
                val replacement = match.new.ownTokens.zip(match.new.ownTokenStarts)
                    .filter { (_, start) -> start >= newName.start && start < newName.end }
                    .joinToString("") { (token, _) -> token.text }
                if (replacement.isNotEmpty()) {
                    return match.old.ownTokens.mapIndexed { index, token ->
                        val start = match.old.ownTokenStarts.getOrNull(index)
                        if (start != null && start >= oldName.start && start < oldName.end) token.copy(text = replacement) else token
                    }
                }
            }
            return renamed(match.old.ownTokens, match.old.name, match.new.name)
        }

        /** The old side's [tokens] with the old [from] name written as the new [to] name. */
        private fun renamed(tokens: List<Token>, from: String, to: String): List<Token> =
            if (from == to || from.isEmpty()) tokens
            else tokens.map { if (it.kind == TokenKind.OTHER && it.text == from) it.copy(text = to) else it }

        /** The ranges of [declaration]'s separator tokens, on its own side, or an empty list. */
        private fun separatorDetails(declaration: Declaration?, onOldSide: Boolean): List<RangePair> {
            if (declaration == null) return emptyList()
            return declaration.ownTokens.zip(declaration.ownTokenStarts)
                .filter { (token, _) -> token.kind == TokenKind.SEPARATOR }
                .map { (token, start) ->
                    val range = TextRange(start, start + token.text.length)
                    if (onOldSide) RangePair(range, null) else RangePair(null, range)
                }
        }

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
            // A change covers the declaration's leading whitespace too, which is part of its own tokens (§8). Under the
            // SIGNIFICANT policy the fallback provider leaves leading whitespace out of the comparison but still
            // lists it, so a reorder that reports one member of a swapped pair accounts for both members' line breaks;
            // the outline only draws details of reported nodes, so no unchanged row is added.
            val oldLeading = oldDeclaration?.leadingWhitespace
            val newLeading = newDeclaration?.leadingWhitespace
            val keepLeading = node.isChangedItself || new.whitespacePolicy == WhitespacePolicy.SIGNIFICANT
            val details = mutableListOf<RangePair>()
            if (keepLeading && (oldLeading != null || newLeading != null)) details += RangePair(oldLeading, newLeading)
            // Separators between children, such as JSON commas, stay in the container's own tokens and cover source for
            // the nothing-hidden check without making the container changed (§3.1 rule 6).
            details += separatorDetails(oldDeclaration, onOldSide = true)
            details += separatorDetails(newDeclaration, onOldSide = false)
            // The fallback provider's separator/line break after an item is covered like leading whitespace but was
            // never compared, so it can be listed for every node, changed or not (languages/06 R7).
            oldDeclaration?.layout?.let { details += RangePair(it, null) }
            newDeclaration?.layout?.let { details += RangePair(null, it) }
            return if (details.isEmpty()) node else node.copy(details = details)
        }

        private fun subject(declaration: Declaration) = declarationSubject(declaration)
    }

    companion object {
        const val FILE_REGION = "File region"

        /** relationships/02 R19: old start, then new start, then the first kind in enum order. */
        private val ORDER = compareBy<Relationship>(
            { it.old.first().range.start },
            { it.new.first().range.start },
            { it.kinds.first().ordinal },
        )
    }
}

/**
 * The changed runs for a node of [content] (declarations/10 R2, R9, R10): compared only when changed or formatting-only,
 * and a formatting-only node is compared with no runs. Null when not compared. A failure other than cancellation leaves
 * the node without runs and keeps its classification (declarations/10 details, Failure).
 */
private fun tokenRuns(
    content: Content,
    old: List<Token>,
    oldStarts: List<Int>,
    new: List<Token>,
    newStarts: List<Int>,
    policy: WhitespacePolicy,
    parseError: Boolean,
    cancellation: Cancellation,
    oldOwn: List<TextRange>,
    newOwn: List<TextRange>,
    oldLengths: List<Int>? = null,
    oldShiftSource: List<Token>? = null,
    newShiftSource: List<Token>? = null,
): List<RangePair>? {
    if (content != Content.CHANGED && content != Content.FORMATTING_ONLY) return null
    if (parseError) return null
    if ((old.isNotEmpty() && oldStarts.size != old.size) || (new.isNotEmpty() && newStarts.size != new.size)) return null
    if (content == Content.FORMATTING_ONLY) return emptyList()
    return try {
        ContentClassifier.changedRuns(
            old, oldStarts, new, newStarts, policy, parseError, cancellation, oldOwn, newOwn,
            oldLengths, oldShiftSource, newShiftSource,
        )
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
