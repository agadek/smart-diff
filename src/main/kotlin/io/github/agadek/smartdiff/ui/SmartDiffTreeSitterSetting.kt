package io.github.agadek.smartdiff.ui

import io.github.agadek.smartdiff.engine.api.TreeSitterSetting

/**
 * Exposes [SmartDiffSettings.treeSitterLanguages] to the Tree-sitter engine without the engine importing `ui`
 * (engines/05 R1, R31). Registered in `plugin.xml` under the engine API interface.
 */
class SmartDiffTreeSitterSetting : TreeSitterSetting {
    override fun isEnabled(): Boolean = SmartDiffSettings.getInstance().treeSitterLanguages
}
