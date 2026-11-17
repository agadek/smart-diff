package io.github.agadek.smartdiff.ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.messages.Topic
import io.github.agadek.smartdiff.engine.api.ConfidencePolicy
import io.github.agadek.smartdiff.engine.api.EquivalenceRule
import io.github.agadek.smartdiff.engine.api.Limits
import io.github.agadek.smartdiff.engine.api.RelationshipKind

/**
 * Application-level settings under Settings | Tools | SMART Diff (technical specification §11). The default engine per
 * language is stored by the registry's `EngineDefaults`.
 */
@Service(Service.Level.APP)
@State(name = "SmartDiffSettings", storages = [Storage("smartdiff.xml")])
class SmartDiffSettings : PersistentStateComponent<SmartDiffSettings.SettingsState> {

    class SettingsState {
        /** Whether the outline toggle starts on in a new diff window. */
        var showOutline: Boolean = true

        /** Whether a visible outline starts minimised to its strip in a new diff window (outline/02 R14). */
        var outlineMinimised: Boolean = false

        /** Whether the Local Changes and Commit tree shows each file's structural summary. */
        var showChangesSummary: Boolean = true

        /** Whether the Local Changes and Commit tree shows each file's declaration rows. */
        var showChangesDeclarations: Boolean = true

        /** Whether the Local Changes and Commit tree follows the caret in an editor (changes-tree/05 R4). */
        var followChangesTreeCaret: Boolean = true

        /** Show formatting-only changes inline instead of in a collapsed group per container. */
        var formattingOnlyInline: Boolean = false

        /** Label changed declarations whose only difference is in comments as comment-only in the outline (outline/11 R1). */
        var markCommentOnly: Boolean = false

        /** Label changed declarations whose only differences are rewrites an enabled equivalence rule names (declarations/08 R1). */
        var markRewriteOnly: Boolean = false

        /**
         * The [EquivalenceRule] names the user turned off, comma-separated; a rule added later is on by default
         * (declarations/08 R3).
         */
        var disabledEquivalenceRules: String = ""

        /** Leave reviewed changes out of the outline (outline/12 R12). Off by default. */
        var hideReviewed: Boolean = false
        /** Mark moved declarations in both editors while the outline shows a result (outline/13 R1). */
        var markMovedCode: Boolean = true

        /** List merge rows grouped by side instead of as the base's container tree. */
        var mergeGroupedBySide: Boolean = true

        /** Hide rows changed on both sides from the merge container tree. */
        var mergeHideBothSides: Boolean = false

        /** The [ChangeType] names hidden from the outline, comma-separated. */
        var hiddenChangeTypes: String = ""

        /** The [OutlineView] name the outline starts in (relationships/06 R2); `CONTAINER_TREE` by default. */
        var outlineView: String = OutlineView.CONTAINER_TREE.name

        /** The [RelationshipKind] names hidden from the refactorings view, comma-separated (relationships/06 R14). */
        var hiddenRelationshipKinds: String = ""

        /** The [ChangeType] names hidden from the Changes tree, comma-separated and separate from the outline's. */
        var hiddenChangesTreeChangeTypes: String = ""
        /** Analyze languages without a dedicated provider through the IDE's structure view (languages/06 R10). */
        var useStructureViewFallback: Boolean = true

        /** Compare two-sided diffs of files no engine supports by lines, showing moved blocks (engines/04 R1, R21). */
        var compareLinesWithoutEngine: Boolean = true
        var maxChars: Int = Limits.DEFAULT.maxChars
        var maxDeclarations: Int = Limits.DEFAULT.maxDeclarations
        var timeoutMillis: Long = Limits.DEFAULT.timeoutMillis
    }

    @Volatile
    private var current = SettingsState()

    /** Moves when a setting that changes what a summary contains changes; display toggles and filters do not move it. */
    @Volatile
    var analysisRevision: Long = 0
        private set

    /**
     * Moves when a setting the Changes tree shows or analyzes changes, so the settings listener can rebuild the tree
     * only when something other than following or another display-only setting changed (settings/01 R14).
     */
    @Volatile
    var treeRevision: Long = 0
        private set

    /** The last malformed registry value that was logged, so one bad value logs one warning (R30). */
    @Volatile
    private var warnedConfidence: String? = null

    override fun getState(): SettingsState = current

    override fun loadState(state: SettingsState) {
        current = state
    }

    var showOutline: Boolean
        get() = current.showOutline
        set(value) = update { showOutline = value }

    /** Whether the next visible outline starts as its strip; the panel remembers the last choice (outline/02 R14). */
    var outlineMinimised: Boolean
        get() = current.outlineMinimised
        set(value) = update { outlineMinimised = value }

    var showChangesSummary: Boolean
        get() = current.showChangesSummary
        set(value) = update { showChangesSummary = value }

