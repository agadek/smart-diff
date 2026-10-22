package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Limits
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * Checks whether the limits in §9.3 of the technical specification are realistic (spike S3, §13): measures the
 * declaration engine on large real files, on a pooled thread as the host runs it, with no limits applied.
 *
 * The files are listed one path per line in the file named by `-Psmartdiff.limits.corpus=<list>`; without it the benchmark
 * only says it was skipped, so CI does not need the corpus. Each file is compared with itself, with a copy that has an empty
 * line after every 10th line (formatting-only changes), and with a copy that has a comment line after every 10th line
 * (changed content). Inserting whole lines keeps the file parsable.
 */
class LimitsBenchmarkTest : BasePlatformTestCase() {

    fun `test analysis time on large files`() {
        val list = System.getProperty("smartdiff.limits.corpus")?.takeIf { it.isNotBlank() }
        if (list == null) {
            benchmark("skipped: no corpus (run with -Psmartdiff.limits.corpus=<file listing paths>)")
            return
        }
        val engine = DeclarationEngine(null)
        val limits = Limits(maxChars = Int.MAX_VALUE, maxDeclarations = Int.MAX_VALUE, timeoutMillis = Long.MAX_VALUE)
        benchmark("file | chars | lines | variant | status | declarations reported | median ms of 5 (after 2 warm-up runs)")
        for (path in File(list).readLines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val text = File(path).readText().replace("\r\n", "\n")
            val languageId = if (path.endsWith(".kt") || path.endsWith(".kts")) "kotlin" else "JAVA"
            val lines = text.lines()
            val variants = listOf(
                "identical" to text,
                "formatting" to insertEvery10th(lines, ""),
                "comments" to insertEvery10th(lines, "// s3"),
            )
            for ((variant, newText) in variants) {
                val request = ComparisonRequest(text, newText, languageId, File(path).name, limits)
                val timings = mutableListOf<Long>()
                var summary = ""
                repeat(7) { run ->
                    val start = System.nanoTime()
                    val result = onPooledThread { engine.compare(request, Cancellation.NONE) }
                    val millis = (System.nanoTime() - start) / 1_000_000
                    if (run >= 2) timings += millis
                    summary = "${result.status} | ${countReported(result.changes)}"
                }
                benchmark("${File(path).name} | ${text.length} | ${lines.size} | $variant | $summary | ${timings.sorted()[timings.size / 2]}")
            }
        }
    }

    private fun insertEvery10th(lines: List<String>, inserted: String): String =
        lines.flatMapIndexed { i, line -> if (i % 10 == 9 && i < lines.size - 1) listOf(line, inserted) else listOf(line) }.joinToString("\n")

    private fun countReported(nodes: List<io.github.agadek.smartdiff.engine.api.ChangeNode>): Int =
        nodes.sumOf { (if (it.isChangedItself) 1 else 0) + countReported(it.children) }

    private fun <T> onPooledThread(task: () -> T): T =
        ApplicationManager.getApplication().executeOnPooledThread(Callable { task() }).get(10, TimeUnit.MINUTES)

    private fun benchmark(line: String) = println("BENCHMARK $line")
}
