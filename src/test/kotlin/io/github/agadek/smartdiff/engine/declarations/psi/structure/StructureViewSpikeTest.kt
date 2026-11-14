package io.github.agadek.smartdiff.engine.declarations.psi.structure

import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.Language
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.DeclarationKind
import io.github.agadek.smartdiff.engine.declarations.psi.DeclarationProvider

/** S9: text-built files give exact names, nesting and ranges from the structure view (languages/06). */
class StructureViewSpikeTest : BasePlatformTestCase() {

    // languages/06-structure-view-fallback R3, R4, R7
    fun `test S9 every bundled structure view gives exact names nesting and ranges`() {
        val provider = fallbackProvider()
        for (case in StructureViewCases.all) {
            val language = Language.findLanguageByID(case.languageId)
            assertNotNull("${case.languageId} is a bundled language", language)
            val file = PsiFileFactory.getInstance(project).createFileFromText(case.fileName, language!!, case.text, false, false)
            assertNotNull("${case.languageId} has a structure view builder", structureViewBuilder(file))
            val tree = provider.build(file, Cancellation.NONE)

            assertEquals("${case.languageId} declarations", case.expected, StructureViewCases.outline(tree))
            assertEquals("${case.languageId} file region", case.fileRegion, tree.fileRegion.ranges)
        }
    }

    // languages/06-structure-view-fallback R3
    fun `test S9 every bundled structure view builds a model without an editor`() {
        for (case in StructureViewCases.all) {
            val language = Language.findLanguageByID(case.languageId)
            assertNotNull("${case.languageId} is a bundled language", language)
            val file = PsiFileFactory.getInstance(project).createFileFromText(case.fileName, language!!, case.text, false, false)
            val builder = structureViewBuilder(file) as? TreeBasedStructureViewBuilder
            assertNotNull("${case.languageId} has a tree-based structure view", builder)
            val model = builder!!.createStructureViewModel(null)
            try {
                assertNotNull("${case.languageId} has a structure view root", model.root)
                assertTrue("${case.languageId} has structure view children", model.root.children.isNotEmpty())
            } finally {
                model.dispose()
            }
        }
    }

    // languages/06-structure-view-fallback R6
    fun `test S9 the recorded kinds are the ones the fallback maps`() {
        val provider = fallbackProvider()
        val kinds = StructureViewCases.all.flatMap { case ->
            val language = Language.findLanguageByID(case.languageId)!!
            val file = PsiFileFactory.getInstance(project).createFileFromText(case.fileName, language, case.text, false, false)
            provider.build(file, Cancellation.NONE).all.map { it.kind }
        }.toSet()

        assertEquals(setOf(DeclarationKind.PROPERTY, DeclarationKind.ITEM, DeclarationKind.CLASS, DeclarationKind.FIELD, DeclarationKind.METHOD), kinds)
    }

    private fun fallbackProvider(): DeclarationProvider =
        DeclarationProvider.EP_NAME.extensionList.single { it is StructureViewDeclarationProvider }

    private fun structureViewBuilder(file: com.intellij.psi.PsiFile): com.intellij.ide.structureView.StructureViewBuilder? {
        var current: Language? = file.language
        while (current != null) {
            val factory = LanguageStructureViewBuilder.INSTANCE.forLanguage(current)
            val builder = factory?.getStructureViewBuilder(file)
            if (builder != null) return builder
            current = current.baseLanguage
        }
        return null
    }
}
