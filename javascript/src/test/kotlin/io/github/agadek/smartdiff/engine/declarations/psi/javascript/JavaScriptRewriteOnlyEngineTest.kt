package io.github.agadek.smartdiff.engine.declarations.psi.javascript

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
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.JS_NUMERIC_LITERALS
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.JS_REDUNDANT_PARENTHESES
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.JS_STRING_QUOTES
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.JS_TRAILING_COMMAS
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.Rendering

/** The engine's rewrite-only fact on the JavaScript provider (declarations/11). Classification itself stays changed. */
class JavaScriptRewriteOnlyEngineTest : BasePlatformTestCase() {

    private val old = """
        function hex() { return 0x1F; }
        function quoted() { return 'A\x21'; }
        function comma(a, b,) { return [a, b,]; }
        function grouped(a, b) { return (a + b); }
        function fixed() { return 1; }
    """.trimIndent() + "\n"

    private val new = """
        function hex() { return 31; }
        function quoted() { return "A!"; }
        function comma(a, b) { return [a, b]; }
        function grouped(a, b) { return a + b; }
        function fixed() { return 2; }
    """.trimIndent() + "\n"

    // declarations/11-javascript-python-go-equivalence-rules R1, R4 to R7, R20, R21
    fun `test a JavaScript, TypeScript, JSX and TSX function with each rule is rewrite-only`() {
        listOf("a.js" to "JavaScript", "a.ts" to "TypeScript", "a.jsx" to "JavaScript", "a.tsx" to "TypeScript JSX").forEach { (file, language) ->
            val result = compare(old, new, language, file)
            assertRules(result, "hex", setOf(JS_NUMERIC_LITERALS), file)
            assertRules(result, "quoted", setOf(JS_STRING_QUOTES), file)
            assertRules(result, "comma", setOf(JS_TRAILING_COMMAS), file)
            assertRules(result, "grouped", setOf(JS_REDUNDANT_PARENTHESES), file)
            assertRules(result, "fixed", emptySet(), file)
        }
    }

    // declarations/11-javascript-python-go-equivalence-rules R1, R8
    fun `test a comment change and a value after return on its own line stay changed`() {
        val o = "function noted() { return 0x1F; // hex\n}\nfunction late() {\n  return (\n    x\n  );\n}\n"
        val n = "function noted() { return 31; // decimal\n}\nfunction late() {\n  return x;\n}\n"
        val result = compare(o, n, "JavaScript", "a.js")
        assertRules(result, "noted", emptySet(), "a.js")
        assertRules(result, "late", emptySet(), "a.js")
    }

    // declarations/11-javascript-python-go-equivalence-rules R21
    fun `test JavaScript never uses another language's rules`() {
        // Java's octal escape is legacy octal in JavaScript, and Kotlin's \$ is an identity escape: both keep their text.
        val o = "function octal() { return '\\101'; }\nfunction dollar() { return '\\$'; }\n"
        val n = "function octal() { return 'A'; }\nfunction dollar() { return '$'; }\n"
        val result = compare(o, n, "JavaScript", "a.js")
        assertRules(result, "octal", emptySet(), "a.js")
        assertRules(result, "dollar", emptySet(), "a.js")
    }

    private fun compare(old: String, new: String, language: String, file: String): ComparisonResult {
        texts = old to new
        val result = ReadAction.compute(ThrowableComputable {
            DeclarationEngine(ProjectManager.getInstance().defaultProject)
                .compare(ComparisonRequest(old, new, language, file), Cancellation.NONE)
        })
        assertEquals(Status.ANALYZED, result.status)
        return result
    }

    private fun declaration(result: ComparisonResult, name: String): ChangeNode {
        fun find(nodes: List<ChangeNode>): ChangeNode? =
            nodes.firstNotNullOfOrNull { node -> node.takeIf { (it.subject as? Subject.Declaration)?.name == name } ?: find(node.children) }
        return find(result.changes) ?: error("no $name in ${result.changes}")
    }

    private var texts = "" to ""

    private fun assertRules(result: ComparisonResult, name: String, rules: Set<EquivalenceRule>, file: String) {
        val node = declaration(result, name)
        assertEquals("$name stays changed in $file: ${Rendering.result(result, texts.first, texts.second)}", Content.CHANGED, node.content)
        assertEquals("$name rules in $file", rules, node.rewriteRules)
    }
}
