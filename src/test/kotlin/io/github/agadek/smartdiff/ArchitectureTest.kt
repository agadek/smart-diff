package io.github.agadek.smartdiff

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
import io.github.agadek.smartdiff.platform.PlatformApiInventory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureTest {

    @Test
    fun `plugin classes follow the package dependency rules`() {
        // Import only from where the plugin's own classes were loaded: the test classes share its packages, and the
        // instrumented test output is not recognized by ImportOption.Predefined.DO_NOT_INCLUDE_TESTS. The IDE class
        // loader gives no code source, so the root is derived from a class file's URL (a directory or a jar entry).
        val classFile = ComparisonEngine::class.java.name.replace('.', '/') + ".class"
        val mainClasses = ComparisonEngine::class.java.classLoader.getResource(classFile)!!.toURI().toString().removeSuffix(classFile)
        val classes = ClassFileImporter()
            .withImportOption { it.asURI().toString().startsWith(mainClasses) }
            .importPackages("io.github.agadek.smartdiff")
        assertTrue("no plugin classes imported from $mainClasses", classes.contain(ComparisonEngine::class.java))
        assertFalse("test classes imported from $mainClasses", classes.contain(ArchitectureTest::class.java))

        ArchitectureRules("io.github.agadek.smartdiff").all.forEach { it.check(classes) }
    }

    @Test
    fun `every rule reports a violation`() {
        val rules = ArchitectureRules("archfixture.violating")
        val classes = fixture("archfixture.violating")

        rules.all.forEach { rule ->
            assertTrue("expected a violation of: ${rule.description}", rule.evaluate(classes).hasViolation())
        }
    }

    @Test
    fun `allowed dependencies pass`() {
        val rules = ArchitectureRules("archfixture.allowed")
        val classes = fixture("archfixture.allowed")

        rules.all.forEach { rule ->
            assertFalse(rule.evaluate(classes).failureReport.toString(), rule.evaluate(classes).hasViolation())
        }
    }

    private fun fixture(packageName: String): JavaClasses = ClassFileImporter().importPackages(packageName)

    private fun mainClasses(): JavaClasses {
        val classFile = ComparisonEngine::class.java.name.replace('.', '/') + ".class"
        val mainClasses = ComparisonEngine::class.java.classLoader.getResource(classFile)!!.toURI().toString().removeSuffix(classFile)
        return ClassFileImporter()
            .withImportOption { it.asURI().toString().startsWith(mainClasses) }
            .importPackages("io.github.agadek.smartdiff")
    }

    private val platformRules = ArchitectureRules("io.github.agadek.smartdiff").platformApi

    private fun checkPlatformRule(api: String) = platformRules.getValue(api).check(mainClasses())

    // platform/01-platform-api-stability R7
    @Test
    fun `platform 01 R7 only the Changes tree adapter uses ChangesViewNodeAction`() =
        checkPlatformRule("com.intellij.openapi.vcs.changes.ChangesViewNodeAction")

    // platform/01-platform-api-stability R7, changes-tree/07 (ADR 0013)
    @Test
    fun `platform 01 R7 only the code review tree adapter uses RefComparisonChange`() =
        checkPlatformRule("com.intellij.collaboration.util.RefComparisonChange")

    // platform/01-platform-api-stability R7
    @Test
    fun `platform 01 R7 only the Python adapters use the experimental Python AST members`() {
        listOf("PyAstClass", "PyAstFunction", "PyAstNamedParameter", "PyAstTargetExpression", "PyAstReferenceExpression")
            .forEach { checkPlatformRule("com.jetbrains.python.ast.$it") }
    }

    // platform/01-platform-api-stability R7
    @Test
    fun `platform 01 R7 only the Markdown provider uses the front matter header`() =
        checkPlatformRule("org.intellij.plugins.markdown.lang.psi.impl.MarkdownFrontMatterHeader")

    // platform/01-platform-api-stability R7
    @Test
    fun `platform 01 R7 only the Scala adapters use the Scala plugin PSI`() = checkPlatformRule("org.jetbrains.plugins.scala..")

    // platform/01-platform-api-stability R7
    @Test
    fun `platform 01 R7 only the Python adapters use the Python plugin PSI`() = checkPlatformRule("com.jetbrains.python..")

    // platform/01-platform-api-stability R7
    @Test
    fun `platform 01 R7 only the Kotlin resolver may use the Kotlin Analysis API`() = checkPlatformRule("org.jetbrains.kotlin.analysis..")

    // platform/01-platform-api-stability R7
    @Test
    fun `platform 01 R7 a use outside the adapter fails the rule`() {
        // The fixture's ui.Outline uses DeclarationEngine, which only the engine itself may use here.
        val classes = fixture("archfixture.violating")
        val api = "archfixture.violating.engine.declarations.DeclarationEngine"
        assertTrue(onlyAdaptersUse("archfixture.violating", api, listOf("archfixture.violating.engine..")).evaluate(classes).hasViolation())
        assertFalse(onlyAdaptersUse("archfixture.violating", api, listOf("archfixture.violating.engine..", "archfixture.violating.ui.Outline")).evaluate(classes).hasViolation())
    }

    // platform/01-platform-api-stability R7
    @Test
    fun `platform 01 R7 the allowed lists equal the inventory's adapter column`() {
        val rows = PlatformApiInventory.parse(File("docs/PLATFORM-API.md").readText())
        val inventory = LinkedHashMap<String, MutableSet<String>>()
        for (row in rows) {
            for (api in row.apis) inventory.getOrPut(api.substringBefore('#')) { linkedSetOf() } += row.adapters
        }
        val rust = inventory.remove("org.rust..")
        assertEquals("the rust subproject checks this row", setOf("io.github.agadek.smartdiff.engine.declarations.psi.rust.."), rust)
        for (api in listOf("com.intellij.lang.javascript..", "com.intellij.lang.ecmascript6..")) {
            val javascript = inventory.remove(api)
            assertEquals("the javascript subproject checks this row", setOf("io.github.agadek.smartdiff.engine.declarations.psi.javascript.."), javascript)
        }
        val go = inventory.remove("com.goide..")
        assertEquals("the go subproject checks this row", setOf("io.github.agadek.smartdiff.engine.declarations.psi.go.."), go)
        val css = inventory.remove("com.intellij.psi.css..")
        assertEquals("the css subproject checks this row", setOf("io.github.agadek.smartdiff.engine.declarations.psi.css.."), css)
        val php = inventory.remove("com.jetbrains.php..")
        assertEquals("the php subproject checks this row", setOf("io.github.agadek.smartdiff.engine.declarations.psi.php.."), php)
        val sql = inventory.remove("com.intellij.sql..")
        assertEquals("the sql subproject checks this row", setOf("io.github.agadek.smartdiff.engine.declarations.psi.sql.."), sql)
        assertEquals(
            inventory.mapValues { it.value.toSet() },
            ArchitectureRules("io.github.agadek.smartdiff").platformApiAdapters.mapValues { it.value.toSet() },
        )
    }
}
