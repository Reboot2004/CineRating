package com.cinerating.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface RapidRtApi {
    @GET("movie")
    suspend fun searchMovie(
        @Query("query") title: String,
        @Header("X-RapidAPI-Key") rapidApiKey: String,
        @Header("X-RapidAPI-Host") rapidApiHost: String = "rottentomatoes-v3.p.rapidapi.com"
    ): Response<RapidRtResponse>
}
