package io.github.agadek.smartdiff.engine.declarations.psi

import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.api.Cancellation
import io.github.agadek.smartdiff.engine.api.ComparisonRequest
import io.github.agadek.smartdiff.engine.api.Status
import io.github.agadek.smartdiff.engine.api.contract.NothingHidden
import io.github.agadek.smartdiff.engine.declarations.psi.json.JsonDeclarationProvider
import java.io.File

/**
 * Golden fixtures (technical specification §12): `src/test/testData/<language>/<case>/{old,new}.<ext>` and
 * `expected-smartdiff.declarations.txt`, the outline the declaration engine gives for them ([Rendering.result]).
 *
 * Every fixture must also hide nothing (§3.1 rule 6). Run the build with `-Psmartdiff.updateGolden=true` to write the
 * expected files from the current results; review every change to them before committing.
 *
 * relationships/02 R9, R10: these fixture files are the outline the engine showed before the relationship model, so
 * they pass unchanged. A diff there is a change in what the plugin shows.
 */
class GoldenFixturesTest : BasePlatformTestCase() {

    // relationships/02 R9, R10: the expected file is the outline before the relationship model.
    // relationships/03-relationship-matching R17
    fun `test Java fixtures`() = checkFixtures("java", "JAVA", "java")

    // relationships/02 R9, R10: the expected file is the outline before the relationship model.
    fun `test Kotlin fixtures`() = checkFixtures("kotlin", "kotlin", "kt")

    // relationships/02 R9, R10: the expected file is the outline before the relationship model.
    fun `test Scala 2 fixtures`() = checkFixtures("scala2", "Scala", "scala")

    // relationships/02 R9, R10: the expected file is the outline before the relationship model.
    fun `test Scala 3 fixtures`() = checkFixtures("scala3", "Scala", "scala")

    // languages/07-json R14
    fun `test JSON fixtures`() = checkFixtures("json", "JSON", "json")

    // languages/06-structure-view-fallback R1, R3, R4, R6, R7, R11; languages/06 R2 / languages/07
    // JSON now has a dedicated provider, so these fallback fixtures mask it and run through the real engine path.
    fun `test structure-view JSON fixtures`() =
        checkFixtures("structure-view/json", "JSON", "json", maskDedicatedJsonProvider = true)

    // languages/06-structure-view-fallback R1, R3, R4, R6, R7, R11
    fun `test structure-view YAML fixtures`() = checkFixtures("structure-view/yaml", "yaml", "yaml")

    // languages/06-structure-view-fallback R1, R3, R4, R6, R7, R11
    fun `test structure-view Markdown fixtures`() = checkFixtures("structure-view/md", "Markdown", "md")

    // languages/06-structure-view-fallback R1, R3, R4, R6, R7, R11
    fun `test structure-view properties fixtures`() = checkFixtures("structure-view/properties", "Properties", "properties")

    // languages/06-structure-view-fallback R1, R3, R4, R6, R7, R11
    fun `test structure-view Groovy fixtures`() = checkFixtures("structure-view/groovy", "Groovy", "groovy")

    // languages/09-shell R2-R12
    fun `test Shell fixtures`() = checkFixtures("shell", "Shell Script", "sh")

    private fun checkFixtures(
        directory: String,
        languageId: String,
        extension: String,
        maskDedicatedJsonProvider: Boolean = false,
    ) {
        if (maskDedicatedJsonProvider) {
            ExtensionTestUtil.maskExtensions(
                DeclarationProvider.EP_NAME,
                DeclarationProvider.EP_NAME.extensionList.filterNot { it is JsonDeclarationProvider },
                testRootDisposable,
            )
        }
        val cases = File(TEST_DATA, directory)
            .listFiles { file -> file.isDirectory && file.name != "merge" && file.name != "relationships" }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue("no fixtures in ${File(TEST_DATA, directory).absolutePath}", cases.isNotEmpty())
        val engine = DeclarationEngine(project)
        val failures = mutableListOf<String>()
        for (case in cases) {
            val ext = when {
                File(case, "old.jsonc").isFile -> "jsonc"
                File(case, "old.json5").isFile -> "json5"
                else -> extension
            }
            val requestLanguage = when (ext) {
                "json5" -> "JSON5"
                else -> languageId
            }
            val old = read(File(case, "old.$ext"))
            val new = read(File(case, "new.$ext"))
            val fileName = "${case.name}.$ext"
            val result = engine.compare(ComparisonRequest(old, new, requestLanguage, fileName), Cancellation.NONE)
            if (result.status == Status.ANALYZED || result.status == Status.PARTIAL) {
                NothingHidden.violation(old, new, result)?.let { failures += "${case.name}: $it" }
            }
            val actual = Rendering.result(result, old, new)
            val expectedFile = File(case, "expected-${engine.id}.txt")
            val expected = if (expectedFile.isFile) read(expectedFile) else null
            if (actual == expected) continue
            if (UPDATE) {
                expectedFile.writeText(actual)
                println("GOLDEN updated ${expectedFile.path}")
            } else {
                failures += "${case.name}: expected\n${expected ?: "(no ${expectedFile.name})\n"}but was\n$actual"
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} $directory fixtures failed:\n\n" + failures.joinToString("\n"))
    }

    /** Fixture texts are LF-separated like document snapshots, whatever the checkout's line ends. */
    private fun read(file: File): String = file.readText().replace("\r\n", "\n")

    private companion object {
        val TEST_DATA = File("src/test/testData")
        val UPDATE = System.getProperty("smartdiff.updateGolden").toBoolean()
    }
}
