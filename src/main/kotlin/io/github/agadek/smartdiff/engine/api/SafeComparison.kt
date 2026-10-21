package io.github.agadek.smartdiff.engine.api

import java.util.concurrent.CancellationException

/**
 * Runs [engine] the way the host must (technical specification §3.1 rule 4, §9.3): a side over
 * [Limits.maxChars] gives [Status.TOO_LARGE] without calling the engine, an exception other than cancellation becomes a
 * [Status.FAILED] result, and cancellation propagates to the caller.
 */
fun compareSafely(engine: ComparisonEngine, request: ComparisonRequest, cancellation: Cancellation): ComparisonResult {
    val largest = maxOf(request.oldText.length, request.newText.length)
    if (largest > request.limits.maxChars) {
        return ComparisonResult.tooLarge(engine, "file has $largest characters, the limit is ${request.limits.maxChars}")
    }
    return try {
        engine.compare(request, cancellation)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ComparisonResult.failed(engine, "engine ${engine.id} failed: ${e.message ?: e.javaClass.simpleName}")
    } catch (e: StackOverflowError) {
        ComparisonResult.failed(engine, "engine ${engine.id} failed: stack overflow")
    }
}
