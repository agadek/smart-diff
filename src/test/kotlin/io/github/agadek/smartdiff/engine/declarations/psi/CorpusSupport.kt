package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/** Helpers the corpus runs share: [CorpusValidationTest] and [FineGrainedBaselinesTest]. */
object CorpusSupport {

    /** The non-comment lines of a corpus list: a local Git repository followed by `git rev-list` arguments. */
    fun listLines(list: File): List<List<String>> =
        list.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split(Regex("\\s+")) }

    fun git(repository: File, arguments: List<String>, allowFailure: Boolean = false): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.absolutePath) + arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0 && !allowFailure) error("git ${arguments.joinToString(" ")} failed in $repository with exit code $exit")
        return output
    }

    fun <T> onPooledThread(task: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread(Callable { task() }).get(10, TimeUnit.MINUTES)

    /** The dialect a language with several, such as Scala, parses both sides in; null for the others. */
    fun dialect(languageId: String, old: String, new: String): String? {
        val provider = DeclarationProvider.EP_NAME.extensionList.firstOrNull { it.supports(languageId) } ?: return null
        return ReadAction.compute(ThrowableComputable<String?, RuntimeException> {
            provider.dialect(ProjectManager.getInstance().defaultProject, listOf(old, new), null)?.id
        })
    }
}
