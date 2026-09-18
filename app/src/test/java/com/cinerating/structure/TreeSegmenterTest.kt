package com.cinerating.structure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic Hotstar-style home screens (1920x1080). Proves structure beats
 * text heuristics: headers/metadata can never become candidates by
 * construction, not by blocklist.
 */
class TreeSegmenterTest {

    private fun box(
        text: String? = null,
        l: Int = 0,
        t: Int = 0,
        r: Int = 100,
        b: Int = 40,
        desc: String? = null,
        id: String? = null,
        clickable: Boolean = false,
        kids: List<UiBox> = emptyList()
    ) = UiBox(text, desc, id, clickable, l, t, r, b, kids)

    private fun card(title: String, l: Int, t: Int) =
        box(title, l, t, l + 240, t + 60, clickable = true)

    /** Hotstar-style home: hero + 2 rails with headers + metadata strip. */
    private fun hotstarHome(): UiBox {
        val meta = box(
            null, 150, 400, 1200, 470, kids = listOf(
                box("2026", 150, 410, 230, 450),
                box("U/A 16+", 240, 410, 360, 450),
                box("Action", 370, 410, 480, 450),
                box("4 Languages", 490, 410, 700, 450)
            )
        )
        val hero = box(
            null, 100, 120, 1500, 600, kids = listOf(
                box("Mirai", 150, 150, 600, 260),
                meta
            )
        )
        val row1 = box(
            null, 0, 620, 1920, 880, kids = listOf(
                box("South Side Swag", 60, 630, 700, 680),
                box(
                    null, 60, 700, 900, 780, kids = listOf(
                        card("Baahubali", 60, 700),
                        card("RRR", 320, 700),
                        card("Vikram", 580, 700)
                    )
                )
            )
        )
        val row2 = box(
            null, 0, 880, 1920, 980, kids = listOf(
                box("Continue Watching for cap", 60, 885, 800, 920),
                box(
                    null, 60, 925, 900, 975, kids = listOf(
                        card("Loki", 60, 925),
                        card("Jawan", 320, 925)
                    )
                )
            )
        )
        return box(null, 0, 0, 1920, 1080, kids = listOf(hero, row1, row2))
    }

    @Test
    fun home_segmentsHeroAndRows() {
        val seg = TreeSegmenter.segment(hotstarHome(), 1920, 1080)
        assertEquals("Mirai", seg.heroTitle?.text)
        assertEquals(2, seg.rows.size)
    }

    @Test
    fun headersNeverBecomeCards() {
        val seg = TreeSegmenter.segment(hotstarHome(), 1920, 1080)
        assertEquals("South Side Swag", seg.rows[0].header?.text)
        assertEquals("Continue Watching for cap", seg.rows[1].header?.text)
        val allCards = seg.rows.flatMap { it.cards }.map { it.text }
        assertTrue(!allCards.any { it == "South Side Swag" })
        assertTrue(!allCards.any { it == "Continue Watching for cap" })
    }

    @Test
    fun cardsAndHeroListedInOrder() {
        val seg = TreeSegmenter.segment(hotstarHome(), 1920, 1080)
        val titles = TreeSegmenter.titles(seg).map { it.text }
        assertEquals(
            listOf("Mirai", "Baahubali", "RRR", "Vikram", "Loki", "Jawan"),
            titles
        )
    }

    @Test
    fun metadataStripIsNotARow() {
        val metaRow = box(
            null, 100, 300, 1800, 420, kids = listOf(
                box("2023", 100, 310, 200, 360),
                box("U/A 13+", 210, 310, 340, 360),
                box("Science Fiction", 350, 310, 600, 360),
                box("4 Languages", 610, 310, 800, 360)
            )
        )
        val seg = TreeSegmenter.segment(
            box(null, 0, 0, 1920, 1080, kids = listOf(metaRow)), 1920, 1080
        )
        assertTrue(seg.rows.isEmpty())
        assertNull(seg.heroTitle)
    }

    @Test
    fun emptyTree_yieldsNothing() {
        val seg = TreeSegmenter.segment(box(null, 0, 0, 1920, 1080), 1920, 1080)
        assertTrue(seg.rows.isEmpty())
        assertNull(seg.heroTitle)
    }

    @Test
    fun profileRouting_hotstarVsDefault() {
        assertTrue(profileFor("in.startv.hotstar").useSegmentation())
        assertTrue(!profileFor("com.netflix.ninja").useSegmentation())
        assertTrue(!profileFor("com.google.android.tvlauncher").useSegmentation())
    }
}
