package io.github.agadek.smartdiff.engine.treesitter

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Spike S12, question 2 (engines/05-tree-sitter-details §2): the file type and language id the IDE gives the files of
 * each Tree-sitter language, which ADR 0015's resolver starts from. Each line is printed as `S12 …` for the build log.
 */
class TreeSitterS12SpikeTest : BasePlatformTestCase() {

    // engines/05-tree-sitter R2, R3; ADR 0015
    fun `test Tree-sitter language files have no language of their own`() {
        val textMate = Language.findLanguageByID("textmate")?.id
        for (name in FILES) {
            val type = FileTypeRegistry.getInstance().getFileTypeByFileName(name)
            val languageId = (type as? LanguageFileType)?.language?.id
            println("S12 $name: file type ${type.name}, language ${languageId ?: "none"}")
            assertTrue(
                "$name should be plain text, TextMate or have no language, not $languageId",
                languageId == null || languageId == PlainTextLanguage.INSTANCE.id || languageId == textMate,
            )
        }
    }

    private companion object {
        val FILES = listOf(
            "App.swift", "Cart.cs", "cart.c", "cart.h",
            "cart.cpp", "cart.cc", "cart.cxx", "cart.c++", "cart.hpp", "cart.hh", "cart.hxx",
        )
    }
}