    /** Whether the Changes tree shows its declaration rows under each summarized file; the tree's "Declarations" toggle. */
    var showChangesDeclarations: Boolean
        get() = current.showChangesDeclarations
        set(value) = update { showChangesDeclarations = value }

    /** Whether the Changes tree selects the declaration under the editor caret (changes-tree/05 R4). Display-only. */
    var followChangesTreeCaret: Boolean
        get() = current.followChangesTreeCaret
        set(value) = update { followChangesTreeCaret = value }

    var formattingOnlyInline: Boolean
        get() = current.formattingOnlyInline
        set(value) = update { formattingOnlyInline = value }

    /** Whether the outline labels comment-only changes (outline/11 R1, R2). Display-only: no new comparison (R12). */
    var markCommentOnly: Boolean
        get() = current.markCommentOnly
        set(value) = update { markCommentOnly = value }

    /** Whether the outline labels rewrite-only changes (declarations/08 R1, R2). Display-only: no new comparison (R17). */
    var markRewriteOnly: Boolean
        get() = current.markRewriteOnly
        set(value) = update { markRewriteOnly = value }

    /** The names of the equivalence rules the user turned off (declarations/08 R3). Display-only (R17). */
    var disabledEquivalenceRules: Set<String>
        get() = current.disabledEquivalenceRules.split(',').map { it.trim() }.filterTo(LinkedHashSet()) { it.isNotEmpty() }
        set(value) = update { disabledEquivalenceRules = value.map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(",") }

    /** The equivalence rules that are on, whether or not "Mark rewrite-only changes" is (declarations/08 R3). */
    val enabledEquivalenceRules: Set<EquivalenceRule>
        get() = disabledEquivalenceRules.let { disabled -> EquivalenceRule.entries.filterTo(LinkedHashSet()) { it.name !in disabled } }

    /**
     * The rules the outline marks rewrite-only changes with: the enabled rules while "Mark rewrite-only changes" is on,
     * none while it is off (declarations/08 R1, R4, R7).
     */
    val rewriteOnlyRules: Set<EquivalenceRule>
        get() = if (markRewriteOnly) enabledEquivalenceRules else emptySet()

    /** Whether the outline leaves reviewed changes out; a display setting for outline/10 R5 (outline/12 R12). */
    var hideReviewed: Boolean
        get() = current.hideReviewed
        set(value) = update { hideReviewed = value }
    /** Whether the editors show moved-code markers (outline/13 R1). Display-only: no new comparison (R12). */
    var markMovedCode: Boolean
        get() = current.markMovedCode
        set(value) = update { markMovedCode = value }

    var mergeGroupedBySide: Boolean
        get() = current.mergeGroupedBySide
        set(value) = update { mergeGroupedBySide = value }

    var mergeHideBothSides: Boolean
        get() = current.mergeHideBothSides
        set(value) = update { mergeHideBothSides = value }

    /** The change types hidden from every outline; a stored name that is no longer a type is ignored. */
    var hiddenChangeTypes: Set<ChangeType>
        get() = current.hiddenChangeTypes.split(',').mapNotNullTo(LinkedHashSet()) { name -> ChangeType.entries.firstOrNull { it.name == name.trim() } }
        set(value) = update { hiddenChangeTypes = ChangeType.entries.filter { it in value }.joinToString(",") { it.name } }

    /** The change types hidden from the Changes tree only; a stored name that is no longer a type is ignored. */
    var hiddenChangesTreeChangeTypes: Set<ChangeType>
        get() = current.hiddenChangesTreeChangeTypes.split(',').mapNotNullTo(LinkedHashSet()) { name -> ChangeType.entries.firstOrNull { it.name == name.trim() } }
        set(value) = update { hiddenChangesTreeChangeTypes = ChangeType.entries.filter { it in value }.joinToString(",") { it.name } }

    /** The outline's view, the container tree or the refactorings view (relationships/06 R1, R2). */
    var outlineView: OutlineView
        get() = OutlineView.entries.firstOrNull { it.name == current.outlineView } ?: OutlineView.CONTAINER_TREE
        set(value) = update { outlineView = value.name }

    /** The relationship kinds hidden from the refactorings view; a stored name that is no longer a kind is ignored. */
    var hiddenRelationshipKinds: Set<RelationshipKind>
        get() = current.hiddenRelationshipKinds.split(',').mapNotNullTo(LinkedHashSet()) { name ->
            RelationshipKind.entries.firstOrNull { it.name == name.trim() }
        }
        set(value) = update { hiddenRelationshipKinds = RelationshipKind.entries.filter { it in value }.joinToString(",") { it.name } }

    /** Analyze languages without a dedicated provider through the IDE's structure view (languages/06 R10). */
    var useStructureViewFallback: Boolean
        get() = current.useStructureViewFallback
        set(value) = update { useStructureViewFallback = value }

