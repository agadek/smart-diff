package io.github.agadek.smartdiff.engine.declarations.psi.xml

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationProvider

/** Spike S10 (HTML part): the platform's HTML and XHTML PSI gives ranges that slice the request text exactly. */
class HtmlS10SpikeTest : BasePlatformTestCase() {

    // languages/15-html S10
    fun `test HTML declaration ranges match PSI elements for every case`() {
        assertS10(
            """
            <!DOCTYPE html>
            <HTML lang=en>
              <HEAD><title>T</title></HEAD>
              <BODY ID=Top>
                <br><img src=x>
                <ul>
                  <li>one
                  <li>two
                </ul>
                <p>first
                <p>second
                <div ID=Unquoted class='c'>v</div>
                <div id>valueless</div>
                <!-- comment -->
                <script>var x = 1  < 2;</script>
                <style>.a { color : red }</style>
                <pre>  keep  this  </pre>
                <textarea>  keep  that  </textarea>
              </BODY>
            </HTML>
            """.trimIndent() + "\n",
            "page.html",
            "HTML",
        )
        assertS10("<!DOCTYPE html>\r\n<html>\r\n  <body><div id=x>v</div></body>\r\n</html>\r\n", "page.html", "HTML")
        assertS10(
            """
            <?xml version="1.0"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>T</title></head>
              <body><div id="x">v</div></body>
            </html>
            """.trimIndent() + "\n",
            "page.xhtml",
            "XHTML",
        )
    }

    private fun assertS10(text: String, fileName: String, languageId: String) {
        val provider = DeclarationProvider.EP_NAME.extensionList.single { it.supports(languageId) }
        ReadAction.run<RuntimeException> {
            val project = ProjectManager.getInstance().defaultProject
            val file = provider.parse(project, text, fileName, provider.dialect(project, listOf(text, text), fileName, languageId))
            val tree = provider.build(file, Cancellation.NONE)
            assertTrue("no declarations: $fileName", tree.topLevel.isNotEmpty())
            tree.all.forEach { declaration ->
                val range = declaration.range
                assertEquals(
                    "PSI text equals request slice for ${declaration.name}",
                    text.substring(range.start, range.end),
                    file.text.substring(range.start, range.end),
                )
            }
            PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).forEach { tag ->
                val range = tag.textRange
                assertEquals("tag ${tag.name} slices the request", text.substring(range.startOffset, range.endOffset), tag.text)
            }
        }
    }
}
