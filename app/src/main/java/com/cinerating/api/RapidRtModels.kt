package com.cinerating.api

import com.squareup.moshi.Json

data class RapidRtResponse(
    @Json(name = "results") val results: List<RapidRtItem>?
)

data class RapidRtItem(
    @Json(name = "tomatoScore") val tomatoScore: Int?,
    @Json(name = "criticCount") val criticCount: Int?
)
