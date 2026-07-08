package com.skyler.pokedexbinder.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TcgCardDto(
    val id: String,
    val name: String,
    val number: String,
    val set: TcgSetDto,
    val images: TcgImagesDto,
    val hp: String? = null,
    val artist: String? = null
)

@JsonClass(generateAdapter = true)
data class TcgSetDto(
    val name: String,
    val releaseDate: String? = null
)

@JsonClass(generateAdapter = true)
data class TcgImagesDto(
    val small: String,
    val large: String
)

@JsonClass(generateAdapter = true)
data class TcgCardsResponse(
    val data: List<TcgCardDto>,
    val totalCount: Int
)

@JsonClass(generateAdapter = true)
data class TcgCardResponse(
    val data: TcgCardDto
)
