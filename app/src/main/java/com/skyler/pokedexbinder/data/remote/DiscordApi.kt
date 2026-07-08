package com.skyler.pokedexbinder.data.remote

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

interface DiscordApi {
    @POST
    suspend fun sendWebhook(
        @Url webhookUrl: String,
        @Body body: DiscordWebhookPayload
    ): Response<Unit>
}

@JsonClass(generateAdapter = true)
data class DiscordWebhookPayload(
    val embeds: List<DiscordEmbed>
)

@JsonClass(generateAdapter = true)
data class DiscordEmbed(
    val title: String,
    val description: String,
    val url: String,          // link to page #latest anchor
    val color: Int? = 0xE3350D  // pokeball red; optional
)
