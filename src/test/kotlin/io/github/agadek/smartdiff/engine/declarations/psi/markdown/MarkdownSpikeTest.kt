package io.github.agadek.smartdiff.engine.declarations.psi.markdown

import com.intellij.lang.Language
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.intellij.plugins.markdown.lang.MarkdownElementTypes
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFrontMatterHeader
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownHeader

/** S10 (Markdown part): records the Markdown PSI shape on the build target (languages/14-markdown-details §9). */
class MarkdownSpikeTest : BasePlatformTestCase() {

    // languages/14-markdown R1, R2, S10
    fun `test S10 Markdown language id and file type`() {
        assertNotNull(Language.findLanguageByID("Markdown"))
        val file = parse("# Title\n")
        assertTrue(file is MarkdownFile)
        assertEquals("Markdown", file.language.id)
    }

    // languages/14-markdown R3, S10: on 2025.1.3 there are no section wrappers.
    fun `test S10 headings are direct children of the Markdown file`() {
        val text = """
            Intro before the first heading.

            # Top ###
            Text in top.

            ## Child
            Text in child.

            ### Skipped level child

            ## Sibling

            Setext top
            ==========

            Setext sub
            ----------
        """.trimIndent() + "\n"
        val file = parse(text)
        val headers = file.children.filterIsInstance<MarkdownHeader>()
        assertEquals(listOf(1, 2, 3, 2, 1, 2), headers.map { it.level })
        assertTrue("every header is a direct child of the file", headers.all { it.parent === file })
        assertEquals("Setext sub", headers.last().contentElement?.text)
    }

    // languages/14-markdown R4, R5, S10
    fun `test S10 heading names content ranges and levels`() {
        val text = listOf(
            "# Simple",
            "# With **bold** and `code` and [link](url) ",
            "# Closing run ###  ",
            "# Hash#notaclosingrun",
            "#",
            "#    ###",
            "Setext **bold**",
            "=================",
            "Sub",
            "---",
        ).joinToString("\n") + "\n"
        val headers = parse(text).children.filterIsInstance<MarkdownHeader>()
        assertEquals(
            listOf(
                1 to "Simple",
                1 to "With **bold** and `code` and [link](url)",
                1 to "Closing run",
                1 to "Hash#notaclosingrun",
                1 to "",
                1 to "",
                1 to "Setext **bold**",
                2 to "Sub",
            ),
            headers.map { it.level to it.contentElement?.text?.trim().orEmpty() },
        )
    }

    // languages/14-markdown R9, S10
    fun `test S10 headings inside code blocks quotes lists and HTML blocks`() {
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
        """.trimIndent() + "\n"
        val file = parse(text)
        assertEquals(listOf("Real"), file.children.filterIsInstance<MarkdownHeader>().map { it.name })
        assertEquals(
            setOf("Real", "Quoted", "Listed"),
            PsiTreeUtil.collectElementsOfType(file, MarkdownHeader::class.java).map { it.name }.toSet(),
        )
    }

    // languages/14-markdown R11, S10
    fun `test S10 YAML and TOML front matter element type`() {
        for (fence in listOf("---" to "---", "+++" to "+++")) {
            val text = "${fence.first}\ntitle: x\n${fence.second}\n# Header\n"
            val file = parse(text)
            val frontMatter = file.children.single { it.node.elementType == MarkdownElementTypes.FRONT_MATTER_HEADER }
            assertTrue(frontMatter is MarkdownFrontMatterHeader)
            assertEquals(com.intellij.openapi.util.TextRange(0, 16), frontMatter.textRange)
            assertEquals(
                MarkdownElementTypes.FRONT_MATTER_HEADER_CONTENT,
                frontMatter.children.single { it.text == "title: x" }.node.elementType,
            )
            assertEquals("Header", file.children.filterIsInstance<MarkdownHeader>().single().name)
        }
    }

    // languages/14-markdown R6, S10
    fun `test S10 CRLF heading ranges`() {
        val text = "# A\r\nText\r\n## B\r\nMore\r\n"
        val file = parse(text)
        file.children.filterIsInstance<MarkdownHeader>().forEach { header ->
            assertEquals(text.substring(header.textRange.startOffset, header.textRange.endOffset), header.text)
        }
    }

    private fun parse(text: String) =
        PsiFileFactory.getInstance(project).createFileFromText("Spike.md", Language.findLanguageByID("Markdown")!!, text, false, false)
}
