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
}
