package io.github.agadek.smartdiff.ui.changes

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesViewModifier
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opens the platform's Changes diff with the tree's changes and the selected declaration's context.
 *
 * The platform action lives in a package the plugin verifier no longer resolves in the latest IDE (2026.3), although the
 * class is still present. Calling it through reflection keeps the Changes tree working on 2025.1 and later without a
 * compile-time dependency on an unexported package. The lookup tries the classloader of the Changes view and of the
 * plugin, since the class moved between platform modules. If neither can see it, the file's own diff still opens through
 * the public `DiffManager` API, without the declaration context.
 */
internal object ChangesDiff {

    private val logged = AtomicBoolean()
    private val fallbackLogged = AtomicBoolean()

    /**
     * Opens the diff at [index] and returns whether a diff was shown. It returns false when [changes] has no such change,
     * or when both the platform action and the fallback fail.
     */
    fun show(project: Project, changes: List<Change>, index: Int, key: Key<*>, value: Any): Boolean =
        show(project, changes, index, key, value, ACTION, CONTEXT)

    /** Opens the file's diff without a declaration context, as the platform does for a file without declarations. */
    fun show(project: Project, changes: List<Change>, index: Int): Boolean =
        show(project, changes, index, null, null, ACTION, CONTEXT)

    /** [show], with the platform class names injectable so a test can make the lookup fail. */
    internal fun show(
        project: Project,
        changes: List<Change>,
        index: Int,
        key: Key<*>?,
        value: Any?,
        actionClassName: String,
        contextClassName: String,
        classLoaders: List<ClassLoader> = classLoaders(),
    ): Boolean {
        if (changes.isEmpty() || index !in changes.indices) return false
        val change = changes[index]
        if (showWithContext(project, changes, index, key, value, actionClassName, contextClassName, classLoaders)) {
            return true
        }
        return showWithoutContext(project, change)
    }

    /** Calls the platform's action through the first classloader that can see both of its classes. */
    private fun showWithContext(
        project: Project,
        changes: List<Change>,
        index: Int,
        key: Key<*>?,
        value: Any?,
        actionClassName: String,
        contextClassName: String,
        classLoaders: List<ClassLoader>,
    ): Boolean {
        val loader = loaderFor(actionClassName, contextClassName, classLoaders) ?: return false
        return try {
            val contextClass = Class.forName(contextClassName, true, loader)
            val context = contextClass.getDeclaredConstructor().newInstance()
            if (key != null) {
                contextClass
                    .getMethod("putChangeContext", Change::class.java, Key::class.java, Any::class.java)
                    .invoke(context, changes[index], key, value)
            }
            Class.forName(actionClassName, true, loader)
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

    /**
     * The first loader that can see both platform classes. The Changes view's loader comes first, as the action belongs
     * to the VCS platform; the plugin's loader comes next, because 2026.3 loads that module as a plugin dependency.
     */
    private fun loaderFor(actionClassName: String, contextClassName: String, classLoaders: List<ClassLoader>): ClassLoader? {
        for (loader in classLoaders) {
            try {
                Class.forName(actionClassName, false, loader)
                Class.forName(contextClassName, false, loader)
                return loader
            } catch (e: ClassNotFoundException) {
                // The next loader may see the module.
            } catch (e: LinkageError) {
                // The next loader may see a compatible copy.
            }
        }
        report(ClassNotFoundException("Cannot load $actionClassName and $contextClassName"))
        return null
    }

    /** The loaders a plugin can reach the platform's VCS classes through, most specific first. */
    internal fun classLoaders(): List<ClassLoader> = buildList {
        ChangesViewModifier::class.java.classLoader?.let(::add)
        ChangesDiff::class.java.classLoader?.let(::add)
        Thread.currentThread().contextClassLoader?.let(::add)
        ClassLoader.getSystemClassLoader()?.let(::add)
    }.distinct()

    /**
     * Opens the file's own diff when the platform action is unavailable: no tree context, no declaration path, but the
     * two revisions of the change, built through the public diff API. The request is lazy, so the diff window reads the
     * revisions under its own progress indicator off the UI thread. Returns whether the diff was shown.
     */
    private fun showWithoutContext(project: Project, change: Change): Boolean {
        return try {
            val before = change.beforeRevision
            val after = change.afterRevision
            val file = after?.file ?: before?.file ?: return false
            val producer = ChangeContentDiffProducer(project, file, before, after)
            DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain.fromProducer(producer), DiffDialogHints.DEFAULT)
            true
        } catch (e: Exception) {
            reportFallback(e)
            false
        } catch (e: LinkageError) {
            reportFallback(e)
            false
        }
    }

    /** Builds the file's two-sided diff when the platform asks for it, on the diff window's background thread. */
    private class ChangeContentDiffProducer(
        private val project: Project,
        private val file: FilePath,
        private val before: ContentRevision?,
        private val after: ContentRevision?,
    ) : DiffRequestProducer {

        override fun getName(): String = file.path

        override fun getContentType(): FileType? = file.fileType

        override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
            try {
                indicator.text = "Loading changes"
                val oldContent = content(project, before) ?: throw DiffRequestProducerException("Cannot read ${before?.file?.path ?: file.path}")
                val newContent = content(project, after) ?: throw DiffRequestProducerException("Cannot read ${after?.file?.path ?: file.path}")
                return SimpleDiffRequest(
                    file.path,
                    oldContent,
                    newContent,
                    revisionTitle(before),
                    revisionTitle(after),
                )
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                throw DiffRequestProducerException(e)
            }
        }
    }

