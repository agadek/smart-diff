package io.github.agadek.smartdiff.engine.declarations.psi.properties

import com.intellij.lang.properties.IProperty
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.ProviderTestCase
import java.io.File

class PropertiesDeclarationProviderTest : ProviderTestCase() {

    // languages/11-properties R3
    fun `test properties are keys named by unescaped key`() {
        val text = "host=localhost\nport=8080\n"
        assertTree("""
            key host  "host=localhost"
            key port  "port=8080"
        """, build("Properties", text, "config.properties"), text)
    }

    // languages/11-properties R3
    fun `test escaped key name is decoded`() {
        val text = "a\\ b=value\n"
        val tree = build("Properties", text, "config.properties")
        assertEquals(listOf("a b"), tree.topLevel.map { it.name })
        assertEquals(text.substring(tree.topLevel.single().range.start, tree.topLevel.single().range.end), "a\\ b=value")
    }

    // languages/11-properties R4
    fun `test dotted keys are flat top-level declarations`() {
        val text = "spring.datasource.url=jdbc:h2:mem\nspring.datasource.user=sa\n"
        val tree = build("Properties", text, "application.properties")
        assertEquals(listOf("spring.datasource.url", "spring.datasource.user"), tree.topLevel.map { it.name })
        assertTrue(tree.topLevel.all { it.children.isEmpty() })
    }

    // languages/11-properties R11
    fun `test duplicate keys are separate declarations`() {
        val text = "a=1\na=2\n"
        val tree = build("Properties", text, "config.properties")
        assertEquals(listOf("a", "a"), tree.topLevel.map { it.name })
    }

    // languages/11-properties R10, R12
    fun `test a renamed key with the same value is a rename candidate and keys have no body`() {
        val result = DeclarationEngine(null).compare(
            ComparisonRequest("timeout=30\n", "timeoutMs=30\n", "Properties", "config.properties"),
            Cancellation.NONE,
        )
        assertEquals(Status.ANALYZED, result.status)
        val rename = result.relationships.single { RelationshipKind.RENAME in it.kinds }
        assertEquals("timeout", (rename.old.single().subject as Subject.Declaration).name)
        assertEquals("timeoutMs", (rename.new.single().subject as Subject.Declaration).name)
        val tree = build("Properties", "a=1\n", "config.properties")
        assertTrue(tree.all.all { it.body == null })
    }

    // languages/11-properties R7
    fun `test spacing around separator is a change`() {
        val result = DeclarationEngine(null).compare(
            ComparisonRequest("a=1\n", "a = 1\n", "Properties", "config.properties"),
            Cancellation.NONE,
        )
        assertEquals(Status.ANALYZED, result.status)
        fun all(nodes: List<io.github.agadek.smartdiff.engine.api.ChangeNode>): List<io.github.agadek.smartdiff.engine.api.ChangeNode> =
            nodes.flatMap { listOf(it) + all(it.children) }
        assertTrue(
            "spacing around separator changes the key: ${result.changes}",
            all(result.changes).any { it.content == Content.CHANGED && it.presence == io.github.agadek.smartdiff.engine.api.Presence.MATCHED },
        )
    }

    // languages/11-properties R1
    fun `test the optional Properties descriptor registers the provider`() {
        val plugin = File("src/main/resources/META-INF/plugin.xml").readText()
        assertTrue(
            "plugin.xml declares the optional properties dependency",
            plugin.contains("<depends optional=\"true\" config-file=\"smartdiff-properties.xml\">com.intellij.properties</depends>"),
        )
        val descriptor = File("src/main/resources/META-INF/smartdiff-properties.xml").readText()
        assertTrue(
            "smartdiff-properties.xml registers the properties provider",
            descriptor.contains("io.github.agadek.smartdiff.engine.declarations.psi.properties.PropertiesDeclarationProvider"),
        )
        assertEquals(WhitespacePolicy.INSIGNIFICANT, build("Properties", "a=1\n", "config.properties").whitespacePolicy)
    }

    // languages/11-properties R2, S10
    fun `test property ranges match PSI elements for escaped keys comments continuations and empty value`() {
        val text = """
            # comment
            ! comment
            a\ b=value
            long=line1 \
              line2
            empty=
        """.trimIndent() + "\n"
        assertS10(text, listOf("a b", "long", "empty"))
    }

    // languages/11-properties S10
    fun `test property ranges match PSI elements with CRLF line ends`() {
        val text = "a=1\r\nb=2\r\n"
        assertS10(text, listOf("a", "b"))
    }

    private fun assertS10(text: String, expectedNames: List<String>) {
        val provider = provider("Properties")
        val (tree, elementTexts, errorCount) = ReadAction.compute(ThrowableComputable<Triple<io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree, List<String>, Int>, RuntimeException> {
            val project = ProjectManager.getInstance().defaultProject
            val file = provider.parse(project, text, "config.properties", null)
            val tree = provider.build(file, Cancellation.NONE)
            val props = (file as PropertiesFile).properties.mapNotNull { it.psiElement }
            Triple(tree, props.map { it.text }, PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).size)
        })
        assertEquals("declaration names", expectedNames, tree.all.map { it.name })
        assertEquals("a PSI element per declaration", tree.all.size, elementTexts.size)
        tree.all.zip(elementTexts).forEach { (declaration, psiText) ->
            assertEquals("PSI text of ${declaration.name}", psiText, text.substring(declaration.range.start, declaration.range.end))
        }
        // The lexer accepts almost any line; S10 records which inputs give error elements. None expected here.
        assertEquals("PSI errors", 0, errorCount)
    }
}
