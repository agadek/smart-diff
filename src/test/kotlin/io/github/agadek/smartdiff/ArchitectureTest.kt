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
        // Import from where the plugin's own classes were loaded: the test classes share its packages, and the
        // instrumented test output is not recognized by ImportOption.Predefined.DO_NOT_INCLUDE_TESTS.
        val mainClasses = ComparisonEngine::class.java.protectionDomain.codeSource.location
        val classes = ClassFileImporter().importUrl(mainClasses)
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