    /**
     * The file's own diff request, without a declaration context. It reads both revisions, so call it on a background
     * thread, as the diff window does through [ChangeContentDiffProducer] and the Commit preview through its provider.
     * [indicator] is checked between the reads, so a preview the user switches away from stops early.
     */
    fun request(project: Project, change: Change, indicator: ProgressIndicator? = null): SimpleDiffRequest? {
        val before = change.beforeRevision
        val after = change.afterRevision
        val file = after?.file ?: before?.file ?: return null
        indicator?.checkCanceled()
        val oldContent = content(project, before) ?: return null
        indicator?.checkCanceled()
        val newContent = content(project, after) ?: return null
        indicator?.checkCanceled()
        return SimpleDiffRequest(
            file.path,
            oldContent,
            newContent,
            revisionTitle(before),
            revisionTitle(after),
        )
    }

    /**
     * The content of [revision] through the public diff API: the current file when the revision is the working copy, its
     * bytes for a byte-backed revision, and its text otherwise. Null when the content cannot be read.
     */
    private fun content(project: Project, revision: ContentRevision?): DiffContent? {
        if (revision == null) return DiffContentFactory.getInstance().createEmpty()
        val file = revision.file
        if (revision.revisionNumber === VcsRevisionNumber.NULL) {
            val virtualFile = file.virtualFile?.takeIf { it.isValid }
            if (virtualFile != null) return DiffContentFactory.getInstance().create(project, virtualFile)
        }
        if (revision is ByteBackedContentRevision) {
            val bytes = revision.contentAsBytes ?: return null
            return DiffContentFactory.getInstance().createFromBytes(project, bytes, file)
        }
        val text = revision.content ?: return null
        return DiffContentFactory.getInstance().create(project, text, file)
    }

    private fun revisionTitle(revision: ContentRevision?): String = when {
        revision == null -> ""
        revision.revisionNumber === VcsRevisionNumber.NULL -> "Current"
        else -> revision.revisionNumber.asString()
    }

    /** A missing platform action stays missing for the session, so only the first failed click is logged. */
    private fun report(e: Throwable) {
        if (logged.compareAndSet(false, true)) {
            thisLogger().warn("Cannot call the platform's Changes diff action; opening the file's diff instead", e)
        }
    }

    /** A failed fallback is logged once too, so a broken change list does not flood the log. */
    private fun reportFallback(e: Throwable) {
        if (fallbackLogged.compareAndSet(false, true)) {
            thisLogger().warn("Cannot open the file's diff from the Changes tree", e)
        }
    }

    private const val ACTION = "com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction"
    private const val CONTEXT = "com.intellij.openapi.vcs.changes.actions.diff.ShowDiffContext"
}
