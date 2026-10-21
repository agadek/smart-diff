package io.github.agadek.smartdiff.engine.api

/**
 * One way of comparing the old side and the new side of a file (technical specification §3.1).
 *
 * Rules every engine follows:
 * 1. [compare] runs on a background thread, never inside a read action. An engine that needs PSI takes its own
 *    cancellable read actions; an engine that runs an external process holds no read lock while waiting.
 * 2. [compare] is pure with respect to its inputs: the same request and engine version give an equal result.
 * 3. [compare] calls [Cancellation.checkCanceled] regularly and lets its exception propagate.
 * 4. Failures are a result with [Status.FAILED] and a reason, not an exception.
 * 5. All ranges in the result are offsets into the request's texts.
 * 6. Nothing hidden: every character that differs between the sides lies inside a reported change, the reported file
 *    region or an unanalyzed range.
 * 7. No source content leaves the IDE process.
 */
interface ComparisonEngine {
    /** Stable; persisted in settings. */
    val id: EngineId

    /** Shown in the engine dropdown. */
    val displayName: String

    /** Engine and parser version; part of the result. */
    val version: String

    /** Whether this engine can compare files of [languageId], an IntelliJ `Language.getID()` such as `JAVA` or `kotlin`. */
    fun supports(languageId: String): Boolean

    fun compare(request: ComparisonRequest, cancellation: Cancellation): ComparisonResult
}

/** Stable engine id, such as `smartdiff.declarations`. */
@JvmInline
value class EngineId(val value: String) {
    override fun toString(): String = value
}

/** Checks whether the comparison was canceled. */
fun interface Cancellation {
    /** Throws a [java.util.concurrent.CancellationException] (IntelliJ's `ProcessCanceledException` is one) when canceled. */
    fun checkCanceled()

    companion object {
        /** Never cancels; for tests and callers without a progress indicator. */
        val NONE: Cancellation = Cancellation { }
    }
}
