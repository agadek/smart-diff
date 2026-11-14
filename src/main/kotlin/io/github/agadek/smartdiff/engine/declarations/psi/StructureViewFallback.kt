package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.openapi.application.ApplicationManager
import io.github.agadek.smartdiff.engine.api.StructureViewFallbackSetting

/**
 * Reads the UI-owned structure-view fallback setting without a compile-time dependency on `ui` (languages/06 R1, R10).
 * The UI registers an application service implementing [StructureViewFallbackSetting]; when no service is registered,
 * for example in engine unit tests, the setting defaults to on.
 */
internal object StructureViewFallback {

    fun isEnabled(): Boolean = try {
        ApplicationManager.getApplication()
            ?.getService(StructureViewFallbackSetting::class.java)
            ?.isEnabled() ?: StructureViewFallbackSetting.DEFAULT_ENABLED
    } catch (_: Exception) {
        StructureViewFallbackSetting.DEFAULT_ENABLED
    }
}
