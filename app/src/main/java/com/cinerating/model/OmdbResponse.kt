package com.cinerating.model

import com.squareup.moshi.Json

data class OmdbResponse(
    @Json(name = "Title") val title: String?,
    @Json(name = "Year") val year: String?,
    @Json(name = "Rated") val rated: String?,
    @Json(name = "Runtime") val runtime: String?,
    @Json(name = "Genre") val genre: String?,
    @Json(name = "Ratings") val ratings: List<OmdbRating>?,
    @Json(name = "imdbRating") val imdbRating: String?,
    @Json(name = "imdbVotes") val imdbVotes: String?,
    @Json(name = "Response") val response: String?,
    @Json(name = "Error") val error: String?
)

data class OmdbRating(
    @Json(name = "Source") val source: String?,
    @Json(name = "Value") val value: String?
)
