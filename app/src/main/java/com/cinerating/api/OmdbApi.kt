package com.cinerating.api

import com.cinerating.model.OmdbResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface OmdbApi {
    @GET("/")
    suspend fun getByTitle(
        @Query("t") title: String,
        @Query("apikey") apiKey: String
    ): Response<OmdbResponse>
}
