package com.cinerating.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayStateTest {

    @Test
    fun setTitles_dedupsAndCaps() {
        val s = XrayState(maxRows = 3)
        s.setTitles(listOf("A", "B", "A", "C", "D"))
        val snap = s.snapshot()
        assertEquals(3, snap.size)
        assertEquals(listOf("A", "B", "C"), snap.map { it.title })
        assertTrue(snap.all { it.score == null })
    }

    @Test
    fun setScore_updatesInPlace() {
        val s = XrayState()
        s.setTitles(listOf("Loki", "Mirai"))
        assertTrue(s.setScore("Loki", "8.2"))
        assertFalse(s.setScore("Unknown", "9.0"))
        assertEquals("8.2", s.snapshot().first { it.title == "Loki" }.score)
        assertEquals(null, s.snapshot().first { it.title == "Mirai" }.score)
    }

    @Test
    fun remove_dropsRow() {
        val s = XrayState()
        s.setTitles(listOf("A", "B"))
        assertTrue(s.remove("A"))
        assertFalse(s.remove("A"))
        assertEquals(listOf("B"), s.snapshot().map { it.title })
    }

    @Test
    fun ensureRow_addsOnceAndEvictsOldestOverflow() {
        val s = XrayState(maxRows = 2)
        s.setTitles(listOf("A", "B"))
        s.ensureRow("A")
        assertEquals(2, s.snapshot().size)
        s.ensureRow("C")
        assertEquals(listOf("A", "C"), s.snapshot().map { it.title })
    }

    @Test
    fun rebuild_resetsScores() {
        val s = XrayState()
        s.setTitles(listOf("A"))
        s.setScore("A", "8.0")
        s.setTitles(listOf("A", "B"))
        assertEquals(null, s.snapshot().first { it.title == "A" }.score)
    }
}
