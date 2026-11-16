package io.github.agadek.smartdiff.engine.declarations.psi.markdown

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.model.Declaration
import io.github.agadek.smartdiff.engine.declarations.model.DeclarationTree
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.ProviderTestCase
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

class MarkdownDeclarationProviderTest : ProviderTestCase() {

    // languages/06-structure-view-fallback R2; languages/14-markdown R1
    fun `test the dedicated provider wins over the structure-view fallback for Markdown`() {
        // The text of the old S9 Markdown case now yields sections, not the fallback's items (languages/14 §7).
        val tree = build("Markdown", "# Title\n\nText\n\n## Section\n\nMore\n", "a.md")
        val title = tree.topLevel.single()
        assertEquals(DeclarationKind.SECTION, title.kind)
        assertEquals("Title", title.name)
        assertEquals(listOf("Section"), title.children.map { it.name })
    }

    // languages/14-markdown R1
    fun `test the optional Markdown descriptor registers the provider`() {
        val plugin = File("src/main/resources/META-INF/plugin.xml").readText()
        assertTrue(
            "plugin.xml declares the optional Markdown dependency",
            plugin.contains("<depends optional=\"true\" config-file=\"smartdiff-markdown.xml\">org.intellij.plugins.markdown</depends>"),
        )
        val descriptor = File("src/main/resources/META-INF/smartdiff-markdown.xml").readText()
        assertTrue(
            "smartdiff-markdown.xml registers the Markdown provider",
            descriptor.contains("io.github.agadek.smartdiff.engine.declarations.psi.markdown.MarkdownDeclarationProvider"),
        )
    }

    // languages/14-markdown R1, R2
    fun `test Markdown files are supported and the plugin loads without the Markdown plugin`() {
        assertTrue(provider("Markdown") is MarkdownDeclarationProvider)
        for (name in listOf("README.md", "README.markdown")) {
            val file = PsiFileFactory.getInstance(project)
                .createFileFromText(name, com.intellij.lang.Language.findLanguageByID("Markdown")!!, "# Title\n", false, false)
            assertEquals("$name uses the Markdown language", "Markdown", file.language.id)
        }
    }

    // languages/14-markdown R3
    fun `test ATX and setext headings are top-level sections`() {
        val text = "# One\n\nTwo\n===\n\n### Three\n"
        val tree = build("Markdown", text, "a.md")
        assertEquals(listOf("One", "Two"), tree.topLevel.map { it.name })
        assertEquals(listOf("Three"), tree.topLevel[1].children.map { it.name })
        assertTrue(tree.all.all { it.kind == DeclarationKind.SECTION })
    }

    // languages/14-markdown R3
    fun `test concurrent builds of different files keep their own sections`() {
        val provider = provider("Markdown")
        val headingCount = 100
        val leftText = (1..headingCount).joinToString("\n\n") { "# Left $it" } + "\n"
        val rightText = (1..headingCount).joinToString("\n\n") { "## Right $it" } + "\n"
        val (leftFile, rightFile) = ReadAction.compute(ThrowableComputable<Pair<PsiFile, PsiFile>, RuntimeException> {
            val project = ProjectManager.getInstance().defaultProject
            provider.parse(project, leftText, "left.md", null) to provider.parse(project, rightText, "right.md", null)
        })

        val futures = (0 until 8).map { thread ->
            ApplicationManager.getApplication().executeOnPooledThread(Callable {
                repeat(120) { iteration ->
                    val useLeft = (thread + iteration) % 2 == 0
                    val file = if (useLeft) leftFile else rightFile
                    val prefix = if (useLeft) "Left" else "Right"
                    val tree = ReadAction.compute(ThrowableComputable<DeclarationTree, RuntimeException> {
                        provider.build(file, Cancellation.NONE)
                    })
                    assertTrue(
                        "the tree of ${file.name} contains its own sections: $tree",
                        tree.topLevel.size == headingCount && tree.topLevel.all { it.name.startsWith(prefix) },
                    )
                }
            })
        }
        futures.forEach { it.get(2, TimeUnit.MINUTES) }
    }

    // languages/14-markdown R4
    fun `test heading text is trimmed without markers or a closing run and keeps inline markup`() {
        val text = "#  **Bold** and `code` and [link](url) ###  \n\nTitle with hash#inside\n---------------------\n"
        val tree = build("Markdown", text, "a.md")
        assertEquals(listOf("**Bold** and `code` and [link](url)"), tree.topLevel.map { it.name })
        assertEquals(listOf("Title with hash#inside"), tree.topLevel.single().children.map { it.name })
    }

    // languages/14-markdown R5
    fun `test a heading with no text is named empty heading`() {
        val text = "#\n\n#    ###\n\n## Named\n"
        val tree = build("Markdown", text, "a.md")
        assertEquals(listOf("(empty heading)", "(empty heading)"), tree.topLevel.map { it.name })
        assertEquals(listOf("Named"), tree.topLevel[1].children.map { it.name })
    }

