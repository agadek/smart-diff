package io.github.agadek.smartdiff.engine.declarations.psi.css

import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CSS plugin PSI row of `docs/PLATFORM-API.md`: only `CssInterop` uses it (platform/01 R7, languages/18-css-details §1). */
class CssArchitectureTest {

    private val adapter = "io.github.agadek.smartdiff.engine.declarations.psi.css.."

    private fun classes() = run {
        val classFile = CssDeclarationProvider::class.java.name.replace('.', '/') + ".class"
        val root = CssDeclarationProvider::class.java.classLoader.getResource(classFile)!!.toURI().toString().removeSuffix(classFile)
        ClassFileImporter()
            .withImportOption { it.asURI().toString().startsWith(root) }
            .importPackages("io.github.agadek.smartdiff")
            .also { assertTrue("the provider is imported from $root", it.contain(CssDeclarationProvider::class.java)) }
    }

    // platform/01-platform-api-stability R7
    @Test
    fun `platform 01 R7 only the CSS provider uses the CSS plugin PSI`() {
        noClasses().that(resideInAPackage("io.github.agadek.smartdiff..").and(resideInAPackage(adapter).negate()))
            .should().dependOnClassesThat().resideInAPackage("com.intellij.psi.css..")
            .allowEmptyShould(true)
            .check(classes())
    }

    // languages/18-css-details §1: every call to the plugin's PSI is in CssInterop
    @Test
    fun `languages 18 only CssInterop touches the CSS plugin`() {
        noClasses().that().resideInAPackage(adapter)
            .and().doNotHaveFullyQualifiedName(CssInterop::class.java.name)
            .and().haveNameNotMatching(Regex.escape(CssInterop::class.java.name) + "\\$.*")
            .should().dependOnClassesThat().resideInAPackage("com.intellij.psi.css..")
            .check(classes())
    }

    // languages/18-css-details §1: SCSS and Less are read by element type name, so their plugins may be absent
    @Test
    fun `languages 18 no class uses the Sass or Less plugins`() {
        noClasses().that().resideInAPackage("io.github.agadek.smartdiff..")
            .should().dependOnClassesThat(resideInAnyPackage("org.jetbrains.plugins.scss..", "org.jetbrains.plugins.sass..", "org.jetbrains.plugins.less.."))
            .check(classes())
    }

    // platform/01-platform-api-stability R7
    @Test
    fun `platform 01 R7 the allowed list equals the inventory's adapter column`() {
        val inventory = File("../docs/PLATFORM-API.md").readLines()
        val row = inventory.single { it.startsWith("| `com.intellij.psi.css..` |") }
        val adapters = Regex("`([^`]+)`").findAll(row.split('|')[3]).map { it.groupValues[1] }.toList()
        assertEquals(listOf(adapter), adapters)
    }
}
