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
import io.github.agadek.smartdiff.engine.api.Limits

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

        /** Whether the Local Changes and Commit tree shows each file's structural summary. */
        var showChangesSummary: Boolean = true

        /** Show formatting-only changes inline instead of in a collapsed group per container. */
        var formattingOnlyInline: Boolean = false

        /** List merge rows grouped by side instead of as the base's container tree. */
        var mergeGroupedBySide: Boolean = true

        /** Hide rows changed on both sides from the merge container tree. */
        var mergeHideBothSides: Boolean = false

        /** The [ChangeType] names hidden from the outline, comma-separated. */
        var hiddenChangeTypes: String = ""
        var maxChars: Int = Limits.DEFAULT.maxChars
        var maxDeclarations: Int = Limits.DEFAULT.maxDeclarations
        var timeoutMillis: Long = Limits.DEFAULT.timeoutMillis
    }

    @Volatile
    private var current = SettingsState()

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

    var showChangesSummary: Boolean
        get() = current.showChangesSummary
        set(value) = update { showChangesSummary = value }

    var formattingOnlyInline: Boolean
        get() = current.formattingOnlyInline
        set(value) = update { formattingOnlyInline = value }

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
                thisLogger().warn("Ignoring malformed $CONFIDENCE_KEY '$raw', expected high,medium such as 0.81,0.7; using the default")
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
        current = SettingsState().also {
            it.showOutline = old.showOutline
            it.showChangesSummary = old.showChangesSummary
            it.formattingOnlyInline = old.formattingOnlyInline
            it.mergeGroupedBySide = old.mergeGroupedBySide
            it.mergeHideBothSides = old.mergeHideBothSides
            it.hiddenChangeTypes = old.hiddenChangeTypes
            it.maxChars = old.maxChars
            it.maxDeclarations = old.maxDeclarations
            it.timeoutMillis = old.timeoutMillis
            it.change()
        }
    }

    /** Told after the settings page is applied, so views that show results outside a diff can update. */
    fun interface Listener {
        fun settingsChanged()
    }

    companion object {
        /** The registry key that sets [confidence] in a running IDE; plugin.xml declares it with the default `0.81,0.7`. */
        const val CONFIDENCE_KEY = "smartdiff.relationships.confidence"

        @Topic.AppLevel
        val CHANGED: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(): SmartDiffSettings = service()
    }
}
