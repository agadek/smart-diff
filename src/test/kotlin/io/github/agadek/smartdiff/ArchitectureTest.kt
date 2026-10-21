package io.github.agadek.smartdiff

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import io.github.agadek.smartdiff.engine.api.ComparisonEngine
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
}
