package io.github.agadek.smartdiff.ui

import io.github.agadek.smartdiff.engine.api.EquivalenceRule
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import io.github.agadek.smartdiff.engine.api.ConfidencePolicy
import io.github.agadek.smartdiff.engine.api.Limits
import io.github.agadek.smartdiff.engine.api.RelationshipKind
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
        assertFalse("the outline opens full by default", settings.outlineMinimised)
        assertTrue("the Changes tree shows summaries by default", settings.showChangesSummary)
        assertTrue("the Changes tree shows declarations by default", settings.showChangesDeclarations)
        assertTrue("the Changes tree follows the caret by default", settings.followChangesTreeCaret)
        // outline/11-comment-only-changes R1
        assertFalse("comment-only changes are not marked by default", settings.markCommentOnly)
        // outline/12-reviewed-marks R12
        assertFalse("reviewed changes are shown by default", settings.hideReviewed)
        assertFalse("formatting-only changes are grouped by default", settings.formattingOnlyInline)
        // outline/11-comment-only-changes R1
        assertFalse("comment-only changes are not marked by default", settings.markCommentOnly)
        assertEquals(Limits.DEFAULT, settings.limits)
        assertEquals("nothing is hidden by default", emptySet<ChangeType>(), settings.hiddenChangeTypes)
        assertEquals("the tree filter starts with nothing hidden", emptySet<ChangeType>(), settings.hiddenChangesTreeChangeTypes)
        assertEquals("the container tree is the default outline view", OutlineView.CONTAINER_TREE, settings.outlineView)
        assertEquals("no relationship kind is hidden by default", emptySet<RelationshipKind>(), settings.hiddenRelationshipKinds)
    }

    // outline/12-reviewed-marks R12
    fun `test outline 12 R12 Hide Reviewed is off by default, persisted and a display setting`() {
        settings.loadState(SmartDiffSettings.SettingsState())
        assertFalse("reviewed changes are shown by default", settings.hideReviewed)
        val analysis = settings.analysisRevision
        val tree = settings.treeRevision

        settings.hideReviewed = true

        assertTrue(settings.state.hideReviewed)
        assertTrue(settings.hideReviewed)
        assertEquals("hiding does not compare files again", analysis, settings.analysisRevision)
        assertEquals("hiding does not rebuild the Changes tree", tree, settings.treeRevision)

        settings.loadState(SmartDiffSettings.SettingsState().apply { hideReviewed = true })
        assertTrue("a stored true value is read back", settings.hideReviewed)
    }

    // outline/11-comment-only-changes R1, R2, R12
    fun `test comment-only marking is persisted and is a display setting`() {
        settings.loadState(SmartDiffSettings.SettingsState())
        val analysis = settings.analysisRevision
        val tree = settings.treeRevision

        settings.markCommentOnly = true

        assertTrue(settings.state.markCommentOnly)
        assertTrue(settings.markCommentOnly)
        assertEquals("marking does not compare files again", analysis, settings.analysisRevision)
        assertEquals("marking does not rebuild the Changes tree", tree, settings.treeRevision)

        settings.loadState(SmartDiffSettings.SettingsState().apply { markCommentOnly = true })
        assertTrue("a stored true value is read back", settings.markCommentOnly)
    }

    // outline/11-comment-only-changes R2; settings/01-settings-page R19
    fun `test the settings page round-trips comment-only marking`() {
        settings.loadState(SmartDiffSettings.SettingsState())
        settings.markCommentOnly = true
        val configurable = SmartDiffConfigurable()
        try {
            assertNotNull(configurable.createComponent())
            configurable.reset()
            assertFalse(configurable.isModified)
            configurable.apply()
        } finally {
            configurable.disposeUIResources()
        }
        assertTrue("applying the page keeps the option", settings.markCommentOnly)
    }

    // outline/13 R1, R13; settings/01 R20
    fun `test outline 13 R13 the settings page has the checkbox`() {
        settings.loadState(SmartDiffSettings.SettingsState())
        val configurable = SmartDiffConfigurable()
        try {
            val panel = configurable.createComponent()!!
            configurable.reset()
            assertFalse(configurable.isModified)
            val box = UIUtil.findComponentsOfType(panel, JBCheckBox::class.java)
                .single { it.text == "Mark moved code in the editors" }
            assertTrue("the checkbox starts on", box.isSelected)

            box.isSelected = false
            assertTrue("isModified follows the checkbox", configurable.isModified)
            configurable.apply()
            assertFalse("applying writes the setting", settings.markMovedCode)
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
        }

        settings.loadState(SmartDiffSettings.SettingsState().apply { markMovedCode = false })
        val reopened = SmartDiffConfigurable()
        try {
            val panel = reopened.createComponent()!!
            reopened.reset()
            val box = UIUtil.findComponentsOfType(panel, JBCheckBox::class.java)
                .single { it.text == "Mark moved code in the editors" }
            assertFalse("the checkbox reads the stored value", box.isSelected)
            assertFalse(reopened.isModified)
            box.isSelected = true
            assertTrue(reopened.isModified)
            reopened.apply()
            assertTrue(settings.markMovedCode)
        } finally {
            reopened.disposeUIResources()
        }
    }

    // declarations/10-fine-grained-differencing R1; settings/01 R23
    fun `test declarations 10 R1 the changed-token checkbox is on by default and stored`() {
        settings.loadState(SmartDiffSettings.SettingsState())
        assertTrue(settings.highlightChangedTokens)
        val configurable = SmartDiffConfigurable()
        try {
            val panel = configurable.createComponent()!!
            configurable.reset()
            val box = UIUtil.findComponentsOfType(panel, JBCheckBox::class.java)
                .single { it.text == "Highlight changed tokens in the SMART Diff viewer" }
            assertTrue("the checkbox starts on", box.isSelected)
            box.isSelected = false
            assertTrue(configurable.isModified)
            configurable.apply()
            assertFalse("applying writes the setting", settings.highlightChangedTokens)
            assertFalse("the stored state keeps it", settings.state.highlightChangedTokens)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun `test R2 the outline view is stored and the container tree is the default`() {
        // relationships/06-refactorings-view R2
        settings.loadState(SmartDiffSettings.SettingsState())
        assertEquals(OutlineView.CONTAINER_TREE, settings.outlineView)

        settings.outlineView = OutlineView.REFACTORINGS
        assertEquals("REFACTORINGS", settings.state.outlineView)
        assertEquals(OutlineView.REFACTORINGS, settings.outlineView)

        settings.loadState(SmartDiffSettings.SettingsState().apply { outlineView = "SOMETHING_ELSE" })
        assertEquals("an unknown stored view keeps the container tree", OutlineView.CONTAINER_TREE, settings.outlineView)
    }

    fun `test R14 hidden relationship kinds are stored by name and unknown names are ignored`() {
        // relationships/06-refactorings-view R14
        settings.loadState(SmartDiffSettings.SettingsState())
        settings.hiddenRelationshipKinds = setOf(RelationshipKind.MOVE, RelationshipKind.INLINE)

        assertEquals("MOVE,INLINE", settings.state.hiddenRelationshipKinds)
        assertEquals(setOf(RelationshipKind.MOVE, RelationshipKind.INLINE), settings.hiddenRelationshipKinds)

        settings.loadState(SmartDiffSettings.SettingsState().apply { hiddenRelationshipKinds = "RENAME, UNKNOWN" })
        assertEquals(setOf(RelationshipKind.RENAME), settings.hiddenRelationshipKinds)
    }

    fun `test R2 R14 the outline view and kind filter are display-only`() {
        // relationships/06-refactorings-view R2, R14; settings/01 R14
        settings.loadState(SmartDiffSettings.SettingsState())
        val analysis = settings.analysisRevision
        val tree = settings.treeRevision

        settings.outlineView = OutlineView.REFACTORINGS
        settings.hiddenRelationshipKinds = setOf(RelationshipKind.RENAME)

        assertEquals("a view choice does not compare files again", analysis, settings.analysisRevision)
        assertEquals("a view choice does not rebuild the Changes tree", tree, settings.treeRevision)
    }

    fun `test the changes tree toggles share the settings page values`() {
        // changes-tree/04-hide-and-filter R5
        settings.loadState(SmartDiffSettings.SettingsState())

        settings.showChangesSummary = false
        settings.showChangesDeclarations = false

        assertFalse("the summary toggle writes the settings page value", settings.state.showChangesSummary)
        assertFalse("the declarations toggle writes the settings page value", settings.state.showChangesDeclarations)
        assertFalse(settings.showChangesSummary)
        assertFalse(settings.showChangesDeclarations)
    }

    fun `test the two filters are separate settings`() {
        // changes-tree/04-hide-and-filter R16
        settings.loadState(SmartDiffSettings.SettingsState())

        settings.hiddenChangeTypes = setOf(ChangeType.ADDED)
        settings.hiddenChangesTreeChangeTypes = setOf(ChangeType.MOVED)

        assertEquals("the outline filter keeps its own value", setOf(ChangeType.ADDED), settings.hiddenChangeTypes)
        assertEquals("the tree filter keeps its own value", setOf(ChangeType.MOVED), settings.hiddenChangesTreeChangeTypes)
    }

    fun `test the changes tree filter is stored separately and starts empty`() {
        // changes-tree/04-hide-and-filter R17
        settings.loadState(SmartDiffSettings.SettingsState())
        assertEquals(emptySet<ChangeType>(), settings.hiddenChangesTreeChangeTypes)

        settings.hiddenChangeTypes = setOf(ChangeType.ADDED)
        settings.hiddenChangesTreeChangeTypes = setOf(ChangeType.MOVED, ChangeType.FORMATTING_ONLY)

        assertEquals("MOVED,FORMATTING_ONLY", settings.state.hiddenChangesTreeChangeTypes)
        assertEquals("the outline value is untouched", "ADDED", settings.state.hiddenChangeTypes)

        settings.loadState(SmartDiffSettings.SettingsState().apply { hiddenChangesTreeChangeTypes = "RENAMED, REMOVED" })
        assertEquals("unknown names are ignored", setOf(ChangeType.REMOVED), settings.hiddenChangesTreeChangeTypes)
        assertEquals("the outline filter is still empty", emptySet<ChangeType>(), settings.hiddenChangeTypes)
    }

    fun `test changing the follow setting is display-only`() {
        // settings/01-settings-page R14
        settings.loadState(SmartDiffSettings.SettingsState())
        val analysis = settings.analysisRevision
        val tree = settings.treeRevision

        settings.followChangesTreeCaret = false

        assertFalse(settings.followChangesTreeCaret)
        assertEquals("following does not clear summaries", analysis, settings.analysisRevision)
        assertEquals("following does not rebuild the tree", tree, settings.treeRevision)
    }

    fun `test only the changes tree and analysis settings move the tree revision`() {
        // settings/01-settings-page R14; outline, merge and follow display settings do not rebuild the tree
        settings.loadState(SmartDiffSettings.SettingsState())
        val tree = settings.treeRevision

        settings.followChangesTreeCaret = false
        settings.showOutline = false
        settings.outlineMinimised = true
        settings.mergeGroupedBySide = false
        settings.mergeHideBothSides = true
        settings.hiddenChangeTypes = setOf(ChangeType.ADDED)
        assertEquals("display-only settings keep the tree revision", tree, settings.treeRevision)

        settings.showChangesSummary = false
        assertEquals("a Changes tree display setting moves the tree revision", tree + 1, settings.treeRevision)

        settings.hiddenChangesTreeChangeTypes = setOf(ChangeType.MOVED)
        assertEquals("the tree filter moves the tree revision", tree + 2, settings.treeRevision)
    }

    fun `test only analysis settings move the analysis revision`() {
        // changes-tree/04-hide-and-filter R6, R18: display toggles and filters keep the summary cache
        settings.loadState(SmartDiffSettings.SettingsState())
        val before = settings.analysisRevision

        settings.showChangesSummary = false
        settings.showChangesDeclarations = false
        settings.hiddenChangesTreeChangeTypes = setOf(ChangeType.MOVED)
        settings.hiddenChangeTypes = setOf(ChangeType.ADDED)
        assertEquals("display settings keep the revision", before, settings.analysisRevision)

        settings.formattingOnlyInline = true
        assertEquals("the formatting-only layout moves the revision", before + 1, settings.analysisRevision)

        settings.limits = Limits.DEFAULT.copy(maxChars = Limits.DEFAULT.maxChars - 1)
        assertEquals("an analysis limit moves the revision", before + 2, settings.analysisRevision)
    }

    fun `test outline 02 R14 the minimised state is stored`() {
        // outline/02-outline-toggle R14
        settings.loadState(SmartDiffSettings.SettingsState())
        settings.outlineMinimised = true

        assertTrue(settings.state.outlineMinimised)
        assertTrue(settings.outlineMinimised)
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

    fun `test R15 R30 the confidence thresholds come from the registry and a malformed value keeps the default`() {
        // relationships/02-relationship-model R15, R30
        val key = Registry.get(SmartDiffSettings.CONFIDENCE_KEY)
        val previous = key.asString()
        try {
            assertEquals("the declared default is the policy's default", "0.82,0.7", previous)
            assertEquals(ConfidencePolicy.DEFAULT, settings.confidence)

            key.setValue("0.9,0.8")
            assertEquals(ConfidencePolicy(high = 0.9, medium = 0.8), settings.confidence)

            key.setValue("")
            assertEquals("an empty value keeps the default", ConfidencePolicy.DEFAULT, settings.confidence)

            key.setValue("high,medium")
            assertEquals("a malformed value keeps the default", ConfidencePolicy.DEFAULT, settings.confidence)
            key.setValue("0.9,0.95")
            assertEquals("medium above high keeps the default", ConfidencePolicy.DEFAULT, settings.confidence)
        } finally {
            key.setValue(previous)
        }
    }

    fun `test the settings page lists each dedicated language with an engine`() {
        // languages/15-html R21; languages/13-xml R21; languages/14-markdown R1
        settings.useStructureViewFallback = false
        val engines = EngineRegistry.getInstance(ProjectManager.getInstance().defaultProject).engines()

        val languages = SmartDiffConfigurable.languagesWithEngines(engines).map { (language, _) -> language.id }

        assertEquals(
            listOf("Groovy", "HTML", "JAVA", "JSON", "JSON5", "kotlin", "Markdown", "Properties", "Python", "Scala", "Shell Script", "TOML", "XHTML", "XML", "yaml"),
            languages,
        )
    }

    fun `test the settings page also lists fallback languages while the fallback is on`() {
        settings.useStructureViewFallback = true
        val engines = EngineRegistry.getInstance(ProjectManager.getInstance().defaultProject).engines()

        val languages = SmartDiffConfigurable.languagesWithEngines(engines).map { (language, _) -> language.id }

        assertTrue("JSON is listed through the structure-view fallback: $languages", "JSON" in languages)
        assertTrue("YAML is listed through the structure-view fallback: $languages", "yaml" in languages)
        assertTrue("the dedicated languages are still listed: $languages", listOf("JAVA", "kotlin", "Python", "Scala").all { it in languages })
    }

    fun `test R10 R18 the structure-view fallback setting is on by default and round-trips`() {
        // languages/06-structure-view-fallback R10; settings/01-settings-page R18
        settings.loadState(SmartDiffSettings.SettingsState())
        assertTrue("the fallback is on by default", settings.useStructureViewFallback)

        settings.useStructureViewFallback = false
        val configurable = SmartDiffConfigurable()
        try {
            assertNotNull(configurable.createComponent())
            configurable.reset()
            assertFalse("the page shows the stored value", configurable.isModified)
            configurable.apply()
        } finally {
            configurable.disposeUIResources()
        }
        assertFalse("applying the page keeps the value", settings.useStructureViewFallback)

        settings.useStructureViewFallback = true
        assertTrue("a new settings state defaults to on", SmartDiffSettings.SettingsState().useStructureViewFallback)
    }

    fun `test R2 the settings page round-trips the outline view`() {
        // relationships/06-refactorings-view R2
        settings.loadState(SmartDiffSettings.SettingsState())
        settings.outlineView = OutlineView.REFACTORINGS
        val configurable = SmartDiffConfigurable()
        try {
            assertNotNull(configurable.createComponent())
            configurable.reset()
            assertFalse(configurable.isModified)
            configurable.apply()
        } finally {
            configurable.disposeUIResources()
        }
        assertEquals("applying the page keeps the chosen view", OutlineView.REFACTORINGS, settings.outlineView)
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

    // declarations/08-equivalence-rules R1, R3; settings/01-settings-page R22
    fun `test rewrite-only marking is off and every rule is on by default`() {
        settings.loadState(SmartDiffSettings.SettingsState())
        assertFalse("rewrite-only changes are not marked by default", settings.markRewriteOnly)
        assertEquals(emptySet<String>(), settings.disabledEquivalenceRules)
        assertEquals(EquivalenceRule.entries.toSet(), settings.enabledEquivalenceRules)
        assertEquals("no rules apply while the option is off", emptySet<EquivalenceRule>(), settings.rewriteOnlyRules)
    }

    // declarations/09-kotlin-scala-equivalence-rules R2, R3
    fun `test the Kotlin and Scala rules are on for a user who turned a Java rule off`() {
        settings.loadState(SmartDiffSettings.SettingsState().apply {
            markRewriteOnly = true
            disabledEquivalenceRules = EquivalenceRule.JAVA_STRING_ESCAPES.name
        })
        val added = EquivalenceRule.entries.filter { it.language == "Kotlin" || it.language == "Scala" }
        assertEquals(7, added.size)
        assertTrue(settings.rewriteOnlyRules.containsAll(added))
        assertFalse(EquivalenceRule.JAVA_STRING_ESCAPES in settings.rewriteOnlyRules)

        settings.disabledEquivalenceRules = setOf(EquivalenceRule.KOTLIN_STRING_ESCAPES.name)
        assertTrue("turning a Kotlin rule off leaves Java's on", EquivalenceRule.JAVA_STRING_ESCAPES in settings.rewriteOnlyRules)
        assertTrue("and Scala's", EquivalenceRule.SCALA_STRING_ESCAPES in settings.rewriteOnlyRules)
    }

    // declarations/08-equivalence-rules R1, R2, R3, R17
    fun `test rewrite-only marking and the rules are persisted and are display settings`() {
        settings.loadState(SmartDiffSettings.SettingsState())
        val analysis = settings.analysisRevision
        val tree = settings.treeRevision

        settings.markRewriteOnly = true
        settings.disabledEquivalenceRules = setOf(EquivalenceRule.JAVA_STRING_ESCAPES.name)

        assertTrue(settings.state.markRewriteOnly)
        assertEquals(EquivalenceRule.JAVA_STRING_ESCAPES.name, settings.state.disabledEquivalenceRules)
        assertEquals(
            EquivalenceRule.entries.toSet() - EquivalenceRule.JAVA_STRING_ESCAPES,
            settings.rewriteOnlyRules,
        )
        assertEquals("marking does not compare files again", analysis, settings.analysisRevision)
        assertEquals("marking does not rebuild the Changes tree", tree, settings.treeRevision)

        settings.loadState(SmartDiffSettings.SettingsState().apply {
            markRewriteOnly = true
            disabledEquivalenceRules = "JAVA_NUMERIC_LITERALS, NO_SUCH_RULE"
        })
        assertTrue("a stored true value is read back", settings.markRewriteOnly)
        assertFalse(EquivalenceRule.JAVA_NUMERIC_LITERALS in settings.rewriteOnlyRules)
        assertTrue("a rule no stored name turns off stays on", EquivalenceRule.JAVA_REDUNDANT_PARENTHESES in settings.rewriteOnlyRules)
    }

    // declarations/08-equivalence-rules R2, R3; settings/01-settings-page R22
    fun `test the settings page round-trips rewrite-only marking and its rules`() {
        settings.loadState(SmartDiffSettings.SettingsState())
        val configurable = SmartDiffConfigurable()
        try {
            val panel = configurable.createComponent()!!
            configurable.reset()
            assertFalse(configurable.isModified)
            val boxes = UIUtil.findComponentsOfType(panel, JBCheckBox::class.java)
            val mark = boxes.single { it.text == "Mark rewrite-only changes" }
            // declarations/09 R2: the rule checkboxes appear in declaration order, so the Java, Kotlin and Scala groups
            // follow each other and a display name shared by several languages is told apart by its position.
            val names = EquivalenceRule.entries.map { it.displayName }.toSet()
            val rules = boxes.filter { it.text in names }
            assertEquals(EquivalenceRule.entries.map { it.displayName }, rules.map { it.text })
            val labels = UIUtil.findComponentsOfType(panel, javax.swing.JLabel::class.java).map { it.text }
            val groups = EquivalenceRule.entries.map { it.language }.distinct()
            assertEquals(listOf("Java", "Kotlin", "Scala"), groups)
            assertEquals(groups, labels.filter { it in groups })
            val hide = boxes.single { it.text == ChangeType.REWRITE_ONLY.text }
            assertFalse("the option starts off", mark.isSelected)
            assertTrue("every rule starts on", rules.all { it.isSelected })
            assertTrue("the rules are enabled only with the option", rules.none { it.isEnabled })
            assertFalse("rewrite-only can be hidden only with the option", hide.isEnabled)

            mark.isSelected = true
            assertTrue("the rules follow the option", rules.all { it.isEnabled })
            assertTrue(hide.isEnabled)
            rules[EquivalenceRule.JAVA_REDUNDANT_PARENTHESES.ordinal].isSelected = false
            assertTrue(configurable.isModified)
            configurable.apply()
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
        }
        assertTrue("applying the page writes the option", settings.markRewriteOnly)
        assertEquals(setOf(EquivalenceRule.JAVA_REDUNDANT_PARENTHESES.name), settings.disabledEquivalenceRules)
    }
}
