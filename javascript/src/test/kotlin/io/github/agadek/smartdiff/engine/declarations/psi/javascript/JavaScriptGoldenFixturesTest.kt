package io.github.agadek.smartdiff.engine.declarations.psi.javascript

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.declarations.psi.GoldenFixtures
import java.io.File

class JavaScriptGoldenFixturesTest : BasePlatformTestCase() {
    // languages/16-javascript-typescript R19; a case's language id follows its file type, as the IDE's does
    fun `test JavaScript fixtures`() {
        check(directory = "javascript", languageId = "JavaScript", extension = "js")
    }

    // languages/16-javascript-typescript R19; `.tsx` cases are in the TypeScript directory
    fun `test TypeScript fixtures`() {
        check(directory = "typescript", languageId = "TypeScript", extension = "ts")
    }

    private fun check(directory: String, languageId: String, extension: String) {
        GoldenFixtures.checkFixtures(
            directory = directory,
            languageId = languageId,
            extension = extension,
            testData = resolveTestData(),
            fail = { message -> fail(message); error(message) },
            assertTrue = { message, condition -> assertTrue(message, condition) },
            println = { message -> println(message) },
        )
    }

    private fun resolveTestData(): File {
        val candidates = listOf(File("src/test/testData"), File("../src/test/testData"))
        return candidates.firstOrNull { it.isDirectory } ?: candidates.first()
    }
}
