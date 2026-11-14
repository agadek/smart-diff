package io.github.agadek.smartdiff.engine.api

/** Notes a result carries for the outline (technical specification §5); one constant so every layer names them alike. */
object AnalysisNote {
    /** The result came from the IDE's structure view rather than a dedicated provider (languages/06 R8). */
    const val STRUCTURE_VIEW = "structure view"
}

/**
 * The persisted switch for the structure-view fallback (languages/06 R1, R10). The UI registers an application service
 * with this interface, so engine code can read the setting without depending on a `ui` class; while no service is
 * registered the default is on.
 */
interface StructureViewFallbackSetting {
    fun isEnabled(): Boolean

    companion object {
        const val DEFAULT_ENABLED: Boolean = true
    }
}
