package io.github.agadek.smartdiff.engine.treesitter

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import io.github.agadek.smartdiff.engine.api.Cancellation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The one shared [TreeSitterHelper] of an IDE session (engines/05-tree-sitter-details §8, ADR 0016): found in the plugin
 * folder, copied to the IDE's system directory, started on first use and started again after a failure. After
 * [MAX_FAILURES] failures in a row, or when no helper exists for this platform, it is [disabled] for the session and
 * logs one warning (R23).
 */
class TreeSitterHelpers(private val locate: () -> Path?) {
    private var helper: TreeSitterHelper? = null
    private var failures = 0
    private var warned = false

    /** Why the helper is off for this session, or null while it can still run. */
    @Volatile
    var disabled: String? = null
        private set

    /** Runs [block] with the running helper, starting it if needed; a failure counts towards turning it off. */
    @Synchronized
    fun <T> use(cancellation: Cancellation, block: (TreeSitterHelper) -> T): T {
        disabled?.let { throw TreeSitterHelperException(it) }
        val running = helper?.takeIf { it.isUsable } ?: start()
        try {
            return block(running).also { failures = 0 }
        } catch (e: TreeSitterHelperException) {
            helper = null
            fail(e.message ?: "the Tree-sitter helper failed")
            throw e
        } catch (e: Throwable) {
            // TreeSitterHelper closes itself on a cancellation, so the next call starts a new one; it is not a failure.
            if (!running.isUsable) helper = null
            throw e
        }
    }

    @Synchronized
    fun shutdown() {
        helper?.close()
        helper = null
    }

    private fun start(): TreeSitterHelper {
        val executable = try {
            locate()
        } catch (e: Exception) {
            null
        } ?: disable("no Tree-sitter helper for ${TreeSitterHelper.platform() ?: System.getProperty("os.name") + " " + System.getProperty("os.arch")}")
        return try {
            TreeSitterHelper(executable).also { helper = it }
        } catch (e: TreeSitterHelperException) {
            fail(e.message ?: "the Tree-sitter helper could not be started")
            throw e
        }
    }

    private fun fail(reason: String) {
        if (++failures >= MAX_FAILURES) disable("$reason ($failures failures in a row)")
    }

    private fun disable(reason: String): Nothing {
        disabled = reason
        if (!warned) {
            warned = true
            LOG.warn("SMART Diff: Tree-sitter languages are compared as without Tree-sitter for this session: $reason")
        }
        throw TreeSitterHelperException(reason)
    }

    companion object {
        const val MAX_FAILURES = 3

        /** Overrides the helper's location, for tests and the build's own test task. */
        const val HELPER_PROPERTY = "smartdiff.treesitter.helper"

        private val LOG = logger<TreeSitterHelpers>()

        /** The session's helpers, found next to the plugin's jar and copied under the IDE's system directory. */
        val INSTANCE: TreeSitterHelpers by lazy { TreeSitterHelpers { installed() } }

        /**
         * The helper to run: the [HELPER_PROPERTY] path, or `treesitter/<platform>/` in the plugin folder copied to
         * `<system>/smart-diff/treesitter/<platform>-<size>/`, so an updated plugin never runs an older copy. Null when
         * the plugin has no helper for this platform.
         */
        internal fun installed(): Path? {
            System.getProperty(HELPER_PROPERTY)?.let { return Path.of(it).takeIf(Files::isRegularFile) }
            val platform = TreeSitterHelper.platform() ?: return null
            val name = TreeSitterHelper.executableName()
            val bundled = pluginFolder()?.resolve("treesitter")?.resolve(platform)?.resolve(name)?.takeIf(Files::isRegularFile) ?: return null
            val target = Path.of(PathManager.getSystemPath(), "smart-diff", "treesitter", "$platform-${Files.size(bundled)}", name)
            if (!Files.isRegularFile(target) || Files.size(target) != Files.size(bundled)) {
                Files.createDirectories(target.parent)
                val partial = target.resolveSibling("$name.partial")
                Files.copy(bundled, partial, StandardCopyOption.REPLACE_EXISTING)
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            target.toFile().setExecutable(true)
            return target
        }

        /** The plugin folder: the parent of the `lib` folder that holds this class's jar. */
        private fun pluginFolder(): Path? {
            val location = TreeSitterHelpers::class.java.protectionDomain?.codeSource?.location ?: return null
            val jar = Path.of(location.toURI())
            return jar.parent?.takeIf { it.fileName?.toString() == "lib" }?.parent
        }
    }
}
