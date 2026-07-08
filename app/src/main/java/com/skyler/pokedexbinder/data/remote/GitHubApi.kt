package com.skyler.pokedexbinder.data.remote

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface GitHubApi {
    @Headers("Accept: application/vnd.github+json")
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getContent(
        @Header("Authorization") auth: String,       // "Bearer <PAT>"
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Query("ref") ref: String = "main"
    ): Response<GitHubContentDto>            // Response<> so 404 is catchable, not thrown

    @Headers("Accept: application/vnd.github+json")
    @PUT("repos/{owner}/{repo}/contents/{path}")
    suspend fun putContent(
        @Header("Authorization") auth: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Body body: GitHubPutRequest
    ): Response<GitHubPutResponse>
}

@JsonClass(generateAdapter = true)
data class GitHubContentDto(
    val content: String?,     // base64, may contain newlines
    val sha: String?,
    val encoding: String?
)

@JsonClass(generateAdapter = true)
data class GitHubPutRequest(
    val message: String,          // commit message
    val content: String,          // base64-encoded new file content
    val sha: String? = null,      // required on update, omitted on create
    val branch: String = "main"
)

@JsonClass(generateAdapter = true)
data class GitHubPutResponse(
    val content: GitHubContentDto?,
    val commit: GitHubCommitDto?
)

@JsonClass(generateAdapter = true)
data class GitHubCommitDto(val sha: String?)
