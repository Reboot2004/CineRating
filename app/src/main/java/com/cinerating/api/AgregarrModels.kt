package com.cinerating.api

import com.squareup.moshi.Json

data class AgregarrRating(
    @Json(name = "imdbId") val imdbId: String?,
    @Json(name = "rating") val rating: Double?,
    @Json(name = "votes") val votes: Long?
)
