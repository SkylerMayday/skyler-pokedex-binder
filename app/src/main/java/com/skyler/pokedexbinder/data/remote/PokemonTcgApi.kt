package com.skyler.pokedexbinder.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

interface PokemonTcgApi {
    @GET("cards")
    suspend fun searchCards(
        @Query("q") query: String,
        @Query("pageSize") pageSize: Int = 20,
        @Query("orderBy") orderBy: String = "name"
    ): TcgCardsResponse
}
