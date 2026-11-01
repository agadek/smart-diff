package io.github.agadek.smartdiff.merge

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Limits
import io.github.agadek.smartdiff.engine.api.compareSafely
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** The three texts of a merge or a three-sided diff, and what the comparisons need to know about them. */
data class MergeTexts(
    val base: CharSequence,
    val left: CharSequence,
    val right: CharSequence,
    /** IntelliJ `Language.getID()`, the same for all three texts. */
    val languageId: String,
    /** A hint only, as in [ComparisonRequest.fileName]. */
    val fileName: String? = null,
    val limits: Limits = Limits.DEFAULT,
)

/**
 * Runs one engine on both sides of a merge and joins the results (three-way merge plan §5.3, §5.4): base → left and
 * base → right, each through [compareSafely] with the other side as a related text, so a language with dialects reads
 * the base alike in both.
 *
 * The right side runs on [executor] while the left runs on the calling thread. Both share [cancellation], which the host
 * cancels when the time limit for both together has passed; when either run is canceled, the other is too.
 */
object MergeComparison {

    fun compare(engine: ComparisonEngine, request: MergeTexts, cancellation: Cancellation, executor: Executor): JoinedResult {
        val canceled = AtomicBoolean()
        val shared = Cancellation {
            if (canceled.get()) throw CancellationException("the other side of the merge was canceled")
            cancellation.checkCanceled()
        }
        val (leftRequest, rightRequest) = sideRequests(request)
        val rightRun = CompletableFuture.supplyAsync({ run(engine, rightRequest, shared, canceled) }, executor)
        val left = try {
            run(engine, leftRequest, shared, canceled)
        } catch (e: Throwable) {
            canceled.set(true)
            throw e
        }
        val right = try {
            rightRun.join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }
        return MergeJoin.join(left, right, request.left, request.right)
    }

    /** The two requests one merge runs, each with the other side as a related text (plan §5.3). */
    internal fun sideRequests(request: MergeTexts): Pair<ComparisonRequest, ComparisonRequest> =
        sideRequest(request, request.left, request.right) to sideRequest(request, request.right, request.left)

    private fun sideRequest(request: MergeTexts, side: CharSequence, other: CharSequence) =
        ComparisonRequest(request.base, side, request.languageId, request.fileName, request.limits, relatedTexts = listOf(other))

    private fun run(engine: ComparisonEngine, request: ComparisonRequest, cancellation: Cancellation, canceled: AtomicBoolean): ComparisonResult =
        try {
            compareSafely(engine, request, cancellation)
        } catch (e: CancellationException) {
            canceled.set(true)
            throw e
        }
}
