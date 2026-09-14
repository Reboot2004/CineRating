package com.cinerating.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Title matrix: Hollywood, Hindi, Telugu, Tamil and dubbed/transliterated
 * titles, verified live against Cinemeta on 2026-09-14 (all 15/15 resolved).
 * Network is NOT hit here — these lock the matching/normalizing/parsing
 * logic using fixtures shaped like the real responses.
 */
class RatingRepositoryTest {

    private val repo = RatingRepository(
        omdbApi = FakeOmdb,
        cinemetaApi = FakeCinemeta,
        agregarrApi = FakeAgregarr,
        omdbApiKey = null
    )

    private fun meta(
        name: String?,
        id: String?,
        imdbId: String? = id,
        releaseInfo: String? = null,
        rating: String? = null
    ) = CinemetaSearchMeta(
        id = id,
        imdbId = imdbId,
        type = "movie",
        name = name,
        releaseInfo = releaseInfo,
        imdbRating = rating
    )

    // ---- normalizeTitle ----

    @Test
    fun normalize_stripsYearInParens() {
        assertEquals("Dangal", repo.normalizeTitle("Dangal (2016)"))
    }

    @Test
    fun normalize_stripsBareYear() {
        assertEquals("Jawan", repo.normalizeTitle("Jawan 2023"))
    }

    @Test
    fun normalize_stripsUiVerbsAndSeparators() {
        assertEquals("RRR", repo.normalizeTitle("Play RRR | • "))
    }

    @Test
    fun normalize_collapsesWhitespace() {
        assertEquals(
            "Ala Vaikunthapurramuloo",
            repo.normalizeTitle("  Ala   Vaikunthapurramuloo  ")
        )
    }

    @Test
    fun normalize_stripsSeasonMarker() {
        assertEquals("Sacred Games", repo.normalizeTitle("Sacred Games Season 1"))
    }

    // ---- pickBestMatch: exact (incl. regional) ----

    @Test
    fun match_hindi_exact() {
        val best = repo.pickBestMatch(
            "Dangal",
            listOf(meta("Dangal 2016", "tt5074352"), meta("Dangal", "tt5074352"))
        )
        assertEquals("tt5074352", best?.imdbId)
        assertEquals("Dangal", best?.name)
    }

    @Test
    fun match_telugu_exact_caseInsensitive() {
        val best = repo.pickBestMatch(
            "rrr",
            listOf(meta("RRR", "tt8178634"), meta("RRR: Behind & Beyond", "tt9999999"))
        )
        assertEquals("tt8178634", best?.imdbId)
    }

    @Test
    fun match_tamil_exact() {
        val best = repo.pickBestMatch(
            "Vikram",
            listOf(meta("Vikram Vedha", "tt6148156"), meta("Vikram", "tt9179430"))
        )
        assertEquals("tt9179430", best?.imdbId)
    }

    // ---- pickBestMatch: prefix (dubbed / extended titles) ----

    @Test
    fun match_dubbed_prefix() {
        // TV shows "Pushpa: The Rise", catalog names it "... - Part 1".
        val best = repo.pickBestMatch(
            "Pushpa: The Rise",
            listOf(
                meta("Pushpa: The Rise - Part 1", "tt9389998"),
                meta("Pushpa 2: The Rule", "tt19868482")
            )
        )
        assertEquals("tt9389998", best?.imdbId)
    }

    @Test
    fun match_baahubali_prefix() {
        val best = repo.pickBestMatch(
            "Baahubali: The Beginning",
            listOf(meta("Baahubali: The Beginning", "tt2631186"))
        )
        assertEquals("tt2631186", best?.imdbId)
    }

    // ---- pickBestMatch: first-result fallback (punctuation variants) ----

    @Test
    fun match_dubbed_punctuationFallback() {
        // TV shows "KGF: Chapter 2", catalog spells "K.G.F: Chapter 2":
        // neither exact nor prefix — first valid result wins.
        val best = repo.pickBestMatch(
            "KGF: Chapter 2",
            listOf(meta("K.G.F: Chapter 2", "tt10698680"))
        )
        assertEquals("tt10698680", best?.imdbId)
    }

    // ---- pickBestMatch: hygiene ----

