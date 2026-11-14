package io.github.agadek.smartdiff.engine.declarations.psi.json

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.ProviderTestCase
import java.io.File

class JsonDeclarationProviderTest : ProviderTestCase() {

    // languages/07-json R3
    fun `test object members are keys named without quotes`() {
        val text = """
            {
              "host": "localhost",
              "port": 8080
            }
        """.trimIndent()
        assertTree("""
            key host  ""host": "localhost""
            key port  ""port": 8080"
        """, build("JSON", text, "config.json"), text)
    }

    // languages/07-json R3, R4
    fun `test scalar value is the key content`() {
        val text = """
            {
              "enabled": true,
              "count": 3,
              "name": "a",
              "nothing": null
            }
        """.trimIndent()
        assertTree("""
            key enabled  ""enabled": true"
            key count  ""count": 3"
            key name  ""name": "a""
            key nothing  ""nothing": null"
        """, build("JSON", text, "config.json"), text)
    }

    // languages/07-json R3
    fun `test nested object members are children of the key`() {
        val text = """
            {
              "server": {
                "host": "localhost",
                "port": 8080
              }
            }
        """.trimIndent()
        assertTree("""
            key server  ""server": {"
              key host  ""host": "localhost""
              key port  ""port": 8080"
        """, build("JSON", text, "config.json"), text)
    }

    // languages/07-json R5
    fun `test array of scalars is content of its key`() {
        val text = """
            {
              "ports": [80, 443]
            }
        """.trimIndent()
        assertTree("""
            key ports  ""ports": [80, 443]"
        """, build("JSON", text, "config.json"), text)
    }

    // languages/07-json R5
    fun `test array of objects with shared name key becomes item keys`() {
        val text = """
            {
              "services": [
                { "name": "web", "port": 80 },
                { "name": "api", "port": 8080 }
              ]
            }
        """.trimIndent()
        assertTree("""
            key services  ""services": ["
              key [name=web]  "{ "name": "web", "port": 80 }"
                key name  ""name": "web""
                key port  ""port": 80"
              key [name=api]  "{ "name": "api", "port": 8080 }"
                key name  ""name": "api""
                key port  ""port": 8080"
        """, build("JSON", text, "config.json"), text)
    }

    // languages/07-json R5
    fun `test array of objects with shared id key becomes item keys`() {
        val text = """
            {
              "items": [
                { "id": 1, "value": "a" },
                { "id": 2, "value": "b" }
              ]
            }
        """.trimIndent()
        assertTree("""
            key items  ""items": ["
              key [id=1]  "{ "id": 1, "value": "a" }"
                key id  ""id": 1"
                key value  ""value": "a""
              key [id=2]  "{ "id": 2, "value": "b" }"
                key id  ""id": 2"
                key value  ""value": "b""
        """, build("JSON", text, "config.json"), text)
    }

    // languages/07-json R5
    fun `test array of objects without shared identifying key is content`() {
        val text = """
            {
              "items": [
                { "a": 1 },
                { "b": 2 }
              ]
            }
        """.trimIndent()
        assertTree("""
            key items  ""items": ["
        """, build("JSON", text, "config.json"), text)
    }

    // languages/07-json R6
    fun `test top-level object members are top-level declarations`() {
        val text = """
            {
              "a": 1,
              "b": 2
            }
        """.trimIndent()
        assertTree("""
            key a  ""a": 1"
            key b  ""b": 2"
        """, build("JSON", text, "config.json"), text)
    }

    // languages/07-json R6
    fun `test top-level identified array becomes top-level declarations`() {
        val text = """
            [
              { "name": "web", "port": 80 },
              { "name": "api", "port": 8080 }
            ]
        """.trimIndent()
        assertTree("""
            key [name=web]  "{ "name": "web", "port": 80 }"
              key name  ""name": "web""
              key port  ""port": 80"
            key [name=api]  "{ "name": "api", "port": 8080 }"
              key name  ""name": "api""
              key port  ""port": 8080"
        """, build("JSON", text, "config.json"), text)
    }

    // languages/07-json R10
    fun `test duplicate keys are separate declarations`() {
        val text = """
            {
              "a": 1,
              "a": 2
            }
        """.trimIndent()
        assertTree("""
            key a  ""a": 1"
            key a  ""a": 2"
        """, build("JSON", text, "config.json"), text)
    }

    // languages/07-json R3
    fun `test escaped key name is decoded`() {
        val text = """
            {
              "a\"b": 1
            }
        """.trimIndent()
        assertTree("""
            key a"b  ""a\"b": 1"
        """, build("JSON", text, "config.json"), text)
    }

    // languages/07-json R11
    fun `test a renamed key with the same value is a rename candidate`() {
        val result = DeclarationEngine(null).compare(
            ComparisonRequest("""{ "timeout": 30 }""", """{ "timeoutMs": 30 }""", "JSON", "config.json"),
            Cancellation.NONE,
        )

        assertEquals(Status.ANALYZED, result.status)
        val rename = result.relationships.single { RelationshipKind.RENAME in it.kinds }
        assertEquals("timeout", (rename.old.single().subject as Subject.Declaration).name)
        assertEquals("timeoutMs", (rename.new.single().subject as Subject.Declaration).name)
        assertEquals("timeoutMs", (result.changes.single().subject as Subject.Declaration).name)
    }

