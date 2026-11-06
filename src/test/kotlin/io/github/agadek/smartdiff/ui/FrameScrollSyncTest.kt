package io.github.agadek.smartdiff.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The piecewise mapping that keeps a selected pair of frames aligned (technical specification §10). */
class FrameScrollSyncTest {

    private val master = ScrollFrame(100.0, 200.0)

    @Test
    fun `test outline 01 R25 maps before inside and after frames of the same length`() {
        // outline/01-structural-outline R25
        val mapping = FrameScrollMapping(master, ScrollFrame(300.0, 400.0))

        assertEquals("before the frames the offset is the same", 270.0, mapping.map(70.0), 0.001)
        assertEquals("inside the frames the offset is the same", 350.0, mapping.map(150.0), 0.001)
        assertEquals("after the frames the offset is the same", 450.0, mapping.map(250.0), 0.001)
    }

    @Test
    fun `test outline 01 R25 scales inside frames of different lengths`() {
        // outline/01-structural-outline R25
        val mapping = FrameScrollMapping(master, ScrollFrame(300.0, 500.0))

        assertEquals("a quarter into the master is a quarter into the slave", 350.0, mapping.map(125.0), 0.001)
        assertEquals("halfway into the master is halfway into the slave", 400.0, mapping.map(150.0), 0.001)
        assertEquals("three quarters into the master is three quarters into the slave", 450.0, mapping.map(175.0), 0.001)
        assertEquals("before the frame is not scaled", 290.0, mapping.map(90.0), 0.001)
        assertEquals("after the frame is not scaled", 510.0, mapping.map(210.0), 0.001)
    }

    @Test
    fun `test outline 01 R25 maps in both directions`() {
        // outline/01-structural-outline R25
        val there = FrameScrollMapping(master, ScrollFrame(300.0, 500.0))
        val back = FrameScrollMapping(ScrollFrame(300.0, 500.0), master)

        assertEquals(400.0, there.map(150.0), 0.001)
        assertEquals(150.0, back.map(400.0), 0.001)
    }

    @Test
    fun `test outline 01 R26 a frame is in view only while part of it is visible`() {
        // outline/01-structural-outline R26
        assertFalse("ending at the frame start is not in view", master.intersects(0.0, 100.0))
        assertTrue("the first pixel is in view", master.intersects(99.0, 150.0))
        assertTrue("the last pixel is in view", master.intersects(150.0, 200.5))
        assertFalse("starting at the frame end is not in view", master.intersects(200.0, 300.0))
    }
}
