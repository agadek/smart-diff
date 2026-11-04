@file:Suppress("DEPRECATION")

package io.github.agadek.smartdiff.engine.api.contract

import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.Confidence
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Evidence
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Relationship
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject

/**
 * The relationship checks of the engine contract (relationships/02 R16 to R20), one function per requirement, so
 * [ComparisonEngineContract] runs them for every engine and a test can hand one a bad result.
 *
 * A check throws an [AssertionError] naming the case and the relationship that breaks the rule. An engine that returns
 * no relationships passes every check (R22).
 */
object RelationshipChecks {

    /** R16: every endpoint names a subject the result tree shows, on its side, at its range. */
    fun endpointsInResultTree(case: ContractCase, result: ComparisonResult) {
        val nodes = nodes(result)
        for (relationship in result.relationships) {
            for (endpoint in relationship.old) {
                val node = nodes.filter { it.oldRange == endpoint.range }
                check(case, node.size == 1) { "old endpoint $endpoint of $relationship names ${node.size} nodes, not 1" }
                check(case, oldSubjectMatches(node.single(), endpoint.subject)) {
                    "old endpoint ${endpoint.subject} of $relationship is not the old side of ${node.single()}"
                }
            }
            for (endpoint in relationship.new) {
                val node = nodes.filter { it.newRange == endpoint.range }
                check(case, node.size == 1) { "new endpoint $endpoint of $relationship names ${node.size} nodes, not 1" }
                check(case, node.single().subject == endpoint.subject) {
                    "new endpoint ${endpoint.subject} of $relationship is not ${node.single()}"
                }
            }
        }
    }

    /** R17: every endpoint and evidence range lies inside its side's text. */
    fun rangesInsideText(case: ContractCase, result: ComparisonResult) {
        for (relationship in result.relationships) {
            relationship.old.forEach { check(case, it.range.end <= case.oldText.length) { "old endpoint ${it.range} of $relationship is outside ${case.oldText.length} characters" } }
            relationship.new.forEach { check(case, it.range.end <= case.newText.length) { "new endpoint ${it.range} of $relationship is outside ${case.newText.length} characters" } }
            for (evidence in relationship.evidence.filterIsInstance<Evidence.Content>()) {
                check(case, evidence.oldRange.end <= case.oldText.length) { "old content ${evidence.oldRange} of $relationship is outside the text" }
                check(case, evidence.newRange.end <= case.newText.length) { "new content ${evidence.newRange} of $relationship is outside the text" }
            }
        }
    }

    /** R18: an extraction's two fields agree with one `EXTRACT` relationship, and no relationship is listed twice. */
    fun extractionFieldsAgree(case: ContractCase, result: ComparisonResult) {
        check(case, result.relationships.distinct().size == result.relationships.size) {
            "a relationship is listed twice: ${result.relationships.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}"
        }
        val extractions = result.relationships.filter { RelationshipKind.EXTRACT in it.kinds }
        for (node in nodes(result)) {
            for (origin in node.extractedFrom) {
                // The Origin names the source as the matched node shows it, so the endpoint's subject may differ when
                // the source was renamed: the old declaration range and the target's range identify the link.
                val links = extractions.filter { link ->
                    link.old.size == 1 && link.new.size == 1 &&
                        link.old.first().range == origin.declarationRange &&
                        link.new.first().range == node.newRange
                }
                check(case, links.size == 1) { "extractedFrom ${origin.subject} of $node matches ${links.size} relationships, not 1" }
                check(case, contentOf(links.single()).oldRange == origin.range) {
                    "extractedFrom ${origin.range} of $node disagrees with ${contentOf(links.single()).oldRange}"
                }
            }
            for (target in node.extractedTo) {
                check(case, extractions.any { it.old.first().range == node.oldRange && it.new.first().subject == target }) {
                    "extractedTo $target of $node has no EXTRACT relationship"
                }
            }
        }
        // The other direction: a high or medium link sets the fields on both ends; a low one sets none (R13).
        for (link in extractions.filter { it.confidence != Confidence.LOW }) {
            val source = nodes(result).singleOrNull { it.oldRange == link.old.first().range }
            val target = nodes(result).singleOrNull { it.newRange == link.new.first().range }
            check(case, source != null && target != null) { "EXTRACT $link has no node on one side" }
            check(case, target!!.extractedFrom.any { it.declarationRange == link.old.first().range && it.range == contentOf(link).oldRange }) {
                "EXTRACT $link sets no extractedFrom on $target"
            }
            check(case, source!!.extractedTo.contains(link.new.first().subject)) { "EXTRACT $link sets no extractedTo on $source" }
        }
    }

