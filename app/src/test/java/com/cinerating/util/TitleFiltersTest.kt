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
    fun railHeaders_rejected() {
        listOf(
            "Live Now", "Top in India", "Top in Movies", "Trending Now"
        ).forEach {
            assertFalse("rail header, not title: $it", TitleFilters.isLikelyMovieTitle(it))
        }
    }

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

    // ---- branded rows: structure + closed promo sets kill these ----

    @Test
    fun promoTiles_rejected() {
        // CTA/promo tiles, not ratable titles ("bigg boss"/"bbs"/"24x7").
        listOf(
            "Enter the Bigg Boss House",
            "BBS10: 24X7 Stream [Deferred]"
        ).forEach {
            assertFalse("promo tile, not title: $it", TitleFilters.isLikelyMovieTitle(it))
        }
    }

    @Test
    fun brandedRows_passFilterByDesign() {
        // "South Side Swag" looks exactly like a title; no static list can
        // enumerate brands. Killed downstream by the match-quality gate
        // (fuzzy FALLBACK rejected) or structural header exclusion.
        // This test pins that contract: filter passes, gate rejects.
        listOf(
            "South Side Swag",
            "Enjoy It on the Big Screen"
        ).forEach {
            assertTrue("passes filter, handled downstream: $it", TitleFilters.isLikelyMovieTitle(it))
        }
    }

    // ---- metadata chrome rejected ----

    @Test
    fun studioAndScoreChrome_rejected() {
        listOf(
            "MARVEL STUDIOS", "Marvel", "6,8", "5.8", "11M", "2.3K", "11m"
        ).forEach {
            assertFalse("should reject: $it", TitleFilters.isLikelyMovieTitle(it))
        }
    }

    @Test
    fun marvelTitles_stillPass() {
        // Guard against over-blocking: the studio STRING dies, real titles live.
        listOf("Ms. Marvel", "Marvel's Avengers: Age Of Ultron", "Loki", "Mirai").forEach {
            assertTrue("should pass: $it", TitleFilters.isLikelyMovieTitle(it))
        }
    }

    @Test
    fun languages_rejected() {
        listOf(
            "Hindi", "English", "Tamil", "Telugu", "Malayalam", "Kannada"
        ).forEach {
            assertFalse("language label, not title: $it", TitleFilters.isLikelyMovieTitle(it))
        }
    }

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
        assertEquals("MAD", TitleFilters.cleanTitle("MAD .."))
        assertEquals("organization", TitleFilters.cleanTitle("organization."))
        assertNull(TitleFilters.cleanTitle(null))
        assertEquals("", TitleFilters.cleanTitle("  "))
    }
}
