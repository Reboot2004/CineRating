package com.cinerating.model

/** How well the catalog hit matched the on-screen title. */
enum class MatchQuality {
    EXACT,
    PREFIX,
    FALLBACK
}

data class RatingResult(
    val title: String,
    val year: String,
    val runtime: String,
    val rated: String,
    val genres: List<String>,
    val imdbScore: String,
    val imdbVotes: String,
    val rtScore: String,
    val rtCriticsCount: String,
    val sourceApp: String,
    val matchQuality: MatchQuality = MatchQuality.FALLBACK
)
