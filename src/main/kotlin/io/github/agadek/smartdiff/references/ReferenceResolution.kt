package io.github.agadek.smartdiff.references

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import io.github.agadek.smartdiff.engine.api.Cancellation

/**
 * One reference resolve for the language resolvers (relationships/04 R5, R6, R16). A resolve that fails, or that leaves
 * the light copy, is an ordinary change. A platform write-action cancel is not swallowed: it is rethrown so
 * `cancellableReadAction` restarts the read, because a swallowed cancel would finish the pass with a partial usage set.
 */
internal object ReferenceResolution {

    fun resolve(reference: PsiReference, file: PsiFile, cancellation: Cancellation, log: Logger): PsiElement? {
        cancellation.checkCanceled()
        return try {
            val resolved = reference.resolve()
            cancellation.checkCanceled()
            resolved?.takeIf { it.containingFile === file }
        } catch (e: ProcessCanceledException) {
            cancellation.checkCanceled()
            if (ProgressManager.getInstance().progressIndicator?.isCanceled == true) throw e
            log.debug("a usage was not resolved: $e")
            null
        } catch (e: Exception) {
            cancellation.checkCanceled()
            log.debug("a usage was not resolved: $e")
            null
        }
    }
}
