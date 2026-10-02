package com.skyler.pokedexbinder.data.remote

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TcgdexApi {
    @GET("en/cards")
    suspend fun searchCards(
        @Query("name") name: String
    ): List<TcgdexCardBriefDto>

    /**
     * TCGdex list filters are substring matches: send [officialTotal]/[dexId] as "eq:<n>" and
     * exact-filter [localId] client-side. A null parameter is omitted by Retrofit.
     */
    @GET("{lang}/cards")
    suspend fun filterCards(
        @Path("lang") lang: String,
        @Query("localId") localId: String?,
        @Query("set.cardCount.official") officialTotal: String?,
        @Query("dexId") dexId: String?,
        @Query("name") name: String?
    ): List<TcgdexCardBriefDto>

    @GET("{lang}/cards/{id}")
    suspend fun getCard(
        @Path("lang") lang: String,
        @Path("id") id: String
    ): TcgdexCardDetailDto
}
