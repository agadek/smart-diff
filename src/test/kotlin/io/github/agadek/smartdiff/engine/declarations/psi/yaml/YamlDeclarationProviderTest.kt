package io.github.agadek.smartdiff.engine.declarations.psi.yaml

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiFileFactory
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.ProviderTestCase
import org.jetbrains.yaml.YAMLLanguage
import java.io.File

class YamlDeclarationProviderTest : ProviderTestCase() {

    // languages/10-yaml R3
    fun `test mapping entries are keys named without quotes`() {
        val text = "host: localhost\nport: 8080\n"
        assertTree("""
            key host  "host: localhost"
            key port  "port: 8080"
        """, build("yaml", text, "config.yaml"), text)
    }

    // languages/10-yaml R3
    fun `test nested mapping members are children of the key`() {
        val text = "server:\n  host: localhost\n  port: 8080\n"
        assertTree("""
            key server  "server:"
              key host  "host: localhost"
              key port  "port: 8080"
        """, build("yaml", text, "config.yaml"), text)
    }

    // languages/10-yaml R3
    fun `test flow mapping behaves like a block mapping`() {
        val text = "server: {host: localhost, port: 8080}\n"
        val tree = build("yaml", text, "config.yaml")
        val server = tree.topLevel.single()
        assertEquals("server", server.name)
        assertEquals(listOf("host", "port"), server.children.map { it.name })
    }

    // languages/10-yaml R4
    fun `test scalar values are content of the key`() {
        val text = "plain: value\nquoted: \"a b\"\nblock: |\n  line1\n  line2\nfolded: >\n  a\n  b\n"
        val tree = build("yaml", text, "config.yaml")
        assertEquals(listOf("plain", "quoted", "block", "folded"), tree.topLevel.map { it.name })
        assertTrue(tree.topLevel.all { it.children.isEmpty() })
    }

    // languages/10-yaml R5
    fun `test sequence of mappings with shared name key becomes item keys`() {
        val text = "services:\n  - name: web\n    port: 80\n  - name: api\n    port: 8080\n"
        assertTree("""
            key services  "services:"
              key [name=web]  "- name: web"
                key name  "name: web"
                key port  "port: 80"
              key [name=api]  "- name: api"
                key name  "name: api"
                key port  "port: 8080"
        """, build("yaml", text, "config.yaml"), text)
    }

    // languages/10-yaml R5
    fun `test sequence without shared identifying key is content`() {
        val text = "items:\n  - a: 1\n  - b: 2\n"
        assertTree("""
            key items  "items:"
        """, build("yaml", text, "config.yaml"), text)
    }

    // languages/10-yaml R6, R7
    fun `test multi-document file has document sections named by kind and metadata name`() {
        val text = "---\nkind: Service\nmetadata:\n  name: web\nspec:\n  port: 80\n---\nkind: Deployment\nmetadata:\n  name: web\nspec:\n  replicas: 1\n"
        val tree = build("yaml", text, "k8s.yaml")
        assertEquals(listOf("Service/web", "Deployment/web"), tree.topLevel.map { it.name })
        assertTrue(tree.topLevel.all { it.kind.name == "SECTION" })
    }

    // languages/10-yaml R7
    fun `test unnamed documents in a multi-document file are named positionally`() {
        val multi = build("yaml", "---\na: 1\n---\nb: 2\n", "config.yaml")
        assertEquals(listOf("document 1", "document 2"), multi.topLevel.map { it.name })
    }

    // languages/10-yaml R6, R7
    fun `test first key range and own tokens exclude document marker in a multi-document file`() {
        val text = "---\na: 1\n---\nb: 2\n"
        val tree = build("yaml", text, "config.yaml")
        val doc1 = tree.topLevel[0]
        assertEquals("document 1", doc1.name)
        val keyA = doc1.children.single()
        assertEquals("a", keyA.name)
        assertEquals("a: 1", text.substring(keyA.range.start, keyA.range.end))
        assertFalse("first key range must not include ---", text.substring(keyA.range.start, keyA.range.end).contains("---"))
        val own = keyA.ownTokens.joinToString("") { it.text }
        assertFalse("first key own tokens must not include ---", own.contains("---"))
    }

