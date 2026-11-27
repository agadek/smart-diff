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
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.KOTLIN_NUMERIC_LITERALS
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.KOTLIN_REDUNDANT_PARENTHESES
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.KOTLIN_STRING_ESCAPES
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.KOTLIN_TEMPLATE_BRACES
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.PYTHON_NUMERIC_LITERALS
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.PYTHON_REDUNDANT_PARENTHESES
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.PYTHON_STRING_QUOTES
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.PYTHON_TRAILING_COMMAS
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.SCALA_NUMERIC_LITERALS
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.SCALA_REDUNDANT_PARENTHESES
import io.github.agadek.smartdiff.engine.api.EquivalenceRule.SCALA_STRING_ESCAPES
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

    // declarations/08-equivalence-rules R18; declarations/09-kotlin-scala-equivalence-rules R16
    fun `test a Groovy file with the same edits stays changed`() {
        // declarations/11 R22: Python now has rules, so the language without them is Groovy.
        val old = "class A {\n    int hex() { return 0x1F }\n    int grouped(int a, int b) { return (a + b) }\n}\n"
        val new = "class A {\n    int hex() { return 31 }\n    int grouped(int a, int b) { return a + b }\n}\n"
        val result = compare(old, new, "Groovy", "A.groovy")
        assertRules(result, "hex", emptySet(), old, new)
        assertRules(result, "grouped", emptySet(), old, new)
    }

    // declarations/11-javascript-python-go-equivalence-rules R1, R9 to R12, R21
    fun `test a Python function with each rule is rewrite-only`() {
        val old = listOf(
            "def hex():\n    return 0x1F\n",
            "def quoted():\n    return 'A\\x21'\n",
            "def comma(a, b,):\n    return [a, b,]\n",
            "def grouped(a, b):\n    return (a + b)\n",
            "def fixed():\n    return 1\n",
        ).joinToString("\n\n")
        val new = listOf(
            "def hex():\n    return 31\n",
            "def quoted():\n    return \"A!\"\n",
            "def comma(a, b):\n    return [a, b]\n",
            "def grouped(a, b):\n    return a + b\n",
            "def fixed():\n    return 2\n",
        ).joinToString("\n\n")
        val result = compare(old, new, "Python", "a.py")
        assertRules(result, "hex", setOf(PYTHON_NUMERIC_LITERALS), old, new)
        assertRules(result, "quoted", setOf(PYTHON_STRING_QUOTES), old, new)
        assertRules(result, "comma", setOf(PYTHON_TRAILING_COMMAS), old, new)
        assertRules(result, "grouped", setOf(PYTHON_REDUNDANT_PARENTHESES), old, new)
        assertRules(result, "fixed", emptySet(), old, new)
    }

    // declarations/11-javascript-python-go-equivalence-rules R1, R11
    fun `test a Python tuple comma and a comment change stay changed`() {
        val old = "def single():\n    return (1,)\n\n\ndef noted():\n    return 0x1F  # hex\n"
        val new = "def single():\n    return (1)\n\n\ndef noted():\n    return 31  # decimal\n"
        val result = compare(old, new, "Python", "a.py")
        assertRules(result, "single", emptySet(), old, new)
        assertRules(result, "noted", emptySet(), old, new)
    }

    // declarations/11-javascript-python-go-equivalence-rules R21
    fun `test Python never uses another language's rules`() {
        // Kotlin's \$ escape is an unknown escape to Python, and a quoted character is a string, not Go's rune.
        val old = "def dollar():\n    return 'a\\$'\n\n\ndef rune():\n    return '\\x61'\n"
        val new = "def dollar():\n    return 'a$'\n\n\ndef rune():\n    return 97\n"
        val result = compare(old, new, "Python", "a.py")
        assertRules(result, "dollar", emptySet(), old, new)
        assertRules(result, "rune", emptySet(), old, new)
    }

    // declarations/09-kotlin-scala-equivalence-rules R1, R4 to R7, R15
    fun `test a Kotlin function with each rule is rewrite-only`() {
        val d = "$"
        val old = """
            fun hex() = 0x1F
            fun escaped() = "\u0041\$"
            fun braces(name: String) = "Hi $d{name}!"
            fun grouped(a: Int, b: Int): Int {
                return (a + b)
            }
            fun fixed() = 1
        """.trimIndent() + "\n"
        val new = """
            fun hex() = 31
            fun escaped() = "A$"
            fun braces(name: String) = "Hi ${d}name!"
            fun grouped(a: Int, b: Int): Int {
                return a + b
            }
            fun fixed() = 2
        """.trimIndent() + "\n"
        val result = compare(old, new, "kotlin", "A.kt")
        assertRules(result, "hex", setOf(KOTLIN_NUMERIC_LITERALS), old, new)
        assertRules(result, "escaped", setOf(KOTLIN_STRING_ESCAPES), old, new)
        assertRules(result, "braces", setOf(KOTLIN_TEMPLATE_BRACES), old, new)
        assertRules(result, "grouped", setOf(KOTLIN_REDUNDANT_PARENTHESES), old, new)
        assertRules(result, "fixed", emptySet(), old, new)
    }

    // declarations/09-kotlin-scala-equivalence-rules R14
    fun `test a Kotlin script's file region is rewrite-only`() {
        val d = "$"
        val old = "val name = \"x\"\nprintln(\"$d{name}!\")\nprintln((0x10))\n"
        val new = "val name = \"x\"\nprintln(\"${d}name!\")\nprintln(16)\n"
        val region = compare(old, new, "kotlin", "script.kts").fileRegion
        assertNotNull(region)
        assertEquals(Content.CHANGED, region!!.content)
        assertEquals(setOf(KOTLIN_TEMPLATE_BRACES, KOTLIN_NUMERIC_LITERALS, KOTLIN_REDUNDANT_PARENTHESES), region.rewriteRules)
    }

    // declarations/09-kotlin-scala-equivalence-rules R14
    fun `test a Gradle build script section is rewrite-only`() {
        val d = "$"
        val old = "plugins {\n    java\n}\n\ndependencies {\n    implementation(\"a:b:$d{version}\")\n}\n"
        val new = "plugins {\n    java\n}\n\ndependencies {\n    implementation(\"a:b:${d}version\")\n}\n"
        val result = compare(old, new, "kotlin", "build.gradle.kts")
        assertRules(result, "dependencies", setOf(KOTLIN_TEMPLATE_BRACES), old, new)
    }

    // declarations/09-kotlin-scala-equivalence-rules R1, R9 to R11, R13
    fun `test a Scala 2 method with each rule is rewrite-only`() {
        val old = """
            class A {
              def hex: Int = 0x1F
              def escaped: String = "\u0041"
              def grouped(a: Int, b: Int): Int = {
                return (a + b)
              }
              def fixed: Int = 1
            }
        """.trimIndent() + "\n"
        val new = """
            class A {
              def hex: Int = 31
              def escaped: String = "A"
              def grouped(a: Int, b: Int): Int = {
                return a + b
              }
              def fixed: Int = 2
            }
        """.trimIndent() + "\n"
        val result = compare(old, new, "Scala", "A.scala")
        assertRules(result, "hex", setOf(SCALA_NUMERIC_LITERALS), old, new)
        assertRules(result, "escaped", setOf(SCALA_STRING_ESCAPES), old, new)
        assertRules(result, "grouped", setOf(SCALA_REDUNDANT_PARENTHESES), old, new)
        assertRules(result, "fixed", emptySet(), old, new)
    }

    // declarations/09-kotlin-scala-equivalence-rules R9 to R11, R13
    fun `test a Scala 3 method with each rule is rewrite-only`() {
        val old = """
            class A:
              def hex: Long = 0xFFL
              def escaped: Char = '\t'
              def grouped(a: Int, b: Int): Int =
                (a +
                  b)
              def fixed: Int = 1
        """.trimIndent() + "\n"
        val new = """
            class A:
              def hex: Long = 255L
              def escaped: Char = '\u0009'
              def grouped(a: Int, b: Int): Int =
                a +
                  b
              def fixed: Int = 2
        """.trimIndent() + "\n"
        val result = compare(old, new, "Scala", "A.scala")
        assertRules(result, "hex", setOf(SCALA_NUMERIC_LITERALS), old, new)
        assertRules(result, "escaped", setOf(SCALA_STRING_ESCAPES), old, new)
        assertRules(result, "grouped", setOf(SCALA_REDUNDANT_PARENTHESES), old, new)
        assertRules(result, "fixed", emptySet(), old, new)
    }

    // declarations/09-kotlin-scala-equivalence-rules R15
    fun `test Java-only edits in Kotlin and Scala files stay changed`() {
        val kOld = "fun octal() = \"\\101\"\n\nfun spaced() = \"\\s\"\n"
        val kNew = "fun octal() = \"A\"\n\nfun spaced() = \" \"\n"
        val kotlin = compare(kOld, kNew, "kotlin", "A.kt")
        assertRules(kotlin, "octal", emptySet(), kOld, kNew)
        assertRules(kotlin, "spaced", emptySet(), kOld, kNew)
        val sOld = "object A {\n  def octal = \"\\101\"\n  def binary = 0x1\n}\n"
        val sNew = "object A {\n  def octal = \"A\"\n  def binary = 0b1\n}\n"
        val scala = compare(sOld, sNew, "Scala", "A.scala")
        assertRules(scala, "octal", emptySet(), sOld, sNew)
        assertRules(scala, "binary", emptySet(), sOld, sNew)
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
