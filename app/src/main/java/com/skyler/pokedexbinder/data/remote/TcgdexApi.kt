package com.skyler.pokedexbinder.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

interface TcgdexApi {
    @GET("cards")
    suspend fun searchCards(
        @Query("name") name: String
    ): List<TcgdexCardBriefDto>
}
