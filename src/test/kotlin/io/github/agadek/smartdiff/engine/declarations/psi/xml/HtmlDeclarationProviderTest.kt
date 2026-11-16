package io.github.agadek.smartdiff.engine.declarations.psi.xml

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonResult
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.api.Limits
import io.github.agadek.smartdiff.engine.api.Presence
import io.github.agadek.smartdiff.engine.api.RelationshipKind
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.Subject
import io.github.agadek.smartdiff.engine.api.WhitespacePolicy
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.ProviderTestCase
import java.io.File

class HtmlDeclarationProviderTest : ProviderTestCase() {

    private fun compare(old: String, new: String, fileName: String = "page.html", languageId: String = "HTML") =
        DeclarationEngine(null).compare(ComparisonRequest(old, new, languageId, fileName), Cancellation.NONE)

    /** Every declaration change node of [result], nested ones included. */
    private fun nodes(result: ComparisonResult): List<ChangeNode> {
        fun flatten(node: ChangeNode): List<ChangeNode> = listOf(node) + node.children.flatMap(::flatten)
        return result.changes.flatMap(::flatten)
    }

    private fun ChangeNode.name(): String? = (subject as? Subject.Declaration)?.name

    private fun changed(result: ComparisonResult) = nodes(result).filter { it.content == Content.CHANGED || it.presence != Presence.MATCHED }

    // languages/15-html R1, R2, R21
    fun `test HTML and XHTML are supported and XML is not`() {
        val descriptor = File("src/main/resources/META-INF/smartdiff-xml.xml").readText()
        assertTrue("HTML provider registered after the XML provider", descriptor.indexOf("HtmlDeclarationProvider") > descriptor.indexOf("XmlDeclarationProvider"))
        assertTrue(provider("HTML") is HtmlDeclarationProvider)
        assertTrue(provider("XHTML") is HtmlDeclarationProvider)
        assertTrue(DeclarationEngine(null).supports("HTML"))
        assertTrue(DeclarationEngine(null).supports("XHTML"))
        assertFalse(HtmlDeclarationProvider().supports("XML"))
        assertTrue(provider("XML") is XmlDeclarationProvider)
        assertFalse(XmlDeclarationProvider().supports("HTML"))
        assertFalse(XmlDeclarationProvider().supports("XHTML"))
    }

    // languages/15-html R3, R6
    fun `test only identified elements are TAGs and other markup is content`() {
        val text = "<html>\n<body>\n  <div id=pricing>text</div>\n  <div>plain</div>\n</body>\n</html>\n"
        val tree = build("HTML", text, "page.html")
        assertEquals(listOf("body"), tree.topLevel.map { it.name })
        assertEquals(listOf("div id=pricing"), tree.topLevel.single().children.map { it.name })
        val identified = tree.all.single { it.name == "div id=pricing" }
        assertEquals(DeclarationKind.TAG, identified.kind)
        assertTrue(identified.children.isEmpty())
        assertEquals(WhitespacePolicy.INSIGNIFICANT, tree.whitespacePolicy)
    }

    // languages/15-html R4, R5
    fun `test head body template script and style are declarations named by tag name or src`() {
        val text = """
            <!DOCTYPE html>
            <html lang="en">
              <head><title>T</title></head>
              <body>
                <template id="row"><span>x</span></template>
                <script src="/js/app.js"></script>
                <style>.a{color:red}</style>
              </body>
            </html>
        """.trimIndent() + "\n"
        val tree = build("HTML", text, "page.html")
        assertEquals(listOf("head", "body"), tree.topLevel.map { it.name })
        assertEquals(
            listOf("template id=row", "script src=/js/app.js", "style"),
            tree.topLevel[1].children.map { it.name },
        )
    }

    // languages/15-html R5
    fun `test tag names are lower-cased and id attributes match without regard to case`() {
        val text = "<HTML>\n<BODY>\n  <DIV ID=Main CLASS=x>v</DIV>\n  <DIV id=Kept>v</DIV>\n</BODY>\n</HTML>\n"
        val tree = build("HTML", text, "page.html")
        assertEquals(listOf("body"), tree.topLevel.map { it.name })
        assertEquals(listOf("div id=Main", "div id=Kept"), tree.topLevel.single().children.map { it.name })
    }

