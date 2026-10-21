package io.github.agadek.smartdiff.engine.registry

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import io.github.agadek.smartdiff.engine.api.EngineId

/**
 * The user's default engine per language id (technical specification §4, §11). An id stays stored even while its
 * engine is not registered, so it applies again when the engine comes back.
 */
@Service(Service.Level.APP)
@State(name = "SmartDiffEngineDefaults", storages = [Storage("smartdiff.xml")])
class EngineDefaults : SimplePersistentStateComponent<EngineDefaults.DefaultsState>(DefaultsState()) {

    class DefaultsState : BaseState() {
        /** Language id to engine id. */
        var byLanguage by map<String, String>()
    }

    fun get(languageId: String): EngineId? = state.byLanguage[languageId]?.let(::EngineId)

    fun set(languageId: String, engineId: EngineId?) {
        if (engineId == null) state.byLanguage.remove(languageId) else state.byLanguage[languageId] = engineId.value
        state.intIncrementModificationCount()
    }

    companion object {
        fun getInstance(): EngineDefaults = service()
    }
}