    // languages/10-yaml R8
    fun `test anchor alias and merge key are represented as written`() {
        val text = "base: &base\n  x: 1\nother:\n  <<: *base\n  y: 2\nalias: *base\n"
        val tree = build("yaml", text, "config.yaml")
        val other = tree.topLevel.first { it.name == "other" }
        assertTrue("merge key present: ${other.children.map { it.name }}", other.children.any { it.name == "<<" })
        assertTrue(tree.topLevel.any { it.name == "alias" })
    }

    // languages/10-yaml R11
    fun `test duplicate keys are separate declarations`() {
        val text = "a: 1\na: 2\n"
        val tree = build("yaml", text, "config.yaml")
        assertEquals(listOf("a", "a"), tree.topLevel.map { it.name })
    }

    // languages/10-yaml R10
    fun `test a renamed key with the same value is a rename candidate`() {
        val result = DeclarationEngine(null).compare(
            ComparisonRequest("timeout: 30\n", "timeoutMs: 30\n", "yaml", "config.yaml"),
            Cancellation.NONE,
        )
        assertEquals(Status.ANALYZED, result.status)
        val rename = result.relationships.single { RelationshipKind.RENAME in it.kinds }
        assertEquals("timeout", (rename.old.single().subject as Subject.Declaration).name)
        assertEquals("timeoutMs", (rename.new.single().subject as Subject.Declaration).name)
    }

    // languages/10-yaml R1
    fun `test the optional YAML descriptor registers the provider`() {
        val plugin = File("src/main/resources/META-INF/plugin.xml").readText()
        assertTrue(
            "plugin.xml declares the optional YAML dependency",
            plugin.contains("<depends optional=\"true\" config-file=\"smartdiff-yaml.xml\">org.jetbrains.plugins.yaml</depends>"),
        )
        val descriptor = File("src/main/resources/META-INF/smartdiff-yaml.xml").readText()
        assertTrue(
            "smartdiff-yaml.xml registers the YAML provider",
            descriptor.contains("io.github.agadek.smartdiff.engine.declarations.psi.yaml.YamlDeclarationProvider"),
        )
    }

    // languages/10-yaml R2, S10
    fun `test YAML key ranges match the PSI elements`() {
        assertRanges(
            "config.yaml",
            "server:\n  host: localhost\n  port: 8080\n",
        )
    }

    // languages/10-yaml R2, R6, S10
    fun `test multi-document YAML ranges match the PSI elements`() {
        assertRanges(
            "k8s.yaml",
            "---\nkind: Service\nmetadata:\n  name: web\n---\nkind: Deployment\nmetadata:\n  name: api\n",
        )
    }

    // languages/10-yaml R8, S10
    fun `test anchors aliases merge keys flow mappings block scalars and CRLF ranges match`() {
        val lf = "base: &base\n  x: 1\nother:\n  <<: *base\n  flow: {a: 1, b: 2}\n  block: |\n    line\nalias: *base\n"
        assertRanges("a.yaml", lf)
        assertRanges("a.yaml", lf.replace("\n", "\r\n"))
    }

    private fun assertRanges(fileName: String, text: String) {
        val provider = provider("yaml")
        ReadAction.run<RuntimeException> {
            val project = com.intellij.openapi.project.ProjectManager.getInstance().defaultProject
            val file = provider.parse(project, text, fileName, provider.dialect(project, listOf(text, text), fileName))
            val tree = provider.build(file, Cancellation.NONE)
            tree.all.forEach { declaration ->
                assertEquals(
                    "range of ${declaration.name} in $fileName",
                    text.substring(declaration.range.start, declaration.range.end),
                    file.text.substring(declaration.range.start, declaration.range.end),
                )
                assertEquals(
                    "PSI text equals request slice for ${declaration.name}",
                    file.text.substring(declaration.range.start, declaration.range.end),
                    text.substring(declaration.range.start, declaration.range.end),
                )
            }
        }
    }
}