    // languages/15-html R3, R4
    fun `test unquoted ids valueless ids empty ids and empty src`() {
        val text = """
            <body>
              <div id=plain>v</div>
              <div id>v</div>
              <div id="">v</div>
              <script src>v</script>
              <script src="">v</script>
            </body>
        """.trimIndent() + "\n"
        val tree = build("HTML", text, "page.html")
        assertEquals(listOf("div id=plain", "script", "script"), tree.topLevel.single().children.map { it.name })
    }

    // languages/15-html R7
    fun `test top level and members nest through non-declaration elements`() {
        val text = """
            <html>
              <head><meta charset="utf-8"></head>
              <body>
                <main>
                  <section id=pricing>
                    <div><span id=inner>x</span></div>
                  </section>
                </main>
                <footer>f</footer>
              </body>
            </html>
        """.trimIndent() + "\n"
        val tree = build("HTML", text, "page.html")
        assertEquals(listOf("head", "body"), tree.topLevel.map { it.name })
        val body = tree.topLevel[1]
        assertEquals(listOf("section id=pricing"), body.children.map { it.name })
        assertEquals(listOf("span id=inner"), body.children.single().children.map { it.name })
        assertTrue("the footer is content of body", tree.all.none { it.name == "footer" })
    }

    // languages/15-html R7
    fun `test a fragment without body has its declarations at the top level`() {
        val tree = build("HTML", "<div id=a>x</div>\n<template>y</template>\n<script>z</script>\n", "fragment.html")
        assertEquals(listOf("div id=a", "template", "script"), tree.topLevel.map { it.name })
    }

    // languages/15-html R13
    fun `test repeated unnamed scripts are separate declarations`() {
        val tree = build("HTML", "<body>\n  <script>first</script>\n  <script>second</script>\n</body>\n", "page.html")
        assertEquals(listOf("script", "script"), tree.topLevel.single().children.map { it.name })
        val result = compare(
            "<body>\n  <script>first</script>\n  <script>second</script>\n</body>\n",
            "<body>\n  <script>first</script>\n  <script>changed</script>\n</body>\n",
        )
        assertEquals(Status.ANALYZED, result.status)
        assertTrue(nodes(result).toString(), changed(result).isNotEmpty())
    }

    // languages/15-html R8, R11
    fun `test attribute changes and reordered attributes change the declaration`() {
        val changed = compare("<body class=a>\n<div id=x class=a></div>\n</body>\n", "<body class=a>\n<div id=x class=b></div>\n</body>\n")
        assertTrue(changed(changed).isNotEmpty())
        val reordered = compare("<div id=x class=a data=y>v</div>\n", "<div class=a data=y id=x>v</div>\n")
        assertTrue("reordered attributes are a change: ${nodes(reordered)}", changed(reordered).isNotEmpty())
        val spacing = compare("<div   id=x   class=a >v</div>\n", "<div id=x class=a>v</div>\n")
        assertTrue("attribute spacing is formatting-only: ${nodes(spacing)}", changed(spacing).isEmpty())
    }

    // languages/15-html R9, R10, R11
    fun `test script style pre and textarea content compares exactly while markup layout does not`() {
        val script = compare("<script>var x = 1;</script>\n", "<script>var x  = 1;</script>\n")
        assertTrue("script spacing is a change: ${nodes(script)}", changed(script).isNotEmpty())
        val style = compare("<style>.a{color:red}</style>\n", "<style>.a { color:red }</style>\n")
        assertTrue("style spacing is a change: ${nodes(style)}", changed(style).isNotEmpty())
        val pre = compare("<pre>  x  y  </pre>\n", "<pre>  x   y  </pre>\n")
        assertEquals("pre spacing is a change", Content.CHANGED, pre.fileRegion?.content)
        val textarea = compare("<textarea>  x  y  </textarea>\n", "<textarea>  x   y  </textarea>\n")
        assertEquals("textarea spacing is a change", Content.CHANGED, textarea.fileRegion?.content)
        val layout = compare("<body>\n  <div id=a>v</div>\n</body>\n", "<body>\n      <div    id=a >v</div>\n</body>\n")
        assertTrue("markup layout is formatting-only: ${nodes(layout)}", changed(layout).isEmpty())
    }

