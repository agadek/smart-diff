package io.github.agadek.smartdiff.engine.declarations.psi.sql

import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SQL plugin PSI row of `docs/PLATFORM-API.md`: only the SQL provider uses it (platform/01 R7). */
class SqlArchitectureTest {

    private val adapter = "io.github.agadek.smartdiff.engine.declarations.psi.sql.."

    // platform/01-platform-api-stability R7
    @Test
    fun `platform 01 R7 only the SQL provider uses the SQL plugin PSI`() {
        val classFile = SqlDeclarationProvider::class.java.name.replace('.', '/') + ".class"
        val root = SqlDeclarationProvider::class.java.classLoader.getResource(classFile)!!.toURI().toString().removeSuffix(classFile)
        val classes = ClassFileImporter()
            .withImportOption { it.asURI().toString().startsWith(root) }
            .importPackages("io.github.agadek.smartdiff")
        assertTrue("the provider is imported from $root", classes.contain(SqlDeclarationProvider::class.java))
        for (pkg in listOf("com.intellij.sql..", "com.intellij.database..")) {
            noClasses().that(resideInAPackage("io.github.agadek.smartdiff..").and(resideInAPackage(adapter).negate()))
                .should().dependOnClassesThat().resideInAPackage(pkg)
                .allowEmptyShould(true)
                .check(classes)
        }
    }

    // platform/01-platform-api-stability R7
    @Test
    fun `platform 01 R7 the allowed list equals the inventory's adapter column`() {
        val inventory = File("../docs/PLATFORM-API.md").readLines()
        val row = inventory.single { it.startsWith("| `com.intellij.sql..` |") }
        val adapters = Regex("`([^`]+)`").findAll(row.split('|')[3]).map { it.groupValues[1] }.toList()
        assertEquals(listOf(adapter), adapters)
    }
}
