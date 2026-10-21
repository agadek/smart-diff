package io.github.agadek.smartdiff.engine.registry

import io.github.agadek.smartdiff.engine.api.EngineId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selection rules of technical specification §4, without the IntelliJ Platform. */
class EngineSelectionTest {

    private val declarations = FakeEngine("smartdiff.declarations", "JAVA", "kotlin")
    private val lines = FakeEngine("smartdiff.lines", "JAVA", "TEXT")
    private val kotlinOnly = FakeEngine("smartdiff.kotlin", "kotlin")
    private val engines = listOf(declarations, lines, kotlinOnly)

    @Test
    fun `candidates are the supporting engines in extension order`() {
        assertEquals(listOf(declarations, kotlinOnly), EngineSelection.of(engines, "kotlin", null).candidates)
        assertEquals(listOf(declarations, lines), EngineSelection.of(engines, "JAVA", null).candidates)
    }

    @Test
    fun `no candidate means no engine`() {
        val selection = EngineSelection.of(engines, "Python", null)
        assertTrue(selection.candidates.isEmpty())
        assertNull(selection.selected)
        assertFalse(selection.offersChoice)
    }

    @Test
    fun `without a stored default the first candidate is selected`() {
        assertSame(declarations, EngineSelection.of(engines, "JAVA", null).selected)
        assertSame(lines, EngineSelection.of(engines, "TEXT", null).selected)
    }

    @Test
    fun `a stored default that is a candidate is selected`() {
        assertSame(lines, EngineSelection.of(engines, "JAVA", EngineId("smartdiff.lines")).selected)
    }

    @Test
    fun `a stored default that does not support the language is ignored`() {
        assertSame(declarations, EngineSelection.of(engines, "kotlin", EngineId("smartdiff.lines")).selected)
    }

    @Test
    fun `a stored default that is not registered is ignored`() {
        assertSame(declarations, EngineSelection.of(engines, "JAVA", EngineId("smartdiff.removed")).selected)
    }

    @Test
    fun `the dropdown is offered only with two or more candidates`() {
        assertTrue(EngineSelection.of(engines, "JAVA", null).offersChoice)
        assertFalse(EngineSelection.of(engines, "TEXT", null).offersChoice)
    }
}
