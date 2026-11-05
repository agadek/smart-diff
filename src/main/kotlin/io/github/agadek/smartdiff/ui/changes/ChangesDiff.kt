package io.github.agadek.smartdiff.ui.changes

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.changes.Change

/**
 * Opens the platform's Changes diff with the tree's changes and the selected declaration's context.
 *
 * The platform action lives in a package the plugin verifier no longer resolves in the latest IDE (2026.3), although the
 * class is still present. Calling it through reflection keeps the Changes tree working on 2025.1 and later without a
 * compile-time dependency on an unexported package.
 */
internal object ChangesDiff {

    fun show(project: Project, changes: List<Change>, index: Int, key: Key<*>, value: Any) {
        if (changes.isEmpty() || index !in changes.indices) return
        try {
            val contextClass = Class.forName(CONTEXT)
            val context = contextClass.getDeclaredConstructor().newInstance()
            contextClass
                .getMethod("putChangeContext", Change::class.java, Key::class.java, Any::class.java)
                .invoke(context, changes[index], key, value)
            Class.forName(ACTION)
                .getMethod("showDiffForChange", Project::class.java, Iterable::class.java, Int::class.javaPrimitiveType, contextClass)
                .invoke(null, project, changes, index, context)
        } catch (e: Exception) {
            thisLogger().warn("Cannot open the Changes diff", e)
        } catch (e: LinkageError) {
            thisLogger().warn("Cannot open the Changes diff", e)
        }
    }

    private const val ACTION = "com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction"
    private const val CONTEXT = "com.intellij.openapi.vcs.changes.actions.diff.ShowDiffContext"
}
