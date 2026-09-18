package com.cinerating.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleFiltersTest {

    // ---- real titles pass (incl. regional) ----

    @Test
    fun realTitles_pass() {
        listOf(
            "Mirai", "Brahmastra", "Munjya", "Inception", "RRR",
            "Ala Vaikunthapurramuloo", "12th Fail", "K.G.F: Chapter 2",
            "Jawan", "Vikram", "Jailer", "Dangal", "Baahubali"
        ).forEach {
            assertTrue("should pass: $it", TitleFilters.isLikelyMovieTitle(it))
        }
    }

    // ---- row headers rejected ----

    @Test
    fun rowHeaders_rejected() {
        listOf(
            "Continue Watching", "Continue Watching for cap", "New on JioHotstar",
            "Trending Now", "Top 10 Movies Today",
            "Popular on Netflix", "Because You Watched RRR", "My List",
            "New Releases", "Only on Hotstar"
        ).forEach {
            assertFalse("should reject: $it", TitleFilters.isLikelyMovieTitle(it))
        }
    }

    // ---- branded rows pass the filter by design ----
    // "South Side Swag" / "Bigg Boss" promos look exactly like titles;
    // no static list can enumerate brands. These are killed downstream by
    // the match-quality gate (EXACT-or-PREFIX required to badge, so fuzzy
    // FALLBACK hits land in Diagnostics as "low-confidence, skipped").
    // This test pins that contract: filter passes, gate rejects.

    @Test
    fun brandedRows_passFilterByDesign() {
        listOf(
            "South Side Swag",
            "Enter the Bigg Boss House",
            "Enjoy It on the Big Screen"
        ).forEach {
            assertTrue("passes filter, handled downstream: $it", TitleFilters.isLikelyMovieTitle(it))
        }
    }

    // ---- metadata chrome rejected ----

    @Test
    fun metadata_rejected() {
        listOf(
            "2h 46m", "142 min", "U/A 16+", "7 Languages", "2025",
            "NEW RELEASE", "2 Seasons", "S1 E1", "Play", "HD", "4K"
        ).forEach {
            assertFalse("should reject: $it", TitleFilters.isLikelyMovieTitle(it))
        }
    }

    // ---- TV chrome observed badged on-device (must all be rejected) ----
    // From Diagnostics photos: "Connect Phone" 7.0, "IMDb 7.5" 7.8,
    // "Action" 4.8, "Super Heroes"/"Horror" 7.2, "#2 in English Today" 6.4,
    // "• New Episode" 6.6, "Enter the Bigg Boss House" handled by gate.

    @Test
    fun tvChrome_rejected() {
        listOf(
            "Connect Phone",
            "IMDb 7.5",
            "Action",
            "Horror",
            "Super Heroes",
            "Comedy",
            "Thriller",
            "Drama",
            "#2 in English Today",
            "• New Episode",
            "7.8/10",
            "4.8/10"
        ).forEach {
            assertFalse("should reject: $it", TitleFilters.isLikelyMovieTitle(it))
        }
    }

    // ---- cleanTitle ----

    @Test
    fun clean_stripsBullets() {
        assertEquals("New Episode", TitleFilters.cleanTitle("• New Episode"))
        assertEquals("Loki", TitleFilters.cleanTitle("• Loki"))
    }

    @Test
    fun clean_stripsSuffixes() {
        assertEquals("Dangal", TitleFilters.cleanTitle("Dangal (2016)"))
        assertEquals("RRR", TitleFilters.cleanTitle("RRR,Movie"))
        assertNull(TitleFilters.cleanTitle(null))
        assertEquals("", TitleFilters.cleanTitle("  "))
    }
}
