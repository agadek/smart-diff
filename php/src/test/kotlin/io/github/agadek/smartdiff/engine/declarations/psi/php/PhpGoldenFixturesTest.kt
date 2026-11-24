package io.github.agadek.smartdiff.engine.declarations.psi.php

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.declarations.psi.GoldenFixtures
import java.io.File

class PhpGoldenFixturesTest : BasePlatformTestCase() {
    // languages/19-php R10, R11, R14
    fun `test PHP fixtures`() {
        GoldenFixtures.checkFixtures(
            directory = "php",
            languageId = "PHP",
            extension = "php",
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
