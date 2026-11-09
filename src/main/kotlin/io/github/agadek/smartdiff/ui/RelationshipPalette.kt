package io.github.agadek.smartdiff.ui

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * The fixed palette of a rename's usage highlights (relationships/06 R12). Each colour has a light and a dark variant,
 * so a usage stays readable on both themes; the seventh rename reuses the first colour.
 */
object RelationshipPalette {

    private val light = listOf(
        Color(0xFF, 0xE0, 0xB2),
        Color(0xC8, 0xE6, 0xC9),
        Color(0xBB, 0xDE, 0xFB),
        Color(0xF8, 0xBB, 0xD0),
        Color(0xD1, 0xC4, 0xE9),
        Color(0xFF, 0xF9, 0xC4),
    )

    private val dark = listOf(
        Color(0x5A, 0x3E, 0x00),
        Color(0x1B, 0x4D, 0x20),
        Color(0x12, 0x3A, 0x5A),
        Color(0x5A, 0x1B, 0x35),
        Color(0x3A, 0x2A, 0x5A),
        Color(0x5A, 0x52, 0x00),
    )

    /** How many colours the palette has before it repeats. */
    const val SIZE = 6

    /** The colour at [index], wrapping around after [SIZE]. */
    fun colour(index: Int): Color = JBColor(light[Math.floorMod(index, SIZE)], dark[Math.floorMod(index, SIZE)])
}
