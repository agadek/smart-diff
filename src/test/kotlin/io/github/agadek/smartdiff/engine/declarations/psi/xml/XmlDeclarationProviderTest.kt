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

class XmlDeclarationProviderTest : ProviderTestCase() {

    private fun compare(old: String, new: String, fileName: String = "config.xml") =
        DeclarationEngine(null).compare(ComparisonRequest(old, new, "XML", fileName), Cancellation.NONE)

    /** Every declaration change node of [result], nested ones included. */
    private fun nodes(result: ComparisonResult): List<ChangeNode> {
        fun flatten(node: ChangeNode): List<ChangeNode> = listOf(node) + node.children.flatMap(::flatten)
        return result.changes.flatMap(::flatten)
    }

    private fun ChangeNode.name(): String? = (subject as? Subject.Declaration)?.name

    private fun changed(result: ComparisonResult) = nodes(result).filter { it.content == Content.CHANGED || it.presence != Presence.MATCHED }

    // languages/13-xml R1
    fun `test the optional XML descriptor registers the provider`() {
        val plugin = File("src/main/resources/META-INF/plugin.xml").readText()
        assertTrue(
            "plugin.xml declares the optional XML dependency",
            plugin.contains("<depends optional=\"true\" config-file=\"smartdiff-xml.xml\">com.intellij.modules.xml</depends>"),
        )
        val descriptor = File("src/main/resources/META-INF/smartdiff-xml.xml").readText()
        assertTrue(
            "smartdiff-xml.xml registers the XML provider",
            descriptor.contains("io.github.agadek.smartdiff.engine.declarations.psi.xml.XmlDeclarationProvider"),
        )
    }

    // languages/13-xml R2, R20
    fun `test XML is supported and HTML is not`() {
        assertTrue(provider("XML") is XmlDeclarationProvider)
        assertTrue(DeclarationEngine(null).supports("XML"))
        assertFalse(XmlDeclarationProvider().supports("HTML"))
        assertFalse(XmlDeclarationProvider().supports("XHTML"))
    }

    // languages/13-xml R3, R6
    fun `test each tag is a TAG named by its tag name with prefix and child tags are members`() {
        val tree = build("XML", "<b:beans xmlns:b=\"urn:b\">\n  <b:alias/>\n  <description>d</description>\n</b:beans>\n", "a.xml")
        val root = tree.topLevel.single()
        assertEquals("b:beans", root.name)
        assertEquals(DeclarationKind.TAG, root.kind)
        assertEquals(listOf("b:alias", "description"), root.children.map { it.name })
        assertTrue("text-only tags have no members", root.children.all { it.children.isEmpty() })
    }

    // languages/13-xml R4
    fun `test a tag with an id or name attribute is named by it, id first`() {
        val text = "<beans>\n  <bean id=\"dataSource\" name=\"ds\"/>\n  <property name=\"url\"/>\n  <bean id=\"\" name=\"n\"/>\n</beans>\n"
        val tree = build("XML", text, "beans.xml")
        assertEquals(listOf("bean id=dataSource", "property name=url", "bean name=n"), tree.topLevel.single().children.map { it.name })
    }

    // languages/13-xml R5
    fun `test a Maven dependency and plugin are named by groupId and artifactId`() {
        val text = """
            <project>
              <dependencies>
                <dependency><groupId>org.junit</groupId><artifactId>junit</artifactId><version>5</version></dependency>
                <dependency><artifactId>orphan</artifactId></dependency>
              </dependencies>
              <build><plugins><plugin><groupId> g </groupId><artifactId>p</artifactId></plugin></plugins></build>
            </project>
        """.trimIndent() + "\n"
        val tree = build("XML", text, "pom.xml")
        val names = tree.all.map { it.name }
        assertTrue(names.toString(), "dependency org.junit:junit" in names)
        assertTrue("a dependency without groupId keeps its tag name: $names", names.count { it == "dependency" } == 1)
        assertTrue(names.toString(), "plugin g:p" in names)
    }

    // languages/13-xml R5
    fun `test a POM version bump is one changed dependency`() {
        fun pom(version: String) =
            "<project>\n  <dependencies>\n    <dependency>\n      <groupId>g</groupId>\n      <artifactId>a</artifactId>\n" +
                "      <version>$version</version>\n    </dependency>\n  </dependencies>\n</project>\n"
        val result = compare(pom("1.0"), pom("1.1"), "pom.xml")
        assertEquals(Status.ANALYZED, result.status)
        val dependency = nodes(result).single { it.name() == "dependency g:a" }
        assertEquals(Presence.MATCHED, dependency.presence)
        assertFalse("no tag is added or removed", nodes(result).any { it.presence != Presence.MATCHED })
    }

    // languages/13-xml R7, R8
    fun `test attribute changes and reordered attributes change the tag`() {
        val changed = compare("<a x=\"1\" y=\"2\"/>\n", "<a x=\"1\" y=\"3\"/>\n")
        assertTrue(changed(changed).isNotEmpty())
        val reordered = compare("<a x=\"1\" y=\"2\"/>\n", "<a y=\"2\" x=\"1\"/>\n")
        assertTrue("reordered attributes are a change: ${nodes(reordered)}", changed(reordered).isNotEmpty())
    }