    @Test
    fun match_skipsNonTtAndNameless() {
        val best = repo.pickBestMatch(
            "Jailer",
            listOf(
                meta(null, "tt11663228"),
                meta("Jailer", "bogus-id"),
                meta("Jailer", "tt11663228")
            )
        )
        assertEquals("tt11663228", best?.imdbId)
    }

    @Test
    fun match_emptyReturnsNull() {
        assertNull(repo.pickBestMatch("Animal", emptyList()))
        assertNull(
            repo.pickBestMatch(
                "Animal",
                listOf(meta(null, null), meta("x", "nope"))
            )
        )
    }

    @Test
    fun match_hollywood_sequelDisambiguation() {
        val best = repo.pickBestMatch(
            "John Wick: Chapter 4",
            listOf(
                meta("John Wick", "tt2911666"),
                meta("John Wick: Chapter 4", "tt10366206")
            )
        )
        assertEquals("tt10366206", best?.imdbId)
    }

    // ---- formatVotes ----

    @Test
    fun votes_formatting() {
        assertEquals("-", repo.formatVotes(null))
        assertEquals("-", repo.formatVotes(0))
        assertEquals("999", repo.formatVotes(999))
        assertEquals("1.5K", repo.formatVotes(1500))
        assertEquals("2.9M", repo.formatVotes(2867837))
    }

    // ---- JSON parsing guards (API drift) ----

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun parse_cinemetaCatalog() {
        val json = """
            {"metas":[
              {"id":"tt1187043","imdb_id":"tt1187043","type":"movie",
               "name":"3 Idiots","releaseInfo":"2009"},
              {"id":"tt23849204","imdb_id":"tt23849204","type":"movie",
               "name":"12th Fail","releaseInfo":"2023","imdbRating":"8.7"}
            ]}
        """.trimIndent()
        val body = moshi.adapter(CinemetaCatalogResponse::class.java).fromJson(json)!!
        assertEquals(2, body.metas?.size)
        assertEquals("3 Idiots", body.metas?.get(0)?.name)
        assertEquals("8.7", body.metas?.get(1)?.imdbRating)
    }

    @Test
    fun parse_cinemetaMeta() {
        val json = """
            {"meta":{"id":"tt8178634","imdb_id":"tt8178634","type":"movie",
             "name":"RRR","year":"2022","runtime":"182 min",
             "genres":["Action","Drama"],"imdbRating":"7.8"}}
        """.trimIndent()
        val body = moshi.adapter(CinemetaMetaResponse::class.java).fromJson(json)!!
        assertEquals("RRR", body.meta?.name)
        assertEquals("7.8", body.meta?.imdbRating)
        assertEquals("182 min", body.meta?.runtime)
    }

    @Test
    fun parse_agregarrBatch() {
        val json = """
            [{"imdbId":"tt1375666","rating":8.8,"votes":2867837},
             {"imdbId":"tt0903747","rating":9.5,"votes":2675096}]
        """.trimIndent()
        val type = Types.newParameterizedType(List::class.java, AgregarrRating::class.java)
        val body = moshi.adapter<List<AgregarrRating>>(type).fromJson(json)!!
        assertEquals(2, body.size)
        assertEquals("tt1375666", body[0].imdbId)
        assertEquals(8.8, body[0].rating!!, 0.001)
        assertEquals(2675096L, body[1].votes)
    }

    // ---- fakes: repository is never constructed with nulls, network never hit ----

    private object FakeOmdb : OmdbApi {
        override suspend fun getByTitle(
            title: String,
            apiKey: String
        ): retrofit2.Response<com.cinerating.model.OmdbResponse> {
            throw UnsupportedOperationException("no network in unit tests")
        }
    }

    private object FakeCinemeta : CinemetaApi {
        override suspend fun searchMovies(query: String) = throw UnsupportedOperationException("no network")
        override suspend fun searchSeries(query: String) = throw UnsupportedOperationException("no network")
        override suspend fun metaMovie(imdbId: String) = throw UnsupportedOperationException("no network")
        override suspend fun metaSeries(imdbId: String) = throw UnsupportedOperationException("no network")
    }

    private object FakeAgregarr : AgregarrApi {
        override suspend fun ratings(ids: List<String>) = throw UnsupportedOperationException("no network")
    }
}
