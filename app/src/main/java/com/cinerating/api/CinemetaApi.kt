package com.cinerating.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Keyless Stremio Cinemeta API — primary IMDb source until user adds OMDb key.
 * Search returns catalog metas (with tt ids), detail returns meta.imdbRating.
 */
interface CinemetaApi {
    @GET("catalog/movie/top/search={query}.json")
    suspend fun searchMovies(
        @Path(value = "query", encoded = true) query: String
    ): Response<CinemetaCatalogResponse>

    @GET("catalog/series/top/search={query}.json")
    suspend fun searchSeries(
        @Path(value = "query", encoded = true) query: String
    ): Response<CinemetaCatalogResponse>

    @GET("meta/movie/{id}.json")
    suspend fun metaMovie(
        @Path(value = "id", encoded = true) imdbId: String
    ): Response<CinemetaMetaResponse>

    @GET("meta/series/{id}.json")
    suspend fun metaSeries(
        @Path(value = "id", encoded = true) imdbId: String
    ): Response<CinemetaMetaResponse>
}
