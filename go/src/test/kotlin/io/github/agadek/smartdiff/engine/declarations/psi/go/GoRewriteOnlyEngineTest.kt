package io.github.agadek.smartdiff.engine.declarations.psi.go

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.EquivalenceRule
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.GO_IMPORT_ORDER
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.GO_NUMERIC_LITERALS
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.GO_REDUNDANT_PARENTHESES
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.GO_STRING_LITERALS
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.GO_TRAILING_COMMAS
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.Rendering

/** The engine's rewrite-only fact on the Go provider (declarations/11). Classification itself stays changed. */
class GoRewriteOnlyEngineTest : BasePlatformTestCase() {

    private var texts = "" to ""

    // declarations/11-javascript-python-go-equivalence-rules R1, R14 to R17, R21
    fun `test a function with each rule is rewrite-only`() {
        val old = """
            package p

            func hex() int { return 0x1F }

            func quoted() string { return "A\x21" }

            func comma(a, b int,) []int { return []int{a, b,} }

            func grouped(a, b int) int { return (a + b) }

            func fixed() int { return 1 }
        """.trimIndent() + "\n"
        val new = """
            package p

            func hex() int { return 31 }

            func quoted() string { return `A!` }

            func comma(a, b int) []int { return []int{a, b} }

            func grouped(a, b int) int { return a + b }

            func fixed() int { return 2 }
        """.trimIndent() + "\n"
        val result = compare(old, new)
        assertRules(result, "hex", setOf(GO_NUMERIC_LITERALS))
        assertRules(result, "quoted", setOf(GO_STRING_LITERALS))
        assertRules(result, "comma", setOf(GO_TRAILING_COMMAS))
        assertRules(result, "grouped", setOf(GO_REDUNDANT_PARENTHESES))
        assertRules(result, "fixed", emptySet())
    }

    // declarations/11-javascript-python-go-equivalence-rules R1, R19
    fun `test the file region is rewrite-only under import order`() {
        val old = "package p\n\nimport (\n\t\"os\"\n\t\"fmt\"\n)\n\nfunc f() { fmt.Println(os.Args) }\n"
        val new = "package p\n\nimport (\n\t\"fmt\"\n\t\"os\"\n)\n\nfunc f() { fmt.Println(os.Args) }\n"
        val result = compare(old, new)
        val region = result.fileRegion ?: error("no file region: ${Rendering.result(result, old, new)}")
        assertEquals(Content.CHANGED, region.content)
        assertEquals(setOf(GO_IMPORT_ORDER), region.rewriteRules)
    }

    // declarations/11-javascript-python-go-equivalence-rules R1
    fun `test a comment change stays changed`() {
        val old = "package p\n\nfunc noted() int {\n\treturn 0x1F // hex\n}\n"
        val new = "package p\n\nfunc noted() int {\n\treturn 31 // decimal\n}\n"
        assertRules(compare(old, new), "noted", emptySet())
    }

    // declarations/11-javascript-python-go-equivalence-rules R21
    fun `test Go never uses another language's rules`() {
        // JavaScript's \\u{…} and Kotlin's \\$ are unknown escapes in Go and keep their text.
        val old = "package p\n\nfunc braced() string { return \"\\u{41}\" }\n\nfunc dollar() string { return \"\\$\" }\n"
        val new = "package p\n\nfunc braced() string { return \"A\" }\n\nfunc dollar() string { return \"$\" }\n"
        val result = compare(old, new)
        assertRules(result, "braced", emptySet())
        assertRules(result, "dollar", emptySet())
    }

    private fun compare(old: String, new: String): ComparisonResult {
        texts = old to new
        val result = ReadAction.compute(ThrowableComputable {
            DeclarationEngine(ProjectManager.getInstance().defaultProject)
                .compare(ComparisonRequest(old, new, "go", "a.go"), Cancellation.NONE)
        })
        assertEquals(Status.ANALYZED, result.status)
        return result
    }

    private fun declaration(result: ComparisonResult, name: String): ChangeNode {
        fun find(nodes: List<ChangeNode>): ChangeNode? =
            nodes.firstNotNullOfOrNull { node -> node.takeIf { (it.subject as? Subject.Declaration)?.name == name } ?: find(node.children) }
        return find(result.changes) ?: error("no $name in ${result.changes}")
    }

    private fun assertRules(result: ComparisonResult, name: String, rules: Set<EquivalenceRule>) {
        val node = declaration(result, name)
        assertEquals("$name stays changed: ${Rendering.result(result, texts.first, texts.second)}", Content.CHANGED, node.content)
        assertEquals("$name rules", rules, node.rewriteRules)
    }
}
