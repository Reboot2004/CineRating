package com.cinerating.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Dataset-authoritative IMDb ratings fallback — no key, batch-capable.
 * Source: IMDb's official daily title.ratings dumps. Open source, welcomes
 * use in open-source projects: https://github.com/agregarr/imdb-ratings-api
 */
interface AgregarrApi {
    @GET("api/ratings")
    suspend fun ratings(
        @Query("id") ids: List<String>
    ): Response<List<AgregarrRating>>
}