    /** R19: the list, its kinds, its endpoints and its evidence come in the order the model fixes. */
    fun fixedOrder(case: ContractCase, result: ComparisonResult) {
        check(case, result.relationships == result.relationships.sortedWith(ORDER)) {
            "relationships are not ordered by old start, new start, first kind"
        }
        for (relationship in result.relationships) {
            check(case, relationship.kinds.toList() == relationship.kinds.sortedBy { it.ordinal }) {
                "kinds of $relationship are not in enum order"
            }
            check(case, relationship.old == relationship.old.sortedBy { it.range.start }) { "old endpoints of $relationship are not sorted" }
            check(case, relationship.new == relationship.new.sortedBy { it.range.start }) { "new endpoints of $relationship are not sorted" }
            check(case, relationship.evidence == relationship.evidence.sortedBy(::evidenceOrder)) { "evidence of $relationship is not in type order" }
        }
    }

    /** R20: nothing-hidden holds, and gives the same answer with the relationships removed. */
    fun nothingHiddenWithoutRelationships(case: ContractCase, result: ComparisonResult) {
        if (result.status != Status.ANALYZED && result.status != Status.PARTIAL) return
        val withRelationships = NothingHidden.violation(case.oldText, case.newText, result)
        val withoutRelationships = NothingHidden.violation(case.oldText, case.newText, result.copy(relationships = emptyList()))
        check(case, withRelationships == withoutRelationships) {
            "removing the relationships changes nothing-hidden from '$withRelationships' to '$withoutRelationships'"
        }
        check(case, withRelationships == null) { "nothing hidden is violated: $withRelationships" }
    }

    /**
     * Whether [subject] can be the old side of [node], whose range already identifies its declaration. It is the node's
     * own subject, the rename's old subject, an old kind of a kind change, or the same kind and name: a declaration
     * that moved can also have changed its signature, so its endpoint carries the old signature while the node's
     * subject carries the new one. A constructor follows its class's rename without a rename of its own, so its old
     * name is the old class's and the node does not carry it.
     */
    private fun oldSubjectMatches(node: ChangeNode, subject: Subject): Boolean {
        if (node.subject == subject || node.renamedFrom == subject) return true
        val endpoint = subject as? Subject.Declaration ?: return false
        if (node.kindChangedFrom != null && endpoint.kind == node.kindChangedFrom) return true
        val nodeSubject = node.subject as? Subject.Declaration ?: return false
        if (endpoint.kind == nodeSubject.kind && endpoint.name == nodeSubject.name) return true
        return nodeSubject.kind == DeclarationKind.CONSTRUCTOR && endpoint.kind == DeclarationKind.CONSTRUCTOR
    }

    private fun contentOf(relationship: Relationship): Evidence.Content =
        relationship.evidence.filterIsInstance<Evidence.Content>().single()

    private fun nodes(result: ComparisonResult): List<ChangeNode> {
        val nodes = mutableListOf<ChangeNode>()
        fun walk(node: ChangeNode) {
            nodes += node
            node.children.forEach(::walk)
        }
        result.fileRegion?.let(::walk)
        result.changes.forEach(::walk)
        return nodes
    }

    /** relationships/02 R19, written out here so the check does not read the engine's own comparator. */
    private val ORDER = compareBy<Relationship>(
        { it.old.first().range.start },
        { it.new.first().range.start },
        { it.kinds.first().ordinal },
    )

    private fun evidenceOrder(evidence: Evidence): Int = when (evidence) {
        is Evidence.Name -> 0
        is Evidence.Content -> 1
        is Evidence.Structure -> 2
        is Evidence.Position -> 3
        is Evidence.Score -> 4
    }

    private inline fun check(case: ContractCase, condition: Boolean, message: () -> String) {
        if (!condition) throw AssertionError("case $case: ${message()}")
    }
}
