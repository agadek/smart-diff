package io.github.agadek.smartdiff.engine.declarations.psi.go

import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Go plugin PSI row of `docs/PLATFORM-API.md`: only the Go provider uses it (platform/01 R7). */
class GoArchitectureTest {

    private val adapter = "io.github.agadek.smartdiff.engine.declarations.psi.go.."

    // platform/01-platform-api-stability R7
    @Test
    fun `platform 01 R7 only the Go provider uses the Go plugin PSI`() {
        val classFile = GoDeclarationProvider::class.java.name.replace('.', '/') + ".class"
        val root = GoDeclarationProvider::class.java.classLoader.getResource(classFile)!!.toURI().toString().removeSuffix(classFile)
        val classes = ClassFileImporter()
            .withImportOption { it.asURI().toString().startsWith(root) }
            .importPackages("io.github.agadek.smartdiff")
        assertTrue("the provider is imported from $root", classes.contain(GoDeclarationProvider::class.java))
        noClasses().that(resideInAPackage("io.github.agadek.smartdiff..").and(resideInAPackage(adapter).negate()))
            .should().dependOnClassesThat().resideInAPackage("com.goide..")
            .allowEmptyShould(true)
            .check(classes)
    }

    // platform/01-platform-api-stability R7
    @Test
    fun `platform 01 R7 the allowed list equals the inventory's adapter column`() {
        val inventory = File("../docs/PLATFORM-API.md").readLines()
        val row = inventory.single { it.startsWith("| `com.goide..` |") }
        val adapters = Regex("`([^`]+)`").findAll(row.split('|')[3]).map { it.groupValues[1] }.toList()
        assertEquals(listOf(adapter), adapters)
    }
}
