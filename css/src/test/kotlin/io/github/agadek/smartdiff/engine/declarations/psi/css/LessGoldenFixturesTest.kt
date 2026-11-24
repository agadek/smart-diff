package io.github.agadek.smartdiff.engine.declarations.psi.css

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.declarations.psi.GoldenFixtures
import java.io.File

class LessGoldenFixturesTest : BasePlatformTestCase() {
    // languages/18-css R14, R15, R20
    fun `test LESS fixtures`() {
        GoldenFixtures.checkFixtures(
            directory = "less",
            languageId = "LESS",
            extension = "less",
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
