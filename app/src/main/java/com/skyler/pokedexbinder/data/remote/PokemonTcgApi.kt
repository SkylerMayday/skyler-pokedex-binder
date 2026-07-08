package com.skyler.pokedexbinder.data.remote

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface PokemonTcgApi {
    @GET("cards")
    suspend fun searchCards(
        @Query("q") query: String,
        @Query("pageSize") pageSize: Int = 250,
        @Query("orderBy") orderBy: String = "-set.releaseDate"
    ): TcgCardsResponse

    @GET("cards/{id}")
    suspend fun getCard(@Path("id") id: String): TcgCardResponse
}
