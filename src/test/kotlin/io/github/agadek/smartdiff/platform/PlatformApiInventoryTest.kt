package io.github.agadek.smartdiff.platform

import io.github.agadek.smartdiff.engine.api.GuardedFeature
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The inventory file and the `checkPlatformApiInventory` logic (platform/01 R6, R8). */
class PlatformApiInventoryTest {

    private val inventory = PlatformApiInventory.parse(File("docs/PLATFORM-API.md").readText())

    // platform/01-platform-api-stability R6
    @Test
    fun `the inventory parses and every row has every column`() {
        assertTrue("rows: $inventory", inventory.size >= 5)
        for (row in inventory) {
            assertTrue("$row has an API", row.apis.isNotEmpty())
            assertTrue("$row has an adapter", row.adapters.isNotEmpty())
            assertTrue("$row has a feature", row.features.isNotEmpty())
            row.features.forEach { feature -> assertTrue("$feature is a GuardedFeature", GuardedFeature.entries.any { it.name == feature }) }
        }
    }

    // platform/01-platform-api-stability R6
    @Test
    fun `the inventory lists the optional plugins' PSI and the Kotlin Analysis API`() {
        val apis = inventory.flatMap { it.apis }
        listOf("org.jetbrains.plugins.scala..", "com.jetbrains.python..", "org.rust..", "com.intellij.lang.javascript..", "com.intellij.lang.ecmascript6..", "com.goide..", "com.jetbrains.php..", "org.jetbrains.kotlin.analysis..", "com.intellij.openapi.vcs.changes.ChangesViewNodeAction")
            .forEach { assertTrue("$it in $apis", it in apis) }
    }

    // platform/01-platform-api-stability R8
    @Test
    fun `report lines parse into uses by plugin classes`() {
        val use = PlatformApiInventory.parseUse(
            "Experimental API method com.jetbrains.python.ast.PyAstReferenceExpression.getNameElement() : com.intellij.lang.ASTNode is invoked in " +
                "io.github.agadek.smartdiff.references.PythonReferenceResolver.index(PsiFile, Set, Cancellation) : Map. This method can be changed",
        )
        assertEquals(
            PlatformApiInventory.Use("Experimental API", "com.jetbrains.python.ast.PyAstReferenceExpression", "getNameElement", "io.github.agadek.smartdiff.references.PythonReferenceResolver"),
            use,
        )
        val overridden = PlatformApiInventory.parseUse(
            "Experimental API method com.intellij.openapi.vcs.changes.ChangesViewNodeAction.handleDoubleClick(com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode node) : boolean is overridden in class io.github.agadek.smartdiff.ui.changes.DeclarationNodeAction. This method",
        )
        assertEquals("com.intellij.openapi.vcs.changes.ChangesViewNodeAction", overridden?.apiClass)
        assertEquals("io.github.agadek.smartdiff.ui.changes.DeclarationNodeAction", overridden?.user)
    }

    // platform/01-platform-api-stability R8
    @Test
    fun `the check fails on an unlisted use and passes on listed ones`() {
        val listed = reports(
            "Experimental API interface com.intellij.openapi.vcs.changes.ChangesViewNodeAction is referenced in io.github.agadek.smartdiff.ui.changes.DeclarationNodeAction. This interface",
            "Experimental API method com.jetbrains.python.ast.PyAstClass.getName() : java.lang.String is invoked in io.github.agadek.smartdiff.engine.declarations.psi.python.PythonDeclarationProvider.describe(PsiElement) : DeclarationInfo. This method",
            // A use inside another plugin's class is not a use by the plugin.
            "Experimental API interface com.jetbrains.python.ast.PyAstTupleExpression is referenced in com.jetbrains.python.psi.PyTupleExpression. This interface",
        )
        assertEquals(emptyList<PlatformApiInventory.Use>(), PlatformApiInventory.unlisted(inventory, PlatformApiInventory.uses(listed)))

        val unlisted = reports(
            "Internal API class com.intellij.openapi.vcs.changes.ui.Hidden is referenced in io.github.agadek.smartdiff.ui.changes.ChangesSummaryModifier.modify(ChangesViewModelBuilder) : void. Internal API",
            "Deprecated method com.jetbrains.python.ast.PyAstClass.getOldName() : java.lang.String is invoked in io.github.agadek.smartdiff.ui.Outline.render() : void",
        )
        val missing = PlatformApiInventory.unlisted(inventory, PlatformApiInventory.uses(unlisted))
        assertEquals(setOf("com.intellij.openapi.vcs.changes.ui.Hidden", "com.jetbrains.python.ast.PyAstClass"), missing.map { it.apiClass }.toSet())
    }

    private fun reports(vararg lines: String): File {
        val dir = Files.createTempDirectory("verifier").toFile().also { it.deleteOnExit() }
        val plugin = File(dir, "IC-251.1/plugins/io.github.agadek.smartdiff/0.1.0").also { it.mkdirs() }
        File(plugin, "verification-verdict.txt").writeText("Compatible.")
        File(plugin, "experimental-api-usages.txt").writeText(lines.joinToString("\n"))
        return dir
    }
}
