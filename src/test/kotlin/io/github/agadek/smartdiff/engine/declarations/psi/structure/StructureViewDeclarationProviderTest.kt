package io.github.agadek.smartdiff.engine.declarations.psi.structure

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.Language
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.AnalysisNote
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ChangeNode
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Content
import io.github.agadek.smartdiff.engine.api.Limits
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationEngine
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationProvider
import io.github.agadek.smartdiff.engine.declarations.psi.java.JavaDeclarationProvider
import io.github.agadek.smartdiff.ui.SmartDiffSettings

/** The structure-view fallback provider (languages/06-structure-view-fallback). */
class StructureViewDeclarationProviderTest : BasePlatformTestCase() {

    private val settings get() = SmartDiffSettings.getInstance()
    private var saved: SmartDiffSettings.SettingsState? = null

    override fun setUp() {
        super.setUp()
        saved = settings.state
        settings.loadState(SmartDiffSettings.SettingsState())
    }

    override fun tearDown() {
        try {
            saved?.let(settings::loadState)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    // languages/06-structure-view-fallback R1, R3, R4, R5
    fun `test R1 R3 R4 R5 every fallback language gives exact names nesting ranges and no signatures`() {
        val provider = fallbackProvider()
        for (case in StructureViewCases.all) {
            val language = Language.findLanguageByID(case.languageId)
            assertNotNull("${case.languageId} is a bundled language", language)
            val file = PsiFileFactory.getInstance(project).createFileFromText(case.fileName, language!!, case.text, false, false)
            val tree = provider.build(file, Cancellation.NONE)

            assertEquals("${case.languageId} declarations", case.expected, StructureViewCases.outline(tree))
            assertEquals("${case.languageId} file region", case.fileRegion, tree.fileRegion.ranges)
            assertTrue("${case.languageId} declares something", tree.topLevel.isNotEmpty())
            tree.all.forEach { declaration ->
                assertNull("no signature: ${declaration.name}", declaration.signature)
                assertTrue("range inside file: ${declaration.range}", declaration.range.start >= 0 && declaration.range.end <= case.text.length)
                assertFalse("no parse error in clean text: ${declaration.name}", declaration.hasParseError)
            }
        }
    }

    // languages/06-structure-view-fallback R2
    fun `test R2 a dedicated provider wins over the fallback`() {
        val fallback = fallbackProvider()
        assertFalse("the fallback never claims Java", fallback.supports("JAVA"))
        val provider = DeclarationProvider.EP_NAME.extensionList.first { it.supports("JAVA") }
        assertTrue("dedicated Java provider is chosen: ${provider.javaClass.name}", provider is JavaDeclarationProvider)
        assertEquals("a dedicated provider has no analysis note", null, provider.analysisNote)

        val result = DeclarationEngine(project).compare(
            ComparisonRequest("class A {}", "class B {}", "JAVA", "A.java"),
            Cancellation.NONE,
        )
        assertEquals(Status.ANALYZED, result.status)
        assertNull("a Java result is not marked as structure view", result.analysisNote)
    }

    // languages/06-structure-view-fallback R2; languages/09-shell R1
    fun `test R2 a shell file goes to the shell provider not the fallback`() {
        val fallback = fallbackProvider()
        assertFalse("the fallback never claims Shell Script", fallback.supports("Shell Script"))
        val provider = DeclarationProvider.EP_NAME.extensionList.first { it.supports("Shell Script") }
        assertTrue("dedicated shell provider is chosen: ${provider.javaClass.name}", provider is io.github.agadek.smartdiff.engine.declarations.psi.shell.ShellDeclarationProvider)
        assertEquals("a dedicated provider has no analysis note", null, provider.analysisNote)

        val result = DeclarationEngine(project).compare(
            ComparisonRequest("foo() { echo hi; }\n", "foo() { echo bye; }\n", "Shell Script", "a.sh"),
            Cancellation.NONE,
        )
        assertEquals(Status.ANALYZED, result.status)
        assertNull("a shell result is not marked as structure view", result.analysisNote)
    }

    // languages/06-structure-view-fallback R1, R10
    fun `test R1 R10 the setting is on by default and turning it off makes fallback languages unsupported`() {
        assertTrue("fallback is on by default", settings.useStructureViewFallback)
        assertTrue(DeclarationEngine(project).supports("JSON"))

        settings.useStructureViewFallback = false

        assertFalse("JSON unsupported when the fallback is off", DeclarationEngine(project).supports("JSON"))
        val result = DeclarationEngine(project).compare(
            ComparisonRequest("{\"a\":1}", "{\"a\":2}", "JSON", "a.json"),
            Cancellation.NONE,
        )
        assertEquals(Status.UNSUPPORTED, result.status)
    }

    // languages/06-structure-view-fallback R12
    fun `test R12 a language without a structure view stays unsupported`() {
        val text = Language.findLanguageByID("TEXT")
        assertNotNull("plain text is a bundled language", text)
        assertNull("plain text has no structure view", structureViewFactory(text!!))
        assertFalse(DeclarationEngine(project).supports("TEXT"))

        val result = DeclarationEngine(project).compare(ComparisonRequest("a", "b", "TEXT"), Cancellation.NONE)
        assertEquals(Status.UNSUPPORTED, result.status)
    }

    // languages/06-structure-view-fallback R7
    fun `test R7 a pure reorder is moved not changed and a reformat is changed`() {
        val engine = DeclarationEngine(project)
        val reordered = engine.compare(
            ComparisonRequest("{\n  \"a\": 1,\n  \"b\": 2\n}\n", "{\n  \"b\": 2,\n  \"a\": 1\n}\n", "JSON", "a.json"),
            Cancellation.NONE,
        )
        val moved = flatten(reordered).filter { it.moved }
        assertTrue("a reorder moves a declaration: ${rendered(reordered)}", moved.isNotEmpty())
        assertTrue("a moved unchanged key is not changed: ${rendered(reordered)}", moved.all { it.content == Content.UNCHANGED })
        assertTrue("a pure reorder is never formatting-only: ${rendered(reordered)}", flatten(reordered).none { it.content == Content.FORMATTING_ONLY })

        val reformatted = engine.compare(
            ComparisonRequest("{\"a\":1,\"b\":2}", "{\n  \"a\": 1,\n  \"b\": 2\n}\n", "JSON", "a.json"),
            Cancellation.NONE,
        )
        assertTrue("a real reformat is changed: ${rendered(reformatted)}", flatten(reformatted).any { it.content == Content.CHANGED })
        assertTrue("a real reformat is never formatting-only: ${rendered(reformatted)}", flatten(reformatted).none { it.content == Content.FORMATTING_ONLY })
    }

    // languages/06-structure-view-fallback R8
    fun `test R8 fallback results carry the structure-view note`() {
        val engine = DeclarationEngine(project)
        val fallback = engine.compare(ComparisonRequest("{\"a\":1}", "{\"a\":2}", "JSON", "a.json"), Cancellation.NONE)

        assertEquals(Status.ANALYZED, fallback.status)
        assertEquals(AnalysisNote.STRUCTURE_VIEW, fallback.analysisNote)

        val java = engine.compare(ComparisonRequest("class A {}", "class B {}", "JAVA", "A.java"), Cancellation.NONE)
        assertNull("a dedicated language has no note", java.analysisNote)
    }

    // languages/06-structure-view-fallback R9
    fun `test R9 a structure view that throws gives FAILED`() {
        val text = Language.findLanguageByID("TEXT")!!
        LanguageStructureViewBuilder.INSTANCE.addExplicitExtension(
            text,
            object : PsiStructureViewFactory {
                override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder =
                    object : TreeBasedStructureViewBuilder() {
                        override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                            throw IllegalStateException("the structure view needs an editor")
                    }
            },
            testRootDisposable,
        )

        val result = DeclarationEngine(project).compare(ComparisonRequest("a", "b", "TEXT", "a.txt"), Cancellation.NONE)

        assertEquals(Status.FAILED, result.status)
        assertTrue("the failure is the structure view throwing", result.engineThrew)
        assertNotNull(result.fallbackReason)
    }

    // languages/06-structure-view-fallback R9
    fun `test R9 a fallback file over the declaration limit is TOO_LARGE`() {
        val request = ComparisonRequest(
            "a=1\nb=2\nc=3\n",
            "a=1\nb=2\nc=4\n",
            "Properties",
            "a.properties",
            limits = Limits.DEFAULT.copy(maxDeclarations = 2),
        )

        val result = DeclarationEngine(project).compare(request, Cancellation.NONE)

        assertEquals(Status.TOO_LARGE, result.status)
        assertNotNull(result.fallbackReason)
    }

    private fun fallbackProvider(): StructureViewDeclarationProvider =
        DeclarationProvider.EP_NAME.extensionList.filterIsInstance<StructureViewDeclarationProvider>().single()

    private fun structureViewFactory(language: Language): PsiStructureViewFactory? {
        var current: Language? = language
        while (current != null) {
            val factory = LanguageStructureViewBuilder.INSTANCE.forLanguage(current)
            if (factory != null) return factory
            current = current.baseLanguage
        }
        return null
    }

    private fun flatten(result: io.github.agadek.smartdiff.engine.api.ComparisonResult): List<ChangeNode> =
        result.changes.flatMap { flatten(it) } + listOfNotNull(result.fileRegion)

    private fun flatten(node: ChangeNode): List<ChangeNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun rendered(result: io.github.agadek.smartdiff.engine.api.ComparisonResult): String =
        io.github.agadek.smartdiff.engine.declarations.psi.Rendering.result(
            result,
            "{\n  \"a\": 1,\n  \"b\": 2\n}\n",
            "{\n  \"b\": 2,\n  \"a\": 1\n}\n",
        )
}
