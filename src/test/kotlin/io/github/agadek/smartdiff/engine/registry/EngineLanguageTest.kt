package io.github.agadek.smartdiff.engine.registry

import io.github.agadek.smartdiff.engine.api.LanguageIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0015: the language an engine is selected by, for files without language support. */
class EngineLanguageTest {

    // engines/05 R2, R3
    @Test
    fun `every Tree-sitter extension resolves from plain text, TextMate and no language`() {
        val expected = mapOf(
            "App.swift" to LanguageIds.TREE_SITTER_SWIFT,
            "Cart.cs" to LanguageIds.TREE_SITTER_C_SHARP,
            "cart.c" to LanguageIds.TREE_SITTER_C,
        ) + listOf("cpp", "cc", "cxx", "c++", "h", "hpp", "hh", "hxx").associate { "cart.$it" to LanguageIds.TREE_SITTER_CPP }
        for ((name, language) in expected) {
            for (id in listOf(null, "TEXT", "textmate")) assertEquals("$name from $id", language, EngineLanguage.of(id, name))
            assertEquals("case is ignored", language, EngineLanguage.of(null, name.uppercase()))
        }
    }

    // engines/05 R4
    @Test
    fun `a language the IDE supports keeps its id`() {
        assertEquals("JAVA", EngineLanguage.of("JAVA", "Cart.swift"))
        assertEquals("ObjectiveC", EngineLanguage.of("ObjectiveC", "cart.h"))
        assertEquals("TEXT", EngineLanguage.of("TEXT", "notes.txt"))
        assertNull(EngineLanguage.of(null, "README"))
        assertNull(EngineLanguage.of(null, null))
    }

    @Test
    fun `Tree-sitter ids are told apart and named`() {
        assertTrue(EngineLanguage.isTreeSitter(LanguageIds.TREE_SITTER_SWIFT))
        assertFalse(EngineLanguage.isTreeSitter("kotlin"))
        assertEquals("C++", EngineLanguage.displayName(LanguageIds.TREE_SITTER_CPP))
        assertNull(EngineLanguage.displayName("JAVA"))
    }
}
