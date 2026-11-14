package io.github.agadek.smartdiff.ui

import io.github.agadek.smartdiff.engine.api.StructureViewFallbackSetting

/**
 * Exposes [SmartDiffSettings.useStructureViewFallback] to the engine without the engine importing `ui`
 * (languages/06 R1, R10). Registered in `plugin.xml` under the engine API interface.
 */
class SmartDiffStructureViewFallbackSetting : StructureViewFallbackSetting {
    override fun isEnabled(): Boolean = SmartDiffSettings.getInstance().useStructureViewFallback
}
