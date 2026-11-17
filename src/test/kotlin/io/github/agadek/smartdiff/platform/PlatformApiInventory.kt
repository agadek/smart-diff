package io.github.agadek.smartdiff.platform

import java.io.File
import kotlin.system.exitProcess

/**
 * The platform API inventory of `docs/PLATFORM-API.md` and the uses Plugin Verifier reports (platform/01 R6, R8).
 * The `checkPlatformApiInventory` task runs [PlatformApiInventoryCheck] over `build/reports/pluginVerifier`.
 */
object PlatformApiInventory {

    /** The columns every inventory row has, in order (R6). */
    val COLUMNS = listOf("API", "Status", "Adapter", "Feature", "Fallback", "Public replacement in 2025.1")

    /** One inventory row; [apis] and [adapters] are the backticked names of their cells. */
    data class Row(
        val apis: List<String>,
        val status: String,
        val adapters: List<String>,
        val features: List<String>,
        val fallback: String,
        val replacement: String,
    )

    /** One reported use: the API's class, its member when the report names one, and the plugin class that uses it. */
    data class Use(val kind: String, val apiClass: String, val member: String?, val user: String) {
        override fun toString(): String = "$kind ${apiClass}${member?.let { "#$it" }.orEmpty()} in $user"
    }

    /** The rows of the inventory table, the first table whose header is [COLUMNS]. */
    fun parse(markdown: String): List<Row> {
        val lines = markdown.lines()
        val header = lines.indexOfFirst { cells(it) == COLUMNS }
        require(header >= 0) { "no inventory table with the columns ${COLUMNS.joinToString(" | ")}" }
        return lines.drop(header + 2).takeWhile { it.trimStart().startsWith("|") }.map { line ->
            val cells = cells(line)
            require(cells.size == COLUMNS.size && cells.all { it.isNotBlank() }) { "an inventory row needs every column: $line" }
            Row(names(cells[0]), cells[1], names(cells[2]), names(cells[3]), cells[4], cells[5])
        }
    }

    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    private fun names(cell: String): List<String> = Regex("`([^`]+)`").findAll(cell).map { it.groupValues[1] }.toList()

    private val USE = Regex(
        """^(Experimental API|Internal API|Deprecated|Scheduled for removal)\s+(?:abstract\s+)?(class|interface|enum|annotation|method|constructor|field)\s+(\S+).*?\bis\s+\w+\s+in\s+(?:class\s+|interface\s+)?([\w.$]+)""",
    )

    /** The uses by classes of the plugin's [root] package in every usage report under [reports]. */
    fun uses(reports: File, root: String = "io.github.agadek.smartdiff"): Set<Use> =
        reports.walkTopDown()
            .filter { it.isFile && it.name.endsWith("usages.txt") }
            .flatMap { it.readLines().asSequence() }
            .mapNotNull { parseUse(it.trim()) }
            .filter { it.user.startsWith("$root.") }
            .toSortedSet(compareBy { it.toString() })

    /** One report line as a use, or null for a line that names none. */
    fun parseUse(line: String): Use? {
        val match = USE.find(line) ?: return null
        val (kind, what, api, userText) = match.destructured
        val name = api.substringBefore('(')
        val (apiClass, member) = when (what) {
            "method", "constructor", "field" -> name.substringBeforeLast('.') to name.substringAfterLast('.')
            else -> name to null
        }
        // The user is a class, or a member of one: keep the class, the part before its first lower-case segment.
        val segments = userText.removeSuffix(".").split('.')
        val classEnd = segments.indexOfFirst { it.isNotEmpty() && it[0].isUpperCase() }
        val user = if (classEnd < 0) userText.removeSuffix(".") else segments.take(classEnd + 1).joinToString(".")
        return Use(kind, apiClass, member, user.substringBefore('$'))
    }

    /** Whether an inventory API name covers [use]: the class, the class and member, or a package with subpackages. */
    fun covers(api: String, use: Use): Boolean = when {
        api.endsWith("..") -> use.apiClass.startsWith(api.removeSuffix("..") + ".")
        '#' in api -> api == "${use.apiClass}#${use.member}"
        else -> api == use.apiClass
    }

    /**
     * The [uses] no row of the inventory lists by class or member (R8). A package row names an optional plugin's whole
     * PSI for the architecture test; it never lists a reported use, so a new experimental member is always named.
     */
    fun unlisted(rows: List<Row>, uses: Set<Use>): List<Use> =
        uses.filter { use -> rows.none { row -> row.apis.any { !it.endsWith("..") && covers(it, use) } } }
}

/** The `checkPlatformApiInventory` task: `<verifier reports dir> <inventory file>`; fails naming each unlisted use (R8). */
object PlatformApiInventoryCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        val reports = File(args[0])
        val rows = PlatformApiInventory.parse(File(args[1]).readText())
        val verdicts = reports.walkTopDown().filter { it.name == "verification-verdict.txt" }.toList()
        if (verdicts.isEmpty()) {
            System.err.println("No Plugin Verifier reports in $reports: run ./gradlew verifyPlugin first.")
            exitProcess(1)
        }
        val uses = PlatformApiInventory.uses(reports)
        val unlisted = PlatformApiInventory.unlisted(rows, uses)
        if (unlisted.isNotEmpty()) {
            System.err.println("Platform API uses that docs/PLATFORM-API.md does not list (platform/01 R8):")
            unlisted.forEach { System.err.println("  $it") }
            exitProcess(1)
        }
        println("Platform API inventory lists all ${uses.size} reported uses in ${verdicts.size} verifier reports.")
    }
}
