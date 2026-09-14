package com.cinerating.api

import com.squareup.moshi.Json

data class CinemetaCatalogResponse(
    @Json(name = "metas") val metas: List<CinemetaSearchMeta>?
)

data class CinemetaSearchMeta(
    @Json(name = "id") val id: String?,
    @Json(name = "imdb_id") val imdbId: String?,
    @Json(name = "type") val type: String?,
    @Json(name = "name") val name: String?,
    @Json(name = "releaseInfo") val releaseInfo: String?,
    @Json(name = "imdbRating") val imdbRating: String?
)

data class CinemetaMetaResponse(
    @Json(name = "meta") val meta: CinemetaDetailMeta?
)

data class CinemetaDetailMeta(
    @Json(name = "id") val id: String?,
    @Json(name = "imdb_id") val imdbId: String?,
    @Json(name = "type") val type: String?,
    @Json(name = "name") val name: String?,
    @Json(name = "year") val year: String?,
    @Json(name = "releaseInfo") val releaseInfo: String?,
    @Json(name = "runtime") val runtime: String?,
    @Json(name = "genre") val genre: List<String>?,
    @Json(name = "genres") val genres: List<String>?,
    @Json(name = "imdbRating") val imdbRating: String?,
    @Json(name = "director") val director: List<String>?
)
