package io.github.agadek.smartdiff.engine.api

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * A feature that needs an experimental, internal or optional-plugin API listed in `docs/PLATFORM-API.md`
 * (platform/01 R6, R10). [api] names that API in the warning [ApiGuard] logs when it turns the feature off.
 */
enum class GuardedFeature(val displayName: String, val api: String) {
    SCALA_PROVIDER("the Scala declaration provider", "the Scala plugin PSI"),
    PYTHON_PROVIDER("the Python declaration provider", "the Python plugin PSI"),
    RUST_PROVIDER("the Rust declaration provider", "the Rust plugin PSI"),
    GO_PROVIDER("the Go declaration provider", "the Go plugin PSI"),
    MARKDOWN_PROVIDER("the Markdown declaration provider", "the experimental Markdown front matter PSI"),
    KOTLIN_REFERENCES("Kotlin rename usages", "the Kotlin plugin PSI"),
    SCALA_REFERENCES("Scala rename usages", "the Scala plugin PSI"),
    PYTHON_REFERENCES("Python rename usages", "the Python plugin PSI"),
    CHANGES_TREE_HOOK("the Changes tree summaries", "the experimental Changes view API"),
    REVIEW_TREE_HOOK("the Git log and pull request tree summaries", "the changes tree, Git or collaboration tools API"),
}

/**
 * Turns off, for the rest of the IDE session, only the feature whose platform or plugin API is missing or changed
 * (platform/01 R10 to R15). It catches [LinkageError] alone: it is not an [Exception], so the plugin's other catch blocks
 * miss it, while cancellation and every other failure pass through unchanged.
 *
 * It depends on nothing in the plugin or the IntelliJ Platform (technical specification §2); the plugin connects
 * [reporter] to its log, which reports a warning, never an error, so no IDE error report opens (R14).
 */
object ApiGuard {

    private val off: MutableSet<GuardedFeature> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Where the one warning per turned-off feature goes; the plugin sets it to `Logger.warn`. */
    @Volatile
    var reporter: (String) -> Unit = { message -> java.util.logging.Logger.getLogger("SMART Diff").warning(message) }

    /** Whether [feature] is still on in this IDE session. */
    fun isOn(feature: GuardedFeature): Boolean = feature !in off

    /**
     * [fallback] when [feature] is off; otherwise [block], or [fallback] after turning the feature off when [block] meets
     * a missing class, method or field, or an incompatible change to one (R10).
     */
    fun <T> run(feature: GuardedFeature, fallback: () -> T, block: () -> T): T {
        if (feature in off) return fallback()
        return try {
            block()
        } catch (e: LinkageError) {
            turnOff(feature, e)
            fallback()
        }
    }

    /** Turns [feature] off and logs the warning once, naming the API and the feature (R10). */
    fun turnOff(feature: GuardedFeature, error: Throwable) {
        if (off.add(feature)) {
            reporter("SMART Diff turned off ${feature.displayName}: ${feature.api} is not available in this IDE ($error).")
        }
    }

    /** Turns every feature on again, so each test starts from a fresh IDE session. */
    internal fun resetForTests() {
        off.clear()
    }
}