    // languages/13-xml R9, R10
    fun `test text compares exactly and layout between tags is formatting-only`() {
        val spacing = compare("<a><b>x y</b></a>\n", "<a><b>x  y</b></a>\n")
        assertTrue("text spacing is a change: ${nodes(spacing)}", changed(spacing).isNotEmpty())
        val cdata = compare("<a><![CDATA[x y]]></a>\n", "<a><![CDATA[x  y]]></a>\n")
        assertTrue("CDATA spacing is a change: ${nodes(cdata)}", changed(cdata).isNotEmpty())
        val layout = compare("<a>\n  <b x=\"1\">t</b>\n</a>\n", "<a>\n      <b   x=\"1\" >t</b>\n</a>\n")
        assertTrue("layout is formatting-only: ${nodes(layout)}", changed(layout).isEmpty())
        val indentedText = compare("<a>\n  text\n</a>\n", "<a>\n      text\n</a>\n")
        assertTrue("indentation around text is formatting-only: ${nodes(indentedText)}", changed(indentedText).isEmpty())
        assertEquals(WhitespacePolicy.INSIGNIFICANT, build("XML", "<a/>\n").whitespacePolicy)
    }

    // languages/13-xml R11
    fun `test reordered child tags are moved`() {
        val result = compare("<a>\n  <b>1</b>\n  <c>2</c>\n</a>\n", "<a>\n  <c>2</c>\n  <b>1</b>\n</a>\n")
        assertTrue(nodes(result).toString(), nodes(result).any { it.moved })
    }

    // languages/13-xml R12
    fun `test repeated unnamed sibling tags are separate declarations`() {
        val tree = build("XML", "<bean>\n  <constructor-arg value=\"1\"/>\n  <constructor-arg value=\"2\"/>\n</bean>\n")
        assertEquals(listOf("constructor-arg", "constructor-arg"), tree.topLevel.single().children.map { it.name })
    }

    // languages/13-xml R13, R16
    fun `test a renamed bean id with the same content is a rename candidate and tags have no body`() {
        val result = compare(
            "<beans>\n  <bean id=\"a\" class=\"x.Long\" scope=\"prototype\"/>\n</beans>\n",
            "<beans>\n  <bean id=\"b\" class=\"x.Long\" scope=\"prototype\"/>\n</beans>\n",
        )
        val rename = result.relationships.single { RelationshipKind.RENAME in it.kinds }
        assertEquals("bean id=a", (rename.old.single().subject as Subject.Declaration).name)
        assertEquals("bean id=b", (rename.new.single().subject as Subject.Declaration).name)
        assertTrue(build("XML", "<a><b/></a>\n").all.all { it.body == null && it.signature == null })
    }

    // languages/13-xml R14, R15
    fun `test comments are changes and the prolog belongs to the file region`() {
        val comment = compare("<a>\n  <!-- x -->\n  <b/>\n</a>\n", "<a>\n  <!-- y -->\n  <b/>\n</a>\n")
        assertTrue(nodes(comment).toString(), changed(comment).isNotEmpty())
        val prolog = compare("<?xml version=\"1.0\"?>\n<a/>\n", "<?xml version=\"1.1\"?>\n<a/>\n")
        assertEquals(Status.ANALYZED, prolog.status)
        assertTrue("the root tag is unchanged: ${nodes(prolog)}", changed(prolog).isEmpty())
        assertEquals("the file region changed", Content.CHANGED, prolog.fileRegion?.content)
    }

    // languages/13-xml R17
    fun `test a file with a parse error inside a tag marks that tag unanalyzed`() {
        val result = compare("<a>\n  <b>1</b>\n  <c>2</c>\n</a>\n", "<a>\n  <b>1</b>\n  <c>2\n</a>\n")
        assertTrue(result.status.toString(), result.status == Status.PARTIAL || result.status == Status.FAILED)
    }

    // languages/13-xml R18
    fun `test an XML file over the declaration limit is not analyzed`() {
        val result = DeclarationEngine(null).compare(
            ComparisonRequest("<a><b/><c/><d/></a>\n", "<a><b/><c/><e/></a>\n", "XML", "a.xml", limits = Limits.DEFAULT.copy(maxDeclarations = 2)),
            Cancellation.NONE,
        )
        assertEquals(Status.TOO_LARGE, result.status)
    }

    // languages/13-xml S10
    fun `test XML ranges match PSI elements for prolog doctype namespaces CDATA comments and CRLF`() {
        assertS10(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE beans SYSTEM "beans.dtd">
            <!-- top -->
            <b:beans xmlns:b="urn:b">
              <?pi data?>
              <b:bean id="a"><![CDATA[x < y]]></b:bean>
              <mixed>text <em>bold</em> tail</mixed>
              <self/>
            </b:beans>
            """.trimIndent() + "\n",
        )
        assertS10("<a>\r\n  <b>1</b>\r\n  <c/>\r\n</a>\r\n")
    }

    private fun assertS10(text: String) {
        val provider = provider("XML")
        ReadAction.run<RuntimeException> {
            val file = provider.parse(ProjectManager.getInstance().defaultProject, text, "a.xml", null)
            val tree = provider.build(file, Cancellation.NONE)
            assertTrue(tree.topLevel.isNotEmpty())
            tree.all.forEach { declaration ->
                assertEquals(
                    "PSI text equals request slice for ${declaration.name}",
                    text.substring(declaration.range.start, declaration.range.end),
                    file.text.substring(declaration.range.start, declaration.range.end),
                )
            }
        }
    }
}
