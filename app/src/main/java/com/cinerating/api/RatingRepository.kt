package com.cinerating.api

import com.cinerating.BuildConfig
import com.cinerating.model.MatchQuality
import com.cinerating.model.RatingResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import java.util.Locale

class RatingRepository(
    private val omdbApi: OmdbApi = ApiClient.omdbApi,
    private val cinemetaApi: CinemetaApi = ApiClient.cinemetaApi,
    private val agregarrApi: AgregarrApi = ApiClient.agregarrApi,
    private val omdbApiKey: String? = BuildConfig.OMDB_API_KEY.ifBlank { null }
) {

    // Bounded LRU (150 entries) — avoids unbounded HashMap growth on long TV sessions.
    private val cache = object : LinkedHashMap<String, RatingResult>(150, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RatingResult>?): Boolean {
            return size > 150
        }
    }

    suspend fun getRatings(title: String, sourceApp: String): RatingResult? {
        val normalized = normalizeTitle(title)
        if (normalized.length < 3) return null

        synchronized(cache) {
            cache[normalized]?.let { cached ->
                return cached.copy(sourceApp = sourceApp)
            }
        }

        // Primary: Cinemeta (keyless, HTTPS, IMDb rating). RT is optional per requirements.
        runCatching { getFromCinemeta(normalized, sourceApp) }
            .getOrNull()
            ?.let { result ->
                synchronized(cache) { cache[normalized] = result }
                return result
            }

        // Fallback: OMDb only if user added a key later (Settings / BuildConfig).
        val key = omdbApiKey
        if (!key.isNullOrBlank()) {
            runCatching { getFromOmdb(normalized, sourceApp, key) }
                .getOrNull()
                ?.let { result ->
                    synchronized(cache) { cache[normalized] = result }
                    return result
                }
        }

        return null
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    private suspend fun getFromCinemeta(normalized: String, sourceApp: String): RatingResult? =
        coroutineScope {
            val encoded = URLEncoder.encode(normalized, "UTF-8")
            val moviesDeferred = async { runCatching { cinemetaApi.searchMovies(encoded) }.getOrNull() }
            val seriesDeferred = async { runCatching { cinemetaApi.searchSeries(encoded) }.getOrNull() }

            val candidates = mutableListOf<Pair<CinemetaSearchMeta, String>>()
            moviesDeferred.await()?.takeIf { it.isSuccessful }?.body()?.metas
                ?.forEach { candidates.add(it to "movie") }
            seriesDeferred.await()?.takeIf { it.isSuccessful }?.body()?.metas
                ?.forEach { candidates.add(it to "series") }
            if (candidates.isEmpty()) return@coroutineScope null

            val pick = pickBestMatch(normalized, candidates.map { it.first })
                ?: return@coroutineScope null
            val best = pick.first
            val matchQuality = pick.second
            val bestType = candidates.firstOrNull { it.first.id == best.id }?.second ?: "movie"
            val imdbId = (best.imdbId ?: best.id) ?: return@coroutineScope null
            if (!imdbId.startsWith("tt")) return@coroutineScope null

            // Search sometimes already carries a rating — use it to save a round trip.
            val searchRating = best.imdbRating?.takeIf { it.isNotBlank() && !it.equals("N/A", true) }

            val detail: CinemetaDetailMeta? = runCatching {
                val resp = if (bestType == "series") cinemetaApi.metaSeries(imdbId)
                else cinemetaApi.metaMovie(imdbId)
                if (resp.isSuccessful) resp.body()?.meta else null
            }.getOrNull()

            val rating = detail?.imdbRating?.takeIf { it.isNotBlank() && !it.equals("N/A", true) }
                ?: searchRating

            if (rating != null) {
                val displayTitle = detail?.name ?: best.name ?: normalized
                val year = detail?.year ?: detail?.releaseInfo ?: best.releaseInfo.orEmpty()
                val runtime = detail?.runtime.orEmpty()
                val genres = detail?.genres ?: detail?.genre ?: emptyList()

                return@coroutineScope RatingResult(
                    title = displayTitle,
                    year = year,
                    runtime = runtime,
                    rated = "",
                    genres = genres,
                    imdbScore = "${rating.trim()}/10",
                    imdbVotes = "-",
                    rtScore = "N/A",
                    rtCriticsCount = "-",
                    sourceApp = sourceApp,
                    matchQuality = matchQuality
                )
            }

            // Cinemeta knew the title but has no rating (common for new titles) —
            // fall back to the dataset-authoritative agregarr lookup by IMDb ID.
            val agregarr = runCatching {
                agregarrApi.ratings(listOf(imdbId))
                    .takeIf { it.isSuccessful }
                    ?.body()
                    ?.firstOrNull { it.imdbId == imdbId && it.rating != null }
            }.getOrNull() ?: return@coroutineScope null

            RatingResult(
                title = detail?.name ?: best.name ?: normalized,
                year = detail?.year ?: detail?.releaseInfo ?: best.releaseInfo.orEmpty(),
                runtime = detail?.runtime.orEmpty(),
                rated = "",
                genres = detail?.genres ?: detail?.genre ?: emptyList(),
                imdbScore = "${agregarr.rating}/10",
                imdbVotes = formatVotes(agregarr.votes),
                rtScore = "N/A",
                rtCriticsCount = "-",
                sourceApp = sourceApp,
                matchQuality = matchQuality
            )
        }

    // Internal (not private) so unit tests cover the title-matching matrix:
    // Hollywood, Hindi, Telugu, Tamil and dubbed/transliterated titles.
    internal fun pickBestMatch(
        normalized: String,
        candidates: List<CinemetaSearchMeta>
    ): Pair<CinemetaSearchMeta, MatchQuality>? {
        val valid = candidates.filter {
            val id = it.imdbId ?: it.id
            !it.name.isNullOrBlank() && id?.startsWith("tt") == true
        }
        if (valid.isEmpty()) return null
        // Canonical compare: strip punctuation/case so "KGF: Chapter 2" EXACTly
        // matches "K.G.F: Chapter 2" instead of falling to fuzzy FALLBACK.
        val want = canonical(normalized)
        if (want.isEmpty()) return null
        valid.firstOrNull { it.name?.let { n -> canonical(n) == want } == true }
            ?.let { return it to MatchQuality.EXACT }
        valid.firstOrNull { it.name?.let { n -> canonical(n).startsWith(want) } == true }
            ?.let { return it to MatchQuality.PREFIX }
        return valid.firstOrNull()?.let { it to MatchQuality.FALLBACK }
    }

    private fun canonical(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private suspend fun getFromOmdb(normalized: String, sourceApp: String, key: String): RatingResult? {
        val omdbResponse = omdbApi.getByTitle(normalized, key)
        if (!omdbResponse.isSuccessful) return null

        val omdbBody = omdbResponse.body() ?: return null
        if (!omdbBody.response.equals("True", ignoreCase = true)) return null

        val rtFromOmdb = omdbBody.ratings
            ?.firstOrNull { it.source.equals("Rotten Tomatoes", ignoreCase = true) }
            ?.value
            ?.trim()

        val result = RatingResult(
            title = omdbBody.title.orEmpty(),
            year = omdbBody.year.orEmpty(),
            runtime = omdbBody.runtime.orEmpty(),
            rated = omdbBody.rated.orEmpty(),
            genres = omdbBody.genre
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty(),
            imdbScore = safeScore(omdbBody.imdbRating, "/10"),
            imdbVotes = omdbBody.imdbVotes ?: "-",
            rtScore = rtFromOmdb ?: "N/A",
            rtCriticsCount = "-",
            sourceApp = sourceApp,
            matchQuality = MatchQuality.EXACT
        )
        if (result.imdbScore == "N/A") return null
        return result
    }

    internal fun normalizeTitle(raw: String): String {
        return raw
            // Multi-word markers FIRST ("Season 1" -> ""), before single-word
            // stripping would orphan the number ("Season 1" -> "1").
            .replace(Regex("Season \\d+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Episode \\d+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(play|watch|resume|episode|season)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\b\\d{4}\\b"), "")
            .replace("|", " ")
            .replace("•", " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun safeScore(value: String?, suffix: String): String {
        if (value.isNullOrBlank() || value.equals("N/A", ignoreCase = true)) return "N/A"
        return "$value$suffix"
    }

    internal fun formatVotes(votes: Long?): String {
        if (votes == null || votes <= 0) return "-"
        // Explicit US locale: String.format uses the default locale, and some
        // environments would render "2,9M" instead of "2.9M".
        return when {
            votes >= 1_000_000 -> String.format(Locale.US, "%.1fM", votes / 1_000_000.0)
            votes >= 1_000 -> String.format(Locale.US, "%.1fK", votes / 1_000.0)
            else -> votes.toString()
        }
    }
}
