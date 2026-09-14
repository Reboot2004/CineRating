package com.cinerating.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val OMDB_BASE_URL = "https://www.omdbapi.com/"
    private const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io/"
    private const val RAPID_RT_BASE_URL = "https://rottentomatoes-v3.p.rapidapi.com/"

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val commonClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            // Never log URLs in release — OMDb key is passed as query param.
            level = HttpLoggingInterceptor.Level.NONE
        })
        .build()

    val cinemetaApi: CinemetaApi by lazy {
        Retrofit.Builder()
            .baseUrl(CINEMETA_BASE_URL)
            .client(commonClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CinemetaApi::class.java)
    }

    val omdbApi: OmdbApi by lazy {
        Retrofit.Builder()
            .baseUrl(OMDB_BASE_URL)
            .client(commonClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OmdbApi::class.java)
    }

    val rapidRtApi: RapidRtApi by lazy {
        Retrofit.Builder()
            .baseUrl(RAPID_RT_BASE_URL)
            .client(commonClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(RapidRtApi::class.java)
    }
}
