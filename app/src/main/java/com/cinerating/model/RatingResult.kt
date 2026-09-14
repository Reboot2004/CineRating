package com.cinerating.model

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
    val sourceApp: String
)
