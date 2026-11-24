package io.github.agadek.smartdiff.engine.declarations.psi.go

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.declarations.psi.GoldenFixtures
import java.io.File

class GoGoldenFixturesTest : BasePlatformTestCase() {
    // languages/17-go R13, R14, R17
    fun `test Go fixtures`() {
        GoldenFixtures.checkFixtures(
            directory = "go",
            languageId = "go",
            extension = "go",
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