    // languages/14-markdown R6
    fun `test a section ends before the next heading of the same or a higher level`() {
        val text = "# A\nText\n\n## B\nMore\n\n# C\nEnd\n"
        val tree = build("Markdown", text, "a.md")
        val (a, c) = tree.topLevel
        val b = a.children.single()
        assertEquals(listOf("A", "C"), listOf(a.name, c.name))
        assertEquals("B", b.name)
        assertEquals(listOf(0, 21), listOf(a.range.start, c.range.start))
        assertEquals(a.range.end, b.range.end)
        assertEquals(text.length, c.range.end)
        assertTrue(text.substring(a.range.start, a.range.end).contains("More\n\n"))
    }

    // languages/14-markdown R7
    fun `test lower-level sections are members of the section above them`() {
        val text = "# Top\n\n## Child\n\n### Grandchild\n\n## Sibling\n"
        val tree = build("Markdown", text, "a.md")
        val top = tree.topLevel.single()
        assertEquals(listOf("Child", "Sibling"), top.children.map { it.name })
        assertEquals(listOf("Grandchild"), top.children.first().children.map { it.name })
        assertTrue(top.children.all { it.range.start >= top.range.start && it.range.end <= top.range.end })
    }

    // languages/14-markdown R8
    fun `test a skipped level nests under the nearest heading with fewer hashes`() {
        val text = "# One\n\n### Three\n\n#### Four\n\n## Two\n"
        val tree = build("Markdown", text, "a.md")
        val one = tree.topLevel.single()
        assertEquals(listOf("Three", "Two"), one.children.map { it.name })
        assertEquals(listOf("Four"), one.children.first().children.map { it.name })
    }

    // languages/14-markdown R9
    fun `test headings in code blocks quotes lists and HTML blocks are not sections`() {
        val text = """
            # Real

            ```
            # Fenced
            ```

            > # Quoted

            - # Listed

            <div>
            # In HTML
            </div>

            ## Also real
        """.trimIndent() + "\n"
        val tree = build("Markdown", text, "a.md")
        assertEquals(listOf("Real"), tree.topLevel.map { it.name })
        assertEquals(listOf("Also real"), tree.topLevel.single().children.map { it.name })
        assertEquals(listOf("Real", "Also real"), tree.all.map { it.name })
    }

    // languages/14-markdown R10
    fun `test text before the first heading belongs to the file region`() {
        val text = "Intro before any heading.\n\n# First\n\nText\n"
        val tree = build("Markdown", text, "a.md")
        assertEquals(listOf("First"), tree.topLevel.map { it.name })
        assertTrue("file region has the intro: ${tree.fileRegion.tokens}", tree.fileRegion.tokens.any { "Intro" in it.text })
        assertEquals(0, tree.fileRegion.ranges.first().start)
    }

    // languages/14-markdown R11
    fun `test YAML and TOML front matter is a section with no members`() {
        for (fence in listOf("---" to "---", "+++" to "+++")) {
            val text = "${fence.first}\ntitle: x\n${fence.second}\n\n# Header\n"
            val tree = build("Markdown", text, "a.md")
            val frontMatter = tree.topLevel.first()
            assertEquals("front matter", frontMatter.name)
            assertEquals(DeclarationKind.SECTION, frontMatter.kind)
            assertTrue(frontMatter.children.isEmpty())
            assertEquals(0, frontMatter.range.start)
            assertEquals("${fence.first}\ntitle: x\n${fence.second}", frontMatter.text(text))
            assertEquals(listOf("Header"), tree.topLevel.drop(1).map { it.name })
        }
    }

    // languages/14-markdown R12
    fun `test text in a section outside its members changes the section`() {
        val old = "# A\nOne\n\n## B\nTwo\n"
        val new = "# A\nChanged\n\n## B\nTwo\n"
        val result = DeclarationEngine(null).compare(ComparisonRequest(old, new, "Markdown", "a.md"), Cancellation.NONE)
        assertEquals(Status.ANALYZED, result.status)
        val a = result.changes.single { (it.subject as? Subject.Declaration)?.name == "A" }
        assertTrue("A changed: $result", a.isReported)
        assertEquals(Content.CHANGED, a.content)
    }

    // languages/14-markdown R13, R14
    fun `test a subsection moving to another parent and a level change are moves`() {
        val moved = DeclarationEngine(null).compare(
            ComparisonRequest("# A\n\n## B\nText\n\n## C\n", "# A\n\n## C\n\n### B\nText\n", "Markdown", "a.md"),
            Cancellation.NONE,
        )
        assertTrue("B moved: $moved", moved.relationships.any { RelationshipKind.MOVE in it.kinds })
        val reordered = DeclarationEngine(null).compare(
            ComparisonRequest("# A\n\n## B\n\n## C\n", "# A\n\n## C\n\n## B\n", "Markdown", "a.md"),
            Cancellation.NONE,
        )
        assertTrue("sections reordered: $reordered", reordered.relationships.any { RelationshipKind.MOVE in it.kinds })
    }

