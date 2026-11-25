package io.github.agadek.smartdiff.engine.declarations.psi.sql

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.declarations.psi.GoldenFixtures
import java.io.File

class SqlGoldenFixturesTest : BasePlatformTestCase() {
    // languages/20-sql R10, R11, R14
    fun `test SQL fixtures`() {
        GoldenFixtures.checkFixtures(
            directory = "sql",
            languageId = "SQL",
            extension = "sql",
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
