package io.github.agadek.smartdiff.engine.registry

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
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
class EngineDefaults : PersistentStateComponent<EngineDefaults.DefaultsState> {

    class DefaultsState {
        /** Language id to engine id. */
        var byLanguage: MutableMap<String, String> = LinkedHashMap()
    }

    @Volatile
    private var current = DefaultsState()

    override fun getState(): DefaultsState = current

    override fun loadState(state: DefaultsState) {
        current = state
    }

    fun get(languageId: String): EngineId? = current.byLanguage[languageId]?.let(::EngineId)

    fun set(languageId: String, engineId: EngineId?) {
        val byLanguage = LinkedHashMap(current.byLanguage)
        if (engineId == null) byLanguage.remove(languageId) else byLanguage[languageId] = engineId.value
        current = DefaultsState().also { it.byLanguage = byLanguage }
    }

    companion object {
        fun getInstance(): EngineDefaults = service()
    }
}