    // languages/15-html R9
    fun `test re-indenting a script block is a change`() {
        val reindented = compare(
            "<body>\n  <script>\n    var x = 1;\n  </script>\n</body>\n",
            "<body>\n  <script>\n      var x = 1;\n  </script>\n</body>\n",
        )
        assertTrue("script re-indent is a change: ${nodes(reindented)}", changed(reindented).isNotEmpty())
    }

    // languages/15-html R12
    fun `test reordered declarations are moved`() {
        val result = compare(
            "<body>\n  <div id=a>1</div>\n  <div id=b>2</div>\n</body>\n",
            "<body>\n  <div id=b>2</div>\n  <div id=a>1</div>\n</body>\n",
        )
        assertTrue(nodes(result).toString(), nodes(result).any { it.moved })
    }

    // languages/15-html R14
    fun `test a renamed id with the same content is a rename candidate`() {
        val result = compare(
            "<body>\n<div id=old class=x>v</div>\n</body>\n",
            "<body>\n<div id=new class=x>v</div>\n</body>\n",
        )
        val rename = result.relationships.single { RelationshipKind.RENAME in it.kinds }
        assertEquals("div id=old", (rename.old.single().subject as Subject.Declaration).name)
        assertEquals("div id=new", (rename.new.single().subject as Subject.Declaration).name)
        assertTrue(build("HTML", "<body><div id=x>v</div></body>\n").all.all { it.body == null && it.signature == null })
    }

    // languages/15-html R15, R16
    fun `test comments are changes and markup outside declarations belongs to the file region`() {
        val comment = compare(
            "<body>\n<div id=x>v</div>\n<!-- one -->\n</body>\n",
            "<body>\n<div id=x>v</div>\n<!-- two -->\n</body>\n",
        )
        assertTrue(nodes(comment).toString(), changed(comment).isNotEmpty())
        val lang = compare("<html lang=en>\n<body><div id=x>v</div></body>\n</html>\n", "<html lang=de>\n<body><div id=x>v</div></body>\n</html>\n")
        assertEquals(Status.ANALYZED, lang.status)
        assertTrue("declarations are unchanged: ${nodes(lang)}", changed(lang).isEmpty())
        assertEquals(Content.CHANGED, lang.fileRegion?.content)
    }

    // languages/15-html R17
    fun `test a declaration has no body or signature`() {
        assertTrue(build("HTML", "<body><script>var x=1;</script></body>\n").all.all { it.body == null && it.signature == null })
    }

    // languages/15-html R18
    fun `test a parse error on one side is reported`() {
        val result = compare(
            "<body>\n  <div id=a>1</div>\n  <div id=b>2</div>\n</body>\n",
            "<body>\n  <div id=a>1</div>\n  <div id=b>2\n</body>\n",
        )
        assertTrue(result.status.toString(), result.status == Status.PARTIAL || result.status == Status.FAILED)
    }

    // languages/15-html R19
    fun `test an HTML file over the declaration limit is not analyzed`() {
        val result = DeclarationEngine(null).compare(
            ComparisonRequest(
                "<body><div id=a></div><div id=b></div></body>\n",
                "<body><div id=a></div><div id=c></div></body>\n",
                "HTML", "page.html", limits = Limits.DEFAULT.copy(maxDeclarations = 2),
            ),
            Cancellation.NONE,
        )
        assertEquals(Status.TOO_LARGE, result.status)
    }

    // languages/15-html R2
    fun `test XHTML files parse with the XHTML language`() {
        ReadAction.run<RuntimeException> {
            val file = provider("XHTML").parse(
                ProjectManager.getInstance().defaultProject,
                "<html><body><div id=x>v</div></body></html>\n",
                "page.xhtml",
                null,
            )
            assertEquals("XHTML", file.language.id)
            val tree = build("XHTML", "<html><body><div id=\"x\">v</div></body></html>\n", "page.xhtml")
            assertEquals(listOf("body", "div id=x"), tree.all.map { it.name })
        }
    }
}
