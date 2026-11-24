package io.github.agadek.smartdiff.engine.treesitter

import io.github.agadek.smartdiff.engine.api.LanguageIds
import io.github.agadek.smartdiff.engine.declarations.psi.GoldenFixtures
import java.nio.file.Path
import org.junit.Test

/**
 * Golden fixtures for the Tree-sitter languages (engines/05 R7, R8, R13 to R21, R30), under
 * `src/test/testData/treesitter/<language>/`. Run with `-Psmartdiff.updateGolden=true` to write the expected outlines.
 */
class TreeSitterGoldenTest {
    private val engine = TreeSitterEngine(TreeSitterHelpers { Path.of(System.getProperty(TreeSitterHelpers.HELPER_PROPERTY)) }, enabled = { true })

    @Test
    fun swift() = GoldenFixtures.checkFixtures("treesitter/swift", LanguageIds.TREE_SITTER_SWIFT, "swift", engine = engine)

    @Test
    fun cSharp() = GoldenFixtures.checkFixtures("treesitter/c-sharp", LanguageIds.TREE_SITTER_C_SHARP, "cs", engine = engine)

    @Test
    fun c() = GoldenFixtures.checkFixtures("treesitter/c", LanguageIds.TREE_SITTER_C, "c", engine = engine)

    @Test
    fun cpp() = GoldenFixtures.checkFixtures("treesitter/cpp", LanguageIds.TREE_SITTER_CPP, "cpp", engine = engine)
}
