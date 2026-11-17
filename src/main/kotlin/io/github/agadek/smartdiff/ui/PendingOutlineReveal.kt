package io.github.agadek.smartdiff.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Outline paths waiting for a diff that a review tree opened through its own view model, which takes no context
 * (changes-tree/07, "Opening a row"). The next outline shown for the same file within [TIMEOUT_MILLIS] takes the path
 * and selects its row. The file is matched by its path where the diff gives one, so two files of the same name in one
 * request do not take each other's path; only a diff whose content has no path is matched by the file name.
 */
@Service(Service.Level.PROJECT)
class PendingOutlineReveal {
    private class Pending(val filePath: String, val fileName: String, val path: OutlinePath, val at: Long)

    @Volatile
    private var pending: Pending? = null

    /** Records [path] for the diff of the file at [filePath], named [fileName]. */
    fun put(filePath: String, fileName: String, path: OutlinePath) {
        pending = Pending(filePath, fileName, path, System.currentTimeMillis())
    }

    /**
     * The path waiting for the diff of the file at [filePath], or named [fileName] when the diff has no path, once; null
     * when there is none, it is for another file, or it is older than [TIMEOUT_MILLIS].
     */
    fun take(filePath: String?, fileName: String?): OutlinePath? = synchronized(this) {
        val waiting = pending ?: return null
        if (System.currentTimeMillis() - waiting.at > TIMEOUT_MILLIS) {
            pending = null
            return null
        }
        if (!matches(waiting, filePath, fileName)) return null
        pending = null
        waiting.path
    }

    private fun matches(waiting: Pending, filePath: String?, fileName: String?): Boolean = when {
        // A content file without a directory, such as an in-memory file named after the revision, has no real path.
        filePath != null && filePath.contains('/') -> filePath == waiting.filePath
        else -> fileName != null && fileName == waiting.fileName
    }

    companion object {
        const val TIMEOUT_MILLIS = 5_000L

        fun getInstance(project: Project): PendingOutlineReveal = project.service()
    }
}
