package io.github.agadek.smartdiff.engine.declarations.psi.structure

import io.github.agadek.smartdiff.engine.api.TextRange
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree

/**
 * What the bundled structure views give on text-built files, recorded by S9 (languages/06): exact kinds, names,
 * nesting and ranges. A change here is a change in what the fallback shows, so both the spike and the provider test
 * check it.
 */
internal object StructureViewCases {

    class Case(
        val fileName: String,
        val languageId: String,
        val text: String,
        val expected: List<String>,
        val fileRegion: List<TextRange>,
    )

    val all = listOf(
        Case(
            fileName = "a.json",
            languageId = "JSON",
            text = """{"a": 1, "b": {"c": 2}}""",
            expected = listOf(
                "PROPERTY a [1, 7)",
                "PROPERTY b [9, 22)",
                "  PROPERTY c [15, 21)",
            ),
            fileRegion = listOf(TextRange(0, 1), TextRange(22, 23)),
        ),
        Case(
            fileName = "a.yaml",
            languageId = "yaml",
            text = "root:\n  child: value\n  other: 1\n",
            expected = listOf(
                "ITEM YAML document [0, 31)",
                "  ITEM root [0, 31)",
                "    ITEM child [8, 20)",
                "    ITEM other [23, 31)",
            ),
            fileRegion = listOf(TextRange(31, 32)),
        ),
        Case(
            fileName = "a.md",
            languageId = "Markdown",
            text = "# Title\n\nText\n\n## Section\n\nMore\n",
            expected = listOf(
                "ITEM Title [0, 7)",
                "  ITEM Section [15, 25)",
            ),
            fileRegion = listOf(TextRange(7, 32)),
        ),
        Case(
            fileName = "a.properties",
            languageId = "Properties",
            text = "foo.bar=1\nbaz=2\n",
            expected = listOf(
                "PROPERTY foo.bar [0, 9)",
                "PROPERTY baz [10, 15)",
            ),
            fileRegion = listOf(TextRange(15, 16)),
        ),
        Case(
            fileName = "A.groovy",
            languageId = "Groovy",
            text = "class A {\n  void foo() {}\n  int bar\n}\n",
            expected = listOf(
                "CLASS A [0, 37)",
                "  FIELD bar: int [32, 35)",
                "  METHOD foo(): void [12, 25)",
            ),
            fileRegion = listOf(TextRange(37, 38)),
        ),
    )

    fun outline(tree: DeclarationTree): List<String> {
        val rows = mutableListOf<String>()
        fun visit(declaration: Declaration, depth: Int) {
            rows += "  ".repeat(depth) + "${declaration.kind} ${declaration.name} ${declaration.range}"
            declaration.children.forEach { visit(it, depth + 1) }
        }
        tree.topLevel.forEach { visit(it, 0) }
        return rows
    }
}
