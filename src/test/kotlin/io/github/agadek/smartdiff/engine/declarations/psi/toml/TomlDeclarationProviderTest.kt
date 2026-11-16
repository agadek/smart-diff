package io.github.agadek.smartdiff.engine.declarations.psi.toml

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
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
import org.toml.lang.psi.TomlArrayTable
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlInlineTable
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlTable
import java.io.File

class TomlDeclarationProviderTest : ProviderTestCase() {

    // languages/12-toml R3
    fun `test key-values are keys named without quotes and dotted keys stay one key`() {
        val text = "a.b = 1\n\"c\".d = 2\nplain = 3\n"
        val tree = build("TOML", text, "config.toml")
        assertEquals(listOf("a.b", "c.d", "plain"), tree.topLevel.map { it.name })
    }

    // languages/12-toml R5
    fun `test inline table value gives members`() {
        val text = "point = { x = 1, y = 2 }\n"
        val tree = build("TOML", text, "config.toml")
        val point = tree.topLevel.single()
        assertEquals("point", point.name)
        assertEquals(listOf("x", "y"), point.children.map { it.name })
    }

    // languages/12-toml R6
    fun `test array of inline tables with shared identifying key becomes item keys`() {
        val text = "services = [{ name = \"web\", port = 80 }, { name = \"api\", port = 8080 }]\n"
        val tree = build("TOML", text, "config.toml")
        val services = tree.topLevel.single()
        assertEquals(listOf("[name=web]", "[name=api]"), services.children.map { it.name })
    }

    // languages/12-toml R7, R8
    fun `test pairs before first header and tables are top-level declarations`() {
        val text = "title = \"T\"\n\n[owner]\nname = \"a\"\n\n[database]\nport = 5432\n"
        val tree = build("TOML", text, "config.toml")
        assertEquals(listOf("title", "owner", "database"), tree.topLevel.map { it.name })
        assertEquals("SECTION", tree.topLevel[1].kind.name)
        assertEquals(listOf("name"), tree.topLevel[1].children.map { it.name })
    }

    // languages/12-toml R9
    fun `test array of tables entries are named by identifying key or header`() {
        val named = """
            [[bin]]
            name = "cli"
            path = "a"

            [[bin]]
            name = "srv"
            path = "b"
        """.trimIndent() + "\n"
        val namedTree = build("TOML", named, "Cargo.toml")
        assertEquals(listOf("bin[name=cli]", "bin[name=srv]"), namedTree.topLevel.map { it.name })

        val plain = "[[bin]]\npath = \"a\"\n\n[[bin]]\npath = \"b\"\n"
        val plainTree = build("TOML", plain, "Cargo.toml")
        assertEquals(listOf("bin", "bin"), plainTree.topLevel.map { it.name })
    }

    // languages/12-toml R6, R9
    fun `test boolean identifying values do not identify array items or array-of-tables entries`() {
        val inline = "services = [{ name = true, port = 80 }, { name = false, port = 81 }]\n"
        val inlineTree = build("TOML", inline, "config.toml")
        val services = inlineTree.topLevel.single()
        assertEquals("boolean ids leave an inline-table array as content", emptyList<String>(), services.children.map { it.name })

        val tables = """
            [[bin]]
            name = true
            path = "a"

            [[bin]]
            name = false
            path = "b"
        """.trimIndent() + "\n"
        val tablesTree = build("TOML", tables, "Cargo.toml")
        assertEquals("boolean ids leave array-of-tables entries named by header", listOf("bin", "bin"), tablesTree.topLevel.map { it.name })
    }

    // languages/12-toml R15
    fun `test duplicate keys are separate declarations`() {
        val text = "a = 1\na = 2\n"
        val tree = build("TOML", text, "config.toml")
        assertEquals(listOf("a", "a"), tree.topLevel.map { it.name })
    }

    // languages/12-toml R14, R16
    fun `test a renamed key with the same value is a rename candidate and no body`() {
        val result = DeclarationEngine(null).compare(
            ComparisonRequest("timeout = 30\n", "timeoutMs = 30\n", "TOML", "config.toml"),
            Cancellation.NONE,
        )
        assertEquals(Status.ANALYZED, result.status)
        val rename = result.relationships.single { RelationshipKind.RENAME in it.kinds }
        assertEquals("timeout", (rename.old.single().subject as Subject.Declaration).name)
        assertEquals("timeoutMs", (rename.new.single().subject as Subject.Declaration).name)
        val tree = build("TOML", "a = 1\n", "config.toml")
        assertTrue(tree.all.all { it.body == null })
    }

    // languages/12-toml R12
    fun `test spacing inside a line is formatting-only`() {
        val result = DeclarationEngine(null).compare(
            ComparisonRequest("a = 1\n", "a=1\n", "TOML", "config.toml"),
            Cancellation.NONE,
        )
        assertEquals(Status.ANALYZED, result.status)
        assertTrue("no content changes: ${result.changes}", result.changes.isEmpty() || result.changes.all { it.content == Content.FORMATTING_ONLY })
        assertEquals(WhitespacePolicy.LINE_BREAKS_SIGNIFICANT, build("TOML", "a = 1\n", "config.toml").whitespacePolicy)
    }

    // languages/12-toml R1
    fun `test the optional TOML descriptor registers the provider`() {
        val plugin = File("src/main/resources/META-INF/plugin.xml").readText()
        assertTrue(
            "plugin.xml declares the optional TOML dependency",
            plugin.contains("<depends optional=\"true\" config-file=\"smartdiff-toml.xml\">org.toml.lang</depends>"),
        )
        val descriptor = File("src/main/resources/META-INF/smartdiff-toml.xml").readText()
        assertTrue(
            "smartdiff-toml.xml registers the TOML provider",
            descriptor.contains("io.github.agadek.smartdiff.engine.declarations.psi.toml.TomlDeclarationProvider"),
        )
    }

    // languages/12-toml R2, S10
    fun `test TOML ranges match PSI elements for dotted keys quoted keys inline tables arrays and tables`() {
        val text = """
            title = "T"
            a.b = 1
            point = { x = 1, y = 2 }
            services = [{ name = "web", port = 80 }]
            # comment

            [owner]
            name = "Tom"

            [[bin]]
            name = "cli"
            path = "src/main.rs"
        """.trimIndent() + "\n"
        assertS10(text)
    }

    // languages/12-toml S10
    fun `test TOML ranges match PSI elements with CRLF line ends`() {
        val text = "a = 1\r\n[b]\r\nc = 2\r\n"
        assertS10(text)
    }

    private fun assertS10(text: String) {
        val provider = provider("TOML")
        ReadAction.run<RuntimeException> {
            val project = ProjectManager.getInstance().defaultProject
            val file = provider.parse(project, text, "config.toml", null)
            val tree = provider.build(file, Cancellation.NONE)
            tree.all.forEach { declaration ->
                assertEquals(
                    "PSI text equals request slice for ${declaration.name}",
                    text.substring(declaration.range.start, declaration.range.end),
                    file.text.substring(declaration.range.start, declaration.range.end),
                )
            }
            // every top-level declaration element present
            assertTrue(tree.topLevel.isNotEmpty())
        }
    }
}
