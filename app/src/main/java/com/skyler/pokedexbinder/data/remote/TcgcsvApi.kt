package com.skyler.pokedexbinder.data.remote

import retrofit2.http.GET
import retrofit2.http.Path

interface TcgcsvApi {
    @GET("tcgplayer/3/groups")
    suspend fun getGroups(): TcgcsvGroupsResponse

    @GET("tcgplayer/3/{groupId}/products")
    suspend fun getProducts(@Path("groupId") groupId: Int): TcgcsvProductsResponse
}
