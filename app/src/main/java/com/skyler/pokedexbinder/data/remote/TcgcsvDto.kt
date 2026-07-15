package com.skyler.pokedexbinder.data.remote

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TcgcsvGroupsResponse(
    val results: List<TcgcsvGroupDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TcgcsvGroupDto(
    val groupId: Int,
    val name: String
)

@JsonClass(generateAdapter = true)
data class TcgcsvProductsResponse(
    val results: List<TcgcsvProductDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TcgcsvProductDto(
    val productId: Int,
    val name: String,
    val cleanName: String? = null,
    val imageUrl: String? = null,
    val groupId: Int,
    val extendedData: List<TcgcsvExtendedDataDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TcgcsvExtendedDataDto(
    val name: String,
    val value: String
)