    /** Compare two-sided diffs of files no engine supports by lines, showing moved blocks (engines/04 R1, R21). */
    var compareLinesWithoutEngine: Boolean
        get() = current.compareLinesWithoutEngine
        set(value) = update { compareLinesWithoutEngine = value }

    /** The limits passed to engines; a stored value that is out of range falls back to its default. */
    var limits: Limits
        get() = Limits(
            maxChars = current.maxChars.takeIf { it > 0 } ?: Limits.DEFAULT.maxChars,
            maxDeclarations = current.maxDeclarations.takeIf { it > 0 } ?: Limits.DEFAULT.maxDeclarations,
            timeoutMillis = current.timeoutMillis.takeIf { it > 0 } ?: Limits.DEFAULT.timeoutMillis,
        )
        set(value) = update {
            maxChars = value.maxChars
            maxDeclarations = value.maxDeclarations
            timeoutMillis = value.timeoutMillis
        }

    /**
     * The [ConfidencePolicy] passed to engines beside [limits], from the `smartdiff.relationships.confidence` registry key
     * (relationships/02 R14, R15). An empty value leaves the default; a malformed one logs a single warning and leaves
     * the default too (R30).
     */
    val confidence: ConfidencePolicy
        get() {
            val raw = registryConfidence()
            val policy = ConfidencePolicy.parse(raw)
            if (policy == null && !raw.isNullOrBlank() && raw != warnedConfidence) {
                warnedConfidence = raw
                thisLogger().warn("Ignoring malformed $CONFIDENCE_KEY '$raw', expected high,medium such as 0.82,0.7; using the default")
            }
            return policy ?: ConfidencePolicy.DEFAULT
        }

    private fun registryConfidence(): String? = try {
        Registry.get(CONFIDENCE_KEY).asString()
    } catch (e: Exception) {
        null
    }

    /** Replaces the state rather than changing it, so a reader on another thread sees either the old or the new values. */
    private fun update(change: SettingsState.() -> Unit) {
        val old = current
        val next = SettingsState().also {
            it.showOutline = old.showOutline
            it.outlineMinimised = old.outlineMinimised
            it.showChangesSummary = old.showChangesSummary
            it.showChangesDeclarations = old.showChangesDeclarations
            it.followChangesTreeCaret = old.followChangesTreeCaret
            it.formattingOnlyInline = old.formattingOnlyInline
            it.markCommentOnly = old.markCommentOnly
            it.markRewriteOnly = old.markRewriteOnly
            it.disabledEquivalenceRules = old.disabledEquivalenceRules
            it.hideReviewed = old.hideReviewed
            it.markMovedCode = old.markMovedCode
            it.mergeGroupedBySide = old.mergeGroupedBySide
            it.mergeHideBothSides = old.mergeHideBothSides
            it.hiddenChangeTypes = old.hiddenChangeTypes
            it.hiddenChangesTreeChangeTypes = old.hiddenChangesTreeChangeTypes
            it.outlineView = old.outlineView
            it.hiddenRelationshipKinds = old.hiddenRelationshipKinds
            it.useStructureViewFallback = old.useStructureViewFallback
            it.compareLinesWithoutEngine = old.compareLinesWithoutEngine
            it.maxChars = old.maxChars
            it.maxDeclarations = old.maxDeclarations
            it.timeoutMillis = old.timeoutMillis
            it.change()
        }
        current = next
        if (old.analysisSignature() != next.analysisSignature()) analysisRevision++
        if (old.treeSignature() != next.treeSignature()) treeRevision++
    }

    /** The settings that change what a summary contains; display toggles, filters and merge options do not. */
    private fun SettingsState.analysisSignature(): List<Any> =
        listOf(maxChars, maxDeclarations, timeoutMillis, formattingOnlyInline, useStructureViewFallback)

    /**
     * The settings the Changes tree shows or analyzes. Outline, merge and follow settings are display-only for this
     * tree, so changing them keeps the tree and its summaries as they are (settings/01 R14).
     */
    private fun SettingsState.treeSignature(): List<Any> =
        listOf(
            showChangesSummary, showChangesDeclarations, hiddenChangesTreeChangeTypes,
            formattingOnlyInline, maxChars, maxDeclarations, timeoutMillis, useStructureViewFallback,
        )

    /** Told after the settings page is applied, so views that show results outside a diff can update. */
    fun interface Listener {
        fun settingsChanged()
    }

    companion object {
        /** The registry key that sets [confidence] in a running IDE; plugin.xml declares it with the default `0.82,0.7`. */
        const val CONFIDENCE_KEY = "smartdiff.relationships.confidence"

        @Topic.AppLevel
        val CHANGED: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(): SmartDiffSettings = service()
    }
}
