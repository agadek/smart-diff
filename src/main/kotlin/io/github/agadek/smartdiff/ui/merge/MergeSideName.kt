package io.github.agadek.smartdiff.ui.merge

import com.intellij.openapi.util.text.StringUtil

/** A side's short display name and the full title it came from (merge outline spec 02 R8-R11). */
data class MergeSideName(val display: String, val fullTitle: String)

/**
 * Turns the titles a three-sided diff or merge window gives its panes into short side names: the branch or revision
 * they name, or the written title cut at 20 characters. It knows the Git merge, rebase, cherry-pick and unstash forms
 * recorded by spike S6; other titles are shown as written.
 */
object MergeSideNames {

    private const val MAX_DISPLAY = 20

    private val yourVersion = Regex("""(?i)^your version,\s*branch\s+(.+)$""")
    private val changesFromBranch = Regex("""(?i)^changes from branch\s+(.+?)(?:,\s*revision\s+\S+)?$""")
    private val changesFromServer = Regex("""(?i)^changes from server\s*\(revision\s+([^)]+)\)$""")
    private val changesFrom = Regex("""(?i)^changes from\s+(.+)$""")
    private val branch = Regex("""(?i)^branch\s+(.+)$""")

    /** The side name for [title], or [fallback] when the title is blank. */
    fun fromTitle(title: String?, fallback: String): MergeSideName {
        val plain = StringUtil.removeHtmlTags(title.orEmpty()).trim()
        val full = plain.ifEmpty { fallback }
        val parsed = parse(plain)
        return MergeSideName(parsed ?: full.take(MAX_DISPLAY), full)
    }

    private fun parse(title: String): String? {
        if (title.isEmpty()) return null
        yourVersion.matchEntire(title)?.let { return it.groupValues[1].trim() }
        changesFromBranch.matchEntire(title)?.let { return it.groupValues[1].trim() }
        changesFromServer.matchEntire(title)?.let { return shortHash(it.groupValues[1].trim()) }
        changesFrom.matchEntire(title)?.let { return stripRemote(it.groupValues[1].trim()) }
        branch.matchEntire(title)?.let { return it.groupValues[1].trim() }
        return null
    }

    /** `origin/feature/rates` reads as `feature/rates`, as the merge plan's side-name example does. */
    private fun stripRemote(name: String): String =
        name.removePrefix("origin/").removePrefix("upstream/")

    /** A 40-character revision is shown as a short hash; other revision names are shown as written. */
    private fun shortHash(revision: String): String =
        if (revision.matches(Regex("[0-9a-fA-F]{7,40}"))) revision.take(7) else revision
}
