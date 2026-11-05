package io.github.agadek.smartdiff.ui.changes

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.changes.Change
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opens the platform's Changes diff with the tree's changes and the selected declaration's context.
 *
 * The platform action lives in a package the plugin verifier no longer resolves in the latest IDE (2026.3), although the
 * class is still present. Calling it through reflection keeps the Changes tree working on 2025.1 and later without a
 * compile-time dependency on an unexported package.
 */
internal object ChangesDiff {

    private val logged = AtomicBoolean()

    /**
     * Opens the diff at [index] and returns whether the platform action was called. It returns false when [changes] has
     * no such change, or when the action cannot be loaded or fails, so the caller can leave the click to the platform.
     */
    fun show(project: Project, changes: List<Change>, index: Int, key: Key<*>, value: Any): Boolean =
        show(project, changes, index, key, value, ACTION, CONTEXT)

    /** [show], with the platform class names injectable so a test can make the lookup fail. */
    internal fun show(
        project: Project,
        changes: List<Change>,
        index: Int,
        key: Key<*>,
        value: Any,
        actionClassName: String,
        contextClassName: String,
    ): Boolean {
        if (changes.isEmpty() || index !in changes.indices) return false
        return try {
            val contextClass = Class.forName(contextClassName)
            val context = contextClass.getDeclaredConstructor().newInstance()
            contextClass
                .getMethod("putChangeContext", Change::class.java, Key::class.java, Any::class.java)
                .invoke(context, changes[index], key, value)
            Class.forName(actionClassName)
                .getMethod("showDiffForChange", Project::class.java, Iterable::class.java, Int::class.javaPrimitiveType, contextClass)
                .invoke(null, project, changes, index, context)
            true
        } catch (e: Exception) {
            report(e)
            false
        } catch (e: LinkageError) {
            report(e)
            false
        }
    }

    /** A missing action stays missing for the session, so only the first failed click is logged. */
    private fun report(e: Throwable) {
        if (logged.compareAndSet(false, true)) {
            thisLogger().warn("Cannot open the Changes diff; the platform opens it instead", e)
        }
    }

    private const val ACTION = "com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction"
    private const val CONTEXT = "com.intellij.openapi.vcs.changes.actions.diff.ShowDiffContext"
}
