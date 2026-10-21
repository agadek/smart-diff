package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import io.github.agadek.smartdiff.engine.api.Cancellation

/**
 * Runs [action] in a read action that gives way to write actions (technical specification §3.1 rule 1): when a write
 * action is about to start, the read is canceled, waits for the write to end and starts over. Whatever [action] throws,
 * including the exception of [cancellation], reaches the caller unchanged.
 *
 * A thread that cannot wait for a write action (the event dispatch thread, or one already reading) runs [action] in a
 * plain read action.
 */
internal fun <T> cancellableReadAction(cancellation: Cancellation, action: () -> T): T {
    val application = ApplicationManager.getApplication()
    if (application.isDispatchThread || application.isReadAccessAllowed) {
        return application.runReadAction(Computable { action() })
    }
    while (true) {
        val indicator = EmptyProgressIndicator()
        var outcome: Result<T>? = null
        ProgressManager.getInstance().runInReadActionWithWriteActionPriority({
            outcome = try {
                Result.success(action())
            } catch (e: ProcessCanceledException) {
                // Canceled for a write action: let the platform end the read, then start over.
                if (indicator.isCanceled) throw e
                Result.failure(e)
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }, indicator)
        outcome?.let { return it.getOrThrow() }
        cancellation.checkCanceled()
        // Acquiring a read lock waits until the pending write action has finished.
        application.runReadAction(Runnable { })
    }
}
