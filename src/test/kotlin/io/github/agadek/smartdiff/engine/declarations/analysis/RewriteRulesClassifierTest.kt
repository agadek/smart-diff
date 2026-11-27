package io.github.agadek.smartdiff.engine.declarations.analysis

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.EquivalenceRule
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.JAVA_NUMERIC_LITERALS
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.JAVA_REDUNDANT_PARENTHESES
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.JAVA_STRING_ESCAPES
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.EquivalenceRuleImpl
import io.github.agadek.smartdiff.engine.declarations.psi.java.JavaEquivalenceRules
import io.github.agadek.smartdiff.engine.declarations.psi.java.javaTokens
import io.github.agadek.smartdiff.engine.declarations.psi.kotlin.KotlinEquivalenceRules
import io.github.agadek.smartdiff.engine.declarations.psi.kotlin.kotlinTokens
import io.github.agadek.smartdiff.engine.declarations.psi.python.PythonEquivalenceRules
import io.github.agadek.smartdiff.engine.declarations.psi.python.pythonTokens

/** The rule set of a node, [ContentClassifier.rewriteRules], on Java tokens (declarations/08 R4 to R7). */
class RewriteRulesClassifierTest : BasePlatformTestCase() {

    private fun rules(
        old: String,
        new: String,
        parseError: Boolean = false,
        signatureChanged: Boolean = false,
        rules: List<EquivalenceRuleImpl> = JavaEquivalenceRules.ALL,
    ): Set<EquivalenceRule> = ContentClassifier.rewriteRules(
        javaTokens(old), javaTokens(new), WhitespacePolicy.INSIGNIFICANT, parseError, signatureChanged, rules,
    )

    // declarations/08-equivalence-rules R4
    fun `test each rule alone is the smallest set`() {
        assertEquals(setOf(JAVA_NUMERIC_LITERALS), rules("int f() { return 0x10; }", "int f() { return 16; }"))
        assertEquals(setOf(JAVA_STRING_ESCAPES), rules("String f() { return \"\\101\"; }", "String f() { return \"A\"; }"))
        assertEquals(setOf(JAVA_REDUNDANT_PARENTHESES), rules("int f() { return (a); }", "int f() { return a; }"))
    }

    // declarations/08-equivalence-rules R4
    fun `test the set holds only the rules that are needed`() {
        assertEquals(
            setOf(JAVA_NUMERIC_LITERALS, JAVA_REDUNDANT_PARENTHESES),
            rules("int f() { return (0x10); }", "int f() { return 16; }"),
        )
        assertEquals(
            setOf(JAVA_NUMERIC_LITERALS, JAVA_STRING_ESCAPES, JAVA_REDUNDANT_PARENTHESES),
            rules("void f() { x = (0x10); s = \"\\u0041\"; }", "void f() { x = 16; s = \"A\"; }"),
        )
    }

    // declarations/08-equivalence-rules R5
    fun `test a comment change is never rewrite-only`() {
        assertEquals(emptySet<EquivalenceRule>(), rules("int f() { return 0x10; } // old", "int f() { return 16; } // new"))
        assertEquals(emptySet<EquivalenceRule>(), rules("int f() { return 0x10; }", "int f() { /* hex */ return 16; }"))
    }

    // declarations/08-equivalence-rules R5
    fun `test a changed signature or a parse error is never rewrite-only`() {
        assertEquals(emptySet<EquivalenceRule>(), rules("int f() { return 0x10; }", "int f() { return 16; }", signatureChanged = true))
        assertEquals(emptySet<EquivalenceRule>(), rules("int f() { return 0x10; }", "int f() { return 16; }", parseError = true))
    }

    // declarations/08-equivalence-rules R6
    fun `test formatting and a rewrite together are rewrite-only`() {
        assertEquals(setOf(JAVA_NUMERIC_LITERALS), rules("int f() { return 0x10; }", "int f() {\n    return   16;\n}"))
    }

    // declarations/08-equivalence-rules R4
    fun `test a rewrite and a real change together are not rewrite-only`() {
        assertEquals(emptySet<EquivalenceRule>(), rules("int f() { return 0x10 + a; }", "int f() { return 16 + b; }"))
        assertEquals(emptySet<EquivalenceRule>(), rules("int f() { return (a + b) * c; }", "int f() { return a + b * c; }"))
    }

    // declarations/08-equivalence-rules R4
    fun `test identical sources are not rewrite-only`() {
        assertEquals(emptySet<EquivalenceRule>(), rules("int f() { return 16; }", "int f() { return 16; }"))
    }

    // declarations/08-equivalence-rules R18
    fun `test a provider without rules never produces a rule set`() {
        assertEquals(emptySet<EquivalenceRule>(), rules("int f() { return 0x10; }", "int f() { return 16; }", rules = emptyList()))
    }

    private fun kotlinRules(old: String, new: String): Set<EquivalenceRule> = ContentClassifier.rewriteRules(
        kotlinTokens(old), kotlinTokens(new), WhitespacePolicy.KOTLIN_LINE_BREAKS, parseError = false, rules = KotlinEquivalenceRules.ALL,
    )

    // declarations/09-kotlin-scala-equivalence-rules R1, R6
    fun `test template braces and escapes together are the smallest set`() {
        val d = "$"
        assertEquals(
            setOf(EquivalenceRule.KOTLIN_TEMPLATE_BRACES, EquivalenceRule.KOTLIN_STRING_ESCAPES),
            kotlinRules("fun f() = \"$d{x}\\u0021\"", "fun f() = \"${d}x!\""),
        )
        assertEquals(setOf(EquivalenceRule.KOTLIN_TEMPLATE_BRACES), kotlinRules("fun f() = \"$d{x}!\"", "fun f() = \"${d}x!\""))
    }

    // declarations/11-javascript-python-go-equivalence-rules R1
    fun `test Python parentheses and a trailing comma together are the smallest set`() {
        fun pythonRules(old: String, new: String) = ContentClassifier.rewriteRules(
            pythonTokens(old), pythonTokens(new), WhitespacePolicy.INDENTATION_SIGNIFICANT, parseError = false, rules = PythonEquivalenceRules.ALL,
        )
        assertEquals(
            setOf(EquivalenceRule.PYTHON_REDUNDANT_PARENTHESES, EquivalenceRule.PYTHON_TRAILING_COMMAS),
            pythonRules("f((a), b,)\n", "f(a, b)\n"),
        )
        assertEquals(setOf(EquivalenceRule.PYTHON_TRAILING_COMMAS), pythonRules("f(a, b,)\n", "f(a, b)\n"))
        assertEquals(emptySet<EquivalenceRule>(), pythonRules("f(a, b,)  # old\n", "f(a, b)  # new\n"))
    }

    // declarations/09-kotlin-scala-equivalence-rules R1
    fun `test a Kotlin rewrite with a comment change has no rules`() {
        assertEquals(emptySet<EquivalenceRule>(), kotlinRules("fun f() = 0x10 // old", "fun f() = 16 // new"))
        assertEquals(setOf(EquivalenceRule.KOTLIN_NUMERIC_LITERALS), kotlinRules("fun f() = 0x10 // same", "fun f() = 16 // same"))
    }
}
