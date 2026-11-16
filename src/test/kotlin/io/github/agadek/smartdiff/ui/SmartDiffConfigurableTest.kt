package io.github.agadek.smartdiff.ui

import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import io.github.agadek.smartdiff.engine.registry.EngineRegistry
import javax.swing.JComponent

/** The settings page entry for the line comparison (engines/04). */
class SmartDiffConfigurableTest : BasePlatformTestCase() {

    private val settings get() = SmartDiffSettings.getInstance()
    private var saved: SmartDiffSettings.SettingsState? = null

    override fun setUp() {
        super.setUp()
        saved = settings.state
        settings.loadState(SmartDiffSettings.SettingsState())
    }

    override fun tearDown() {
        try {
            saved?.let(settings::loadState)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test engines 04 R1 R21 the checkbox is on by default and is stored`() {
        // engines/04-moved-lines R1, R21
        val configurable = SmartDiffConfigurable()
        try {
            val panel = configurable.createComponent()!!
            configurable.reset()
            val box = findBox(panel, "Show moved lines in files without an engine")
            assertTrue("the checkbox starts on", box.isSelected)
            assertFalse(configurable.isModified)

            box.isSelected = false
            assertTrue("isModified follows the checkbox", configurable.isModified)
            configurable.apply()
            assertFalse("applying stores the setting", settings.compareLinesWithoutEngine)
        } finally {
            configurable.disposeUIResources()
        }

        settings.loadState(SmartDiffSettings.SettingsState().apply { compareLinesWithoutEngine = false })
        val reopened = SmartDiffConfigurable()
        try {
            val panel = reopened.createComponent()!!
            reopened.reset()
            assertFalse("the checkbox reads the stored value", findBox(panel, "Show moved lines in files without an engine").isSelected)
        } finally {
            reopened.disposeUIResources()
        }
    }

    fun `test engines 04 R17 the default engine table has no Lines row`() {
        // engines/04-moved-lines R17: the line engine is not registered on the extension point.
        val engines = EngineRegistry.getInstance(ProjectManager.getInstance().defaultProject).engines()
        assertTrue("the line engine is not registered", engines.none { it.id.value == "smartdiff.lines" })
        assertTrue("no language offers a Lines default", SmartDiffConfigurable.languagesWithEngines(engines).none { (_, choices) ->
            choices.any { it.displayName == "Lines" }
        })
    }

    private fun findBox(panel: JComponent, text: String): JBCheckBox =
        UIUtil.findComponentsOfType(panel, JBCheckBox::class.java).first { it.text == text }
}
