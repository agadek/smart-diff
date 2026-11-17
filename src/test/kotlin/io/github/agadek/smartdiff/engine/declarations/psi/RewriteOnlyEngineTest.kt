package io.github.agadek.smartdiff.engine.declarations.psi

import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.EquivalenceRule
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.JAVA_NUMERIC_LITERALS
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.JAVA_REDUNDANT_PARENTHESES
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.JAVA_STRING_ESCAPES
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject

/** The engine's rewrite-only fact on real providers (declarations/08). Classification itself stays changed. */
class RewriteOnlyEngineTest : ProviderTestCase() {

    // declarations/08-equivalence-rules R4, R10, R11, R12
    fun `test a Java method with each rule is rewrite-only`() {
        val old = """
            class A {
                int hex() { return 0x1F; }
                String escaped() { return "\u0041\101"; }
                int grouped(int a, int b) { return (a + b); }
                int fixed() { return 1; }
            }
        """.trimIndent() + "\n"
        val new = """
            class A {
                int hex() { return 31; }
                String escaped() { return "AA"; }
                int grouped(int a, int b) { return a + b; }
                int fixed() { return 2; }
            }
        """.trimIndent() + "\n"
        val result = compare(old, new, "JAVA", "A.java")
        assertRules(result, "hex", setOf(JAVA_NUMERIC_LITERALS), old, new)
        assertRules(result, "escaped", setOf(JAVA_STRING_ESCAPES), old, new)
        assertRules(result, "grouped", setOf(JAVA_REDUNDANT_PARENTHESES), old, new)
        assertRules(result, "fixed", emptySet(), old, new)
    }

    // declarations/08-equivalence-rules R4, R10
    fun `test a field initializer is rewrite-only`() {
        val old = "class A {\n    static final long MASK = 0xF_FFFFL;\n}\n"
        val new = "class A {\n    static final long MASK = 1048575L;\n}\n"
        assertRules(compare(old, new, "JAVA", "A.java"), "MASK", setOf(JAVA_NUMERIC_LITERALS), old, new)
    }

    // declarations/08-equivalence-rules R9
    fun `test the file region is rewrite-only under the same rule`() {
        val old = "@Version((0x10))\npackage p;\n\nclass A {}\n"
        val new = "@Version(16)\npackage p;\n\nclass A {}\n"
        val result = compare(old, new, "JAVA", "package-info.java")
        val region = result.fileRegion
        assertNotNull("a file region: ${Rendering.result(result, old, new)}", region)
        assertEquals(Content.CHANGED, region!!.content)
        assertEquals(setOf(JAVA_NUMERIC_LITERALS, JAVA_REDUNDANT_PARENTHESES), region.rewriteRules)
    }

    // declarations/08-equivalence-rules R18
    fun `test a Kotlin file with the same edits stays changed`() {
        val old = "fun hex() = 0x1F\n\nfun grouped(a: Int, b: Int) = (a + b)\n"
        val new = "fun hex() = 31\n\nfun grouped(a: Int, b: Int) = a + b\n"
        val result = compare(old, new, "kotlin", "A.kt")
        assertRules(result, "hex", emptySet(), old, new)
        assertRules(result, "grouped", emptySet(), old, new)
    }

    private fun compare(old: String, new: String, language: String, file: String): ComparisonResult {
        val result = DeclarationEngine(null).compare(ComparisonRequest(old, new, language, file), Cancellation.NONE)
        assertEquals(Status.ANALYZED, result.status)
        return result
    }

    private fun declaration(result: ComparisonResult, name: String): ChangeNode {
        fun find(nodes: List<ChangeNode>): ChangeNode? =
            nodes.firstNotNullOfOrNull { node -> node.takeIf { (it.subject as? Subject.Declaration)?.name == name } ?: find(node.children) }
        return find(result.changes) ?: error("no $name in ${result.changes}")
    }

    private fun assertRules(result: ComparisonResult, name: String, rules: Set<EquivalenceRule>, old: String, new: String) {
        val node = declaration(result, name)
        assertEquals("$name stays changed: ${Rendering.result(result, old, new)}", Content.CHANGED, node.content)
        assertEquals("$name rules: ${Rendering.result(result, old, new)}", rules, node.rewriteRules)
    }
}
