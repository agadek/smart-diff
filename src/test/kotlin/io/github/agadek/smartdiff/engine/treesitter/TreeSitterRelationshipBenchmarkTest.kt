package io.github.agadek.smartdiff.engine.treesitter

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.LanguageIds
import io.github.agadek.smartdiff.relationships.RelationshipBenchmark
import java.io.File
import java.nio.file.Path

/**
 * The relationship benchmark cases of the Tree-sitter languages (engines/05 R21, R30), listed in
 * `src/test/testData/relationships-corpus-treesitter.txt` and compared by the Tree-sitter engine with the helper the build
 * made. Every language finds every labelled relationship and no wrong or forced pair.
 */
class TreeSitterRelationshipBenchmarkTest : BasePlatformTestCase() {

    private val engine = TreeSitterEngine(
        TreeSitterHelpers { Path.of(System.getProperty(TreeSitterHelpers.HELPER_PROPERTY)) },
        enabled = { true },
    )

    fun `test Tree-sitter relationship benchmark cases have no wrong or forced pairs`() {
        // engines/05 R21, R30
        val output = File("build/reports/smartdiff-relationships-treesitter").apply { mkdirs() }
        val report = RelationshipBenchmark.run(
            corpus = File(CORPUS),
            output = output,
            minimum = 0,
            commitMinimum = 0,
            engines = listOf(
                RelationshipBenchmark.BenchmarkEngine(engine, LANGUAGES.keys) { request ->
                    engine.trees(request, Cancellation.NONE)?.first
                },
            ),
        )
        assertTrue("no wrong or forced pairs: ${report.gateFailures}", report.gateFailures.isEmpty())
        // Every language is counted before any assertion, so one run shows them all.
        val problems = mutableListOf<String>()
        for (corpus in LANGUAGES.values) {
            val metrics = report.metricsByLanguage[corpus]
            if (metrics == null) {
                problems += "$corpus: no row"
                continue
            }
            val tp = metrics.perKind.values.sumOf { it.tp }
            val fp = metrics.perKind.values.sumOf { it.fp }
            val fn = metrics.perKind.values.sumOf { it.fn }
            println("TREE-SITTER BENCHMARK $corpus tp=$tp fp=$fp fn=$fn report=${output.absolutePath}")
            if (fp != 0) problems += "$corpus: $fp wrong relationships"
            if (fn != 0) problems += "$corpus: $fn labelled relationships not found"
            if (tp == 0) problems += "$corpus: no relationship found"
        }
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    fun `test every Tree-sitter language covers the relationship categories`() {
        // engines/05 R21, R30: renames, a kind change, a move, extraction and inlining, for each language. Moves inside
        // declarations (relationships/08) read changed runs, which the Tree-sitter engine does not compute.
        val labels = RelationshipBenchmark.parseCorpus(File(CORPUS)).mapNotNull { it.path }
            .map { RelationshipBenchmark.parseLabel(File(it, "label.yaml")) }
        for (corpus in LANGUAGES.values) {
            val categories = labels.filter { it.corpus == corpus }.map { it.category }.toSet()
            for (category in CATEGORIES) assertTrue("$corpus: $category", category in categories)
        }
    }

    fun `test every Tree-sitter case folder is listed`() {
        // engines/05 R30
        val listed = RelationshipBenchmark.parseCorpus(File(CORPUS)).mapNotNull { it.path?.normalize()?.path }.toSet()
        val folders = File("src/test/testData/treesitter").walkTopDown()
            .filter { it.isFile && it.name == "label.yaml" }
            .map { it.parentFile.normalize().path }
            .toList()
        assertTrue("Tree-sitter case folders exist", folders.isNotEmpty())
        for (folder in folders) assertTrue("listed: $folder", folder in listed)
    }

    private companion object {
        const val CORPUS = "src/test/testData/relationships-corpus-treesitter.txt"

        /** Language id to benchmark corpus. */
        val LANGUAGES = mapOf(
            LanguageIds.TREE_SITTER_SWIFT to "swift",
            LanguageIds.TREE_SITTER_C_SHARP to "c-sharp",
            LanguageIds.TREE_SITTER_C to "c",
            LanguageIds.TREE_SITTER_CPP to "cpp",
        )

        val CATEGORIES = listOf(
            "method-rename",
            "class-rename",
            "rename-with-body-change",
            "kind-change",
            "move-in-file",
            "extraction",
            "inlining",
        )
    }
}
