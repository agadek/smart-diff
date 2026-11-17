package io.github.agadek.smartdiff.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Outline paths waiting for a diff that a review tree opened through its own view model, which takes no context
 * (changes-tree/07, "Opening a row"). The next outline shown for the same file name within [TIMEOUT_MILLIS] takes the
 * path and selects its row.
 */
@Service(Service.Level.PROJECT)
class PendingOutlineReveal {
    private class Pending(val fileName: String, val path: OutlinePath, val at: Long)

    @Volatile
    private var pending: Pending? = null

    fun put(fileName: String, path: OutlinePath) {
        pending = Pending(fileName, path, System.currentTimeMillis())
    }

    /** The path waiting for [fileName], once; null when there is none or it is older than [TIMEOUT_MILLIS]. */
    fun take(fileName: String?): OutlinePath? = synchronized(this) {
        val waiting = pending ?: return null
        if (System.currentTimeMillis() - waiting.at > TIMEOUT_MILLIS) {
            pending = null
            return null
        }
        if (fileName == null || waiting.fileName != fileName) return null
        pending = null
        waiting.path
    }

    companion object {
        const val TIMEOUT_MILLIS = 5_000L

        fun getInstance(project: Project): PendingOutlineReveal = project.service()
    }
}
