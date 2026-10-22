package io.github.agadek.smartdiff.engine.declarations.psi

import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree

/** Text renderings of declaration trees and results, for provider tests and golden fixtures. */
object Rendering {

    /**
     * The outline as the user would see it (technical specification §10): reported nodes and the containers of reported
     * nodes, one per line, with their labels and the lines they span on each side.
     */
    fun result(result: ComparisonResult, oldText: CharSequence, newText: CharSequence): String = buildString {
        appendLine("status: ${result.status}")
        result.fallbackReason?.let { appendLine("reason: $it") }
        result.fileRegion?.let { appendLine(node(it, oldText, newText)) }
        fun visit(node: ChangeNode, depth: Int) {
            if (!node.isReported) return
            appendLine("  ".repeat(depth) + node(node, oldText, newText))
            node.candidates.forEach { appendLine("  ".repeat(depth + 1) + "candidate: ${subject(it.subject)}  ${lines(it.range, if (node.oldRange == null) oldText else newText)}") }
            node.children.forEach { visit(it, depth + 1) }
        }
        result.changes.forEach { visit(it, 0) }
        result.unanalyzed.forEach { appendLine("unanalyzed: old ${it.old?.let { r -> lines(r, oldText) }}  new ${it.new?.let { r -> lines(r, newText) }}") }
    }

    private fun node(node: ChangeNode, oldText: CharSequence, newText: CharSequence): String {
        val labels = buildList {
            if (node.presence != Presence.MATCHED) add(node.presence.name.lowercase())
            node.renamedFrom?.let { add("rename candidate from ${subject(it)}") }
            if (node.moved) add("moved")
            if (node.content != Content.UNCHANGED) add(node.content.name.lowercase().replace('_', '-'))
            if (node.signatureChanged) add("signature changed")
            node.extractedFrom.forEach { add("extracted from ${name(it.subject)} old ${lines(it.range, oldText)}") }
            if (node.extractedTo.isNotEmpty()) add("code extracted to ${node.extractedTo.joinToString(", ") { name(it) }}")
        }
        val sides = listOfNotNull(node.oldRange?.let { "old ${lines(it, oldText)}" }, node.newRange?.let { "new ${lines(it, newText)}" })
        return (listOf(subject(node.subject)) + labels + sides).joinToString("  ")
    }

    fun subject(subject: Subject): String = when (subject) {
        is Subject.Declaration -> label(subject.kind, (subject.receiver?.let { "$it." } ?: "") + subject.name, subject.signature)
        is Subject.Region -> subject.description
    }

    /** A subject without its kind, as an extraction link names it: `summary(Order)`. */
    private fun name(subject: Subject): String = when (subject) {
        is Subject.Declaration -> (subject.receiver?.let { "$it." } ?: "") + subject.name + (subject.signature?.let { "($it)" } ?: "")
        is Subject.Region -> subject.description
    }

    private fun label(kind: DeclarationKind, name: String, signature: String?): String =
        kind.name.lowercase().replace('_', ' ') + (if (name.isEmpty()) "" else " $name") + (signature?.let { "($it)" } ?: "")

    /** 1-based lines `first-last` of [range]; a range within one line is that line. */
    private fun lines(range: TextRange, text: CharSequence): String {
        fun line(offset: Int) = 1 + (0 until offset).count { text[it] == '\n' }
        val first = line(range.start)
        val last = if (range.length == 0) first else line(range.end - 1)
        return if (first == last) "$first" else "$first-$last"
    }

    /**
     * A declaration tree, one declaration per line: its label, `!` when it has a parse error, and the first line of its
     * source, which shows where its range starts.
     */
    fun tree(tree: DeclarationTree, text: CharSequence): String = buildString {
        fun visit(declaration: Declaration, depth: Int) {
            val firstLine = text.subSequence(declaration.range.start, declaration.range.end).lineSequence().first().trim()
            val error = if (declaration.hasParseError) " !" else ""
            appendLine("  ".repeat(depth) + label(declaration.kind, declaration.name, declaration.signature) + error + "  \"$firstLine\"")
            declaration.children.forEach { visit(it, depth + 1) }
        }
        tree.topLevel.forEach { visit(it, 0) }
        if (tree.fileRegion.hasParseError) appendLine("file region !")
    }
}