    // languages/14-markdown R15
    fun `test a heading reworded over unchanged text is a rename candidate`() {
        val body = "\n\n" + (1..8).joinToString("\n\n") { "Paragraph $it has several words in it." } + "\n"
        val result = DeclarationEngine(null).compare(
            ComparisonRequest("# First$body", "# Renamed$body", "Markdown", "a.md"),
            Cancellation.NONE,
        )
        assertEquals(Status.ANALYZED, result.status)
        val rename = result.relationships.single { RelationshipKind.RENAME in it.kinds }
        assertEquals("First", (rename.old.single().subject as Subject.Declaration).name)
        assertEquals("Renamed", (rename.new.single().subject as Subject.Declaration).name)
    }

    // languages/14-markdown R16
    fun `test duplicate names match inside their container`() {
        val text = "# Example\nA\n\n## Example\nB\n\n# Example\nC\n\n## Example\nD\n"
        val tree = build("Markdown", text, "a.md")
        assertEquals(listOf("Example", "Example"), tree.topLevel.map { it.name })
        assertEquals(listOf("Example"), tree.topLevel[0].children.map { it.name })
        assertEquals(listOf("Example"), tree.topLevel[1].children.map { it.name })
    }

    // languages/14-markdown R17
    fun `test trailing spaces are a change not formatting-only`() {
        val result = DeclarationEngine(null).compare(
            ComparisonRequest("# Title\n\nText\n", "# Title\n\nText  \n", "Markdown", "a.md"),
            Cancellation.NONE,
        )
        assertEquals(Status.ANALYZED, result.status)
        assertTrue("trailing spaces changed: $result", result.changes.any { it.content == Content.CHANGED })
        assertTrue("no formatting-only: $result", result.changes.none { it.content == Content.FORMATTING_ONLY })
        assertEquals(WhitespacePolicy.SIGNIFICANT, build("Markdown", "# Title\n", "a.md").whitespacePolicy)
        assertEquals(WhitespacePolicy.SIGNIFICANT, io.github.agadek.smartdiff.ui.OutlineController.whitespacePolicy("Markdown"))
    }

    // languages/14-markdown R18
    fun `test sections have no body or signature`() {
        val tree = build("Markdown", "# Title\n\nText\n", "a.md")
        assertTrue(tree.all.all { it.body == null && it.signature == null })
    }

    // languages/14-markdown R1, R3, R4, R9, R11, S10
    fun `test S10 request text sliced by every declaration range starts with its heading`() {
        val text = """
            ---
            title: x
            ---

            Intro.

            # Top ###  
            Text.

            ## Child
            Child text.

            ### Skipped
            More.

            ## Sibling
            Even more.

            Setext top
            ==========

            Setext sub
            ----------

            ```
            # In code
            ```

            > # In quote

            - # In list

            <div>
            # In HTML
            </div>
        """.trimIndent() + "\n"
        assertS10(text)
    }

    // languages/14-markdown R6, S10
    fun `test S10 CRLF ranges slice the request text`() {
        assertS10("# A\r\nText\r\n## B\r\nMore\r\n")
    }

    private fun assertS10(text: String) {
        val provider = provider("Markdown")
        ReadAction.run<RuntimeException> {
            val project = ProjectManager.getInstance().defaultProject
            val file = provider.parse(project, text, "S10.md", null)
            val tree = provider.build(file, Cancellation.NONE)
            assertTrue(tree.all.isNotEmpty())
            tree.all.forEach { declaration ->
                assertEquals(
                    "PSI text equals request slice for ${declaration.name}",
                    text.substring(declaration.range.start, declaration.range.end),
                    file.text.substring(declaration.range.start, declaration.range.end),
                )
                val slice = text.substring(declaration.range.start, declaration.range.end)
                val startsWithHeading = when (declaration.name) {
                    "front matter" -> slice.startsWith("---") || slice.startsWith("+++")
                    "(empty heading)" -> slice.startsWith("#")
                    else -> slice.lines().first().contains(declaration.name)
                }
                assertTrue("the range of ${declaration.name} starts with its heading: $slice", startsWithHeading)
            }
            fun checkMembers(declaration: Declaration) {
                declaration.children.forEach { child ->
                    assertTrue("member ${child.name} inside ${declaration.name}", child.range.start >= declaration.range.start && child.range.end <= declaration.range.end)
                    checkMembers(child)
                }
            }
            tree.topLevel.forEach(::checkMembers)
        }
    }

    private fun Declaration.text(text: String): String = text.substring(range.start, range.end)
}
