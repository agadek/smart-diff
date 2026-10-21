package io.github.agadek.smartdiff.ui

import com.intellij.lang.Language
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.EngineId
import io.github.agadek.smartdiff.engine.registry.EngineDefaults
import io.github.agadek.smartdiff.engine.registry.EngineRegistry
import javax.swing.JList

/** Settings | Tools | SMART Diff (technical specification §11). */
class SmartDiffConfigurable : BoundConfigurable("SMART Diff") {

    override fun createPanel(): DialogPanel {
        val settings = SmartDiffSettings.getInstance()
        val defaults = EngineDefaults.getInstance()
        val engines = EngineRegistry.getInstance(ProjectManager.getInstance().defaultProject).engines()
        return panel {
            row {
                checkBox("Show structural outline automatically")
                    .bindSelected(settings::showOutline)
                    .comment("When off, the outline starts hidden; the toolbar button in each diff still shows it.")
            }
            buttonsGroup("Formatting-only changes:") {
                row { radioButton("Collapsed in a group", false) }
                row { radioButton("Inline", true) }
            }.bind(settings::formattingOnlyInline)
            buttonsGroup("Hide from the outline:") {
                for (type in ChangeType.entries) {
                    row {
                        checkBox(type.text).bindSelected(
                            { type in settings.hiddenChangeTypes },
                            { hide -> settings.hiddenChangeTypes = if (hide) settings.hiddenChangeTypes + type else settings.hiddenChangeTypes - type },
                        )
                    }
                }
                row {
                    comment("Hidden changes still count in the file summary. Uncertain and unanalyzed changes are always shown.")
                }
            }

            group("Default Comparison Engine") {
                val languages = languagesWithEngines(engines)
                if (languages.isEmpty()) row { comment("No installed comparison engine supports a language of this IDE.") }
                for ((language, candidates) in languages) {
                    val stored = defaults.get(language.id)?.value
                    // A stored engine that is not installed stays selectable, so saving the page does not drop it.
                    val choices = listOf<String?>(null) + candidates.map { it.id.value } +
                        listOfNotNull(stored?.takeIf { id -> candidates.none { it.id.value == id } })
                    row("${language.displayName}:") {
                        comboBox(choices, engineRenderer(candidates))
                            .bindItem({ defaults.get(language.id)?.value }, { defaults.set(language.id, it?.let(::EngineId)) })
                    }
                }
            }

            group("Limits") {
                row("Largest file to analyze (characters):") {
                    intTextField(1..Int.MAX_VALUE)
                        .bindIntText({ settings.limits.maxChars }, { settings.limits = settings.limits.copy(maxChars = it) })
                }
                row("Most declarations per side:") {
                    intTextField(1..Int.MAX_VALUE)
                        .bindIntText({ settings.limits.maxDeclarations }, { settings.limits = settings.limits.copy(maxDeclarations = it) })
                }
                row("Analysis time limit (ms):") {
                    intTextField(1..Int.MAX_VALUE)
                        .bindIntText(
                            { settings.limits.timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() },
                            { settings.limits = settings.limits.copy(timeoutMillis = it.toLong()) },
                        )
                }
            }
        }
    }

    private fun engineRenderer(candidates: List<ComparisonEngine>) = object : SimpleListCellRenderer<String?>() {
        override fun customize(list: JList<out String?>, id: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
            text = when (id) {
                null -> "First registered" + (candidates.firstOrNull()?.let { " (${it.displayName})" } ?: "")
                else -> candidates.firstOrNull { it.id.value == id }?.displayName ?: "$id (not installed)"
            }
        }
    }

    companion object {
        /** Each registered language that at least one engine supports, with those engines, sorted by display name. */
        fun languagesWithEngines(engines: List<ComparisonEngine>): List<Pair<Language, List<ComparisonEngine>>> =
            Language.getRegisteredLanguages()
                .filter { it != Language.ANY }
                .map { language -> language to engines.filter { it.supports(language.id) } }
                .filter { (_, candidates) -> candidates.isNotEmpty() }
                .sortedBy { (language, _) -> language.displayName.lowercase() }
    }
}
