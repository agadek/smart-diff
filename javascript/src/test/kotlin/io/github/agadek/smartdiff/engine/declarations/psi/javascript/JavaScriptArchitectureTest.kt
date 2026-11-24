package io.github.agadek.smartdiff.engine.declarations.psi.javascript

import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JavaScript plugin PSI row of `docs/PLATFORM-API.md`: only `JavaScriptInterop` uses it (platform/01 R7, details §1). */
class JavaScriptArchitectureTest {

    private val adapter = "io.github.agadek.smartdiff.engine.declarations.psi.javascript.."

    private val pluginPackages = arrayOf("com.intellij.lang.javascript..", "com.intellij.lang.ecmascript6..")

    private fun classes() = run {
        val classFile = JavaScriptDeclarationProvider::class.java.name.replace('.', '/') + ".class"
        val root = JavaScriptDeclarationProvider::class.java.classLoader.getResource(classFile)!!.toURI().toString().removeSuffix(classFile)
        ClassFileImporter()
            .withImportOption { it.asURI().toString().startsWith(root) }
            .importPackages("io.github.agadek.smartdiff")
            .also { assertTrue("the provider is imported from $root", it.contain(JavaScriptDeclarationProvider::class.java)) }
    }

    // platform/01-platform-api-stability R7
    @Test
    fun `platform 01 R7 only the JavaScript provider uses the JavaScript plugin PSI`() {
        noClasses().that(resideInAPackage("io.github.agadek.smartdiff..").and(resideInAPackage(adapter).negate()))
            .should().dependOnClassesThat(resideInAnyPackage(*pluginPackages))
            .allowEmptyShould(true)
            .check(classes())
    }

    // languages/16-javascript-typescript-details §1: every call to the plugin's PSI is in JavaScriptInterop
    @Test
    fun `languages 16 only JavaScriptInterop touches the JavaScript plugin`() {
        noClasses().that().resideInAPackage(adapter)
            .and().doNotHaveFullyQualifiedName(JavaScriptInterop::class.java.name)
            .and().haveNameNotMatching(Regex.escape(JavaScriptInterop::class.java.name) + "\\$.*")
            .should().dependOnClassesThat(resideInAnyPackage(*pluginPackages))
            .check(classes())
    }

    // platform/01-platform-api-stability R7
    @Test
    fun `platform 01 R7 the allowed list equals the inventory's adapter column`() {
        val inventory = File("../docs/PLATFORM-API.md").readLines()
        val row = inventory.single { it.startsWith("| `com.intellij.lang.javascript..`") }
        val adapters = Regex("`([^`]+)`").findAll(row.split('|')[3]).map { it.groupValues[1] }.toList()
        assertEquals(listOf(adapter), adapters)
    }
}
