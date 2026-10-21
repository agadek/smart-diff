package io.github.agadek.smartdiff.ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
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

        /** Show formatting-only changes inline instead of in a collapsed group per container. */
        var formattingOnlyInline: Boolean = false
        var maxChars: Int = Limits.DEFAULT.maxChars
        var maxDeclarations: Int = Limits.DEFAULT.maxDeclarations
        var timeoutMillis: Long = Limits.DEFAULT.timeoutMillis
    }

    @Volatile
    private var current = SettingsState()

    override fun getState(): SettingsState = current

    override fun loadState(state: SettingsState) {
        current = state
    }

    var showOutline: Boolean
        get() = current.showOutline
        set(value) = update { showOutline = value }

    var formattingOnlyInline: Boolean
        get() = current.formattingOnlyInline
        set(value) = update { formattingOnlyInline = value }

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

    /** Replaces the state rather than changing it, so a reader on another thread sees either the old or the new values. */
    private fun update(change: SettingsState.() -> Unit) {
        val old = current
        current = SettingsState().also {
            it.showOutline = old.showOutline
            it.formattingOnlyInline = old.formattingOnlyInline
            it.maxChars = old.maxChars
            it.maxDeclarations = old.maxDeclarations
            it.timeoutMillis = old.timeoutMillis
            it.change()
        }
    }

    companion object {
        fun getInstance(): SmartDiffSettings = service()
    }
}
