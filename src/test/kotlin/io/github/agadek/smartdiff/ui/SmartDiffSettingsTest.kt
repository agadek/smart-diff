package io.github.agadek.smartdiff.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Limits
import io.github.agadek.smartdiff.engine.registry.EngineRegistry

/** Settings | Tools | SMART Diff (technical specification §11). */
class SmartDiffSettingsTest : BasePlatformTestCase() {

    private val settings get() = SmartDiffSettings.getInstance()
    private var saved: SmartDiffSettings.SettingsState? = null

    override fun setUp() {
        super.setUp()
        saved = settings.state
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

    fun `test defaults`() {
        settings.loadState(SmartDiffSettings.SettingsState())

        assertTrue("the outline opens by default", settings.showOutline)
        assertTrue("the Changes tree shows summaries by default", settings.showChangesSummary)
        assertFalse("formatting-only changes are grouped by default", settings.formattingOnlyInline)
        assertEquals(Limits.DEFAULT, settings.limits)
        assertEquals("nothing is hidden by default", emptySet<ChangeType>(), settings.hiddenChangeTypes)
    }

    fun `test hidden change types are stored by name and unknown names are ignored`() {
        settings.loadState(SmartDiffSettings.SettingsState())
        settings.hiddenChangeTypes = setOf(ChangeType.MOVED, ChangeType.FORMATTING_ONLY)

        assertEquals("MOVED,FORMATTING_ONLY", settings.state.hiddenChangeTypes)
        assertEquals(setOf(ChangeType.MOVED, ChangeType.FORMATTING_ONLY), settings.hiddenChangeTypes)

        settings.loadState(SmartDiffSettings.SettingsState().apply { hiddenChangeTypes = "RENAMED, ADDED" })
        assertEquals(setOf(ChangeType.ADDED), settings.hiddenChangeTypes)
    }

    fun `test out-of-range limits fall back to their defaults`() {
        settings.loadState(SmartDiffSettings.SettingsState().apply { maxChars = 0; timeoutMillis = -1 })

        assertEquals(Limits.DEFAULT, settings.limits)
    }

    fun `test the settings page lists each language with an engine`() {
        val engines = EngineRegistry.getInstance(ProjectManager.getInstance().defaultProject).engines()

        val languages = SmartDiffConfigurable.languagesWithEngines(engines).map { (language, _) -> language.id }

        assertEquals(listOf("JAVA", "kotlin"), languages)
    }

    fun `test the settings page opens and saves without changes`() {
        val configurable = SmartDiffConfigurable()
        try {
            assertNotNull(configurable.createComponent())
            configurable.reset()
            assertFalse(configurable.isModified)
            configurable.apply()
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun `test the settings page is registered under Tools`() {
        val page = Configurable.APPLICATION_CONFIGURABLE.extensionList.singleOrNull { it.id == "io.github.agadek.smartdiff.settings" }

        assertNotNull("the settings page is not registered", page)
        assertEquals("tools", page!!.parentId)
    }
}
