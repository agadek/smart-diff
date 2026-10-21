package io.github.agadek.smartdiff

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureTest {

    @Test
    fun `plugin classes follow the package dependency rules`() {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.agadek.smartdiff")

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
