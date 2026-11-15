package io.github.agadek.smartdiff.engine.declarations.psi.rust

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.agadek.smartdiff.engine.declarations.psi.GoldenFixtures
import java.io.File

class RustGoldenFixturesTest : BasePlatformTestCase() {
    // languages/05-rust R13, R14, R17
    fun `test Rust fixtures`() {
        GoldenFixtures.checkFixtures(
            directory = "rust",
            languageId = "Rust",
            extension = "rs",
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