    // languages/07-json R12
    fun `test keys have no body and are never extracted or inlined`() {
        val oldText = """{ "removed": "shared value text", "kept": 1 }"""
        val newText = """{ "kept": "shared value text" }"""
        val tree = build("JSON", oldText, "config.json")
        assertTrue(tree.all.all { it.body == null })

        val result = DeclarationEngine(null).compare(ComparisonRequest(oldText, newText, "JSON", "config.json"), Cancellation.NONE)
        assertTrue(
            "no extraction or inlining: ${result.relationships}",
            result.relationships.none { RelationshipKind.EXTRACT in it.kinds || RelationshipKind.INLINE in it.kinds },
        )
    }

    // languages/07-json R1
    fun `test the optional JSON descriptor registers the provider`() {
        val plugin = File("src/main/resources/META-INF/plugin.xml").readText()
        assertTrue(
            "plugin.xml declares the optional JSON dependency",
            plugin.contains("<depends optional=\"true\" config-file=\"smartdiff-json.xml\">com.intellij.modules.json</depends>"),
        )
        val descriptor = File("src/main/resources/META-INF/smartdiff-json.xml").readText()
        assertTrue(
            "smartdiff-json.xml registers the JSON provider",
            descriptor.contains("io.github.agadek.smartdiff.engine.declarations.psi.json.JsonDeclarationProvider"),
        )
    }

    // languages/07-json R2, S10
    fun `test JSON key ranges match the PSI elements`() {
        assertS10(
            "JSON",
            "config.json",
            """
                {
                  "host": "localhost",
                  "server": {
                    "port": 8080
                  }
                }
            """.trimIndent(),
            listOf("host", "server", "port"),
        )
    }

    // languages/07-json R2, S10
    fun `test JSON with line and block comments parses and gives correct key ranges`() {
        val text = """
            {
              // line comment
              "host": "localhost",
              /* block comment */
              "server": {
                "port": 8080
              }
            }
        """.trimIndent()
        assertS10("JSON", "config.jsonc", text, listOf("host", "server", "port"))
    }

    // languages/07-json R2, S10
    fun `test JSON5 unquoted, single-quoted and trailing-comma keys give correct ranges`() {
        val text = """
            {
              host: 'localhost',
              services: [
                { name: 'web', port: 80 },
              ],
            }
        """.trimIndent()
        assertS10("JSON5", "config.json5", text, listOf("host", "services", "[name=web]", "name", "port"))
    }

    // languages/07-json S10
    fun `test key ranges match the PSI elements with CRLF line ends`() {
        val text = "{\r\n  \"a\": 1,\r\n  \"b\": {\r\n    \"c\": 2\r\n  }\r\n}\r\n"
        assertS10("JSON", "config.json", text, listOf("a", "b", "c"))
    }

    /**
     * S10: every tree range is exactly the backing PSI element's text, the names and count are the expected ones, and
     * JSON with comments has no PSI errors.
     */
    private fun assertS10(languageId: String, fileName: String, text: String, expectedNames: List<String>) {
        val provider = provider(languageId)
        val (tree, elementTexts, errorCount) = ReadAction.compute(ThrowableComputable<Triple<DeclarationTree, List<String>, Int>, RuntimeException> {
            val project = ProjectManager.getInstance().defaultProject
            val file = provider.parse(project, text, fileName, null)
            val tree = provider.build(file, Cancellation.NONE)
            Triple(tree, psiDeclarations(file).map { it.text }, PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).size)
        })

        assertEquals("declaration names", expectedNames, tree.all.map { it.name })
        assertEquals("a PSI element per declaration", tree.all.size, elementTexts.size)
        tree.all.zip(elementTexts).forEach { (declaration, psiText) ->
            assertEquals("PSI text of ${declaration.name}", psiText, text.substring(declaration.range.start, declaration.range.end))
        }
        assertEquals("PSI errors in $fileName", 0, errorCount)
    }

    /** The PSI elements the provider turns into declarations, in tree order. */
    private fun psiDeclarations(file: PsiFile): List<PsiElement> {
        val collector = object {
            val declarations = mutableListOf<PsiElement>()

            fun value(value: JsonValue?) {
                when (value) {
                    is JsonObject -> value.propertyList.forEach(::property)
                    is JsonArray -> value.valueList.filterIsInstance<JsonObject>().forEach { item ->
                        declarations += item
                        item.propertyList.forEach(::property)
                    }
                    else -> Unit
                }
            }

            fun property(property: JsonProperty) {
                declarations += property
                value(property.value)
            }
        }
        when (val root = (file as JsonFile).topLevelValue) {
            is JsonObject -> root.propertyList.forEach(collector::property)
            is JsonArray -> root.valueList.filterIsInstance<JsonObject>().forEach { item ->
                collector.declarations += item
                item.propertyList.forEach(collector::property)
            }
            else -> Unit
        }
        return collector.declarations
    }
}
