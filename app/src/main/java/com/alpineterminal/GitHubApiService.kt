package com.alpineterminal

import retrofit2.Call
import retrofit2.http.*
import com.google.gson.annotations.SerializedName

interface GitHubApiService {
    @GET("user/repos")
    fun listRepositories(
        @Header("Authorization") token: String
    ): Call<List<GitHubRepo>>

    @POST("user/repos")
    fun createRepository(
        @Header("Authorization") token: String,
        @Body repo: GitHubRepo
    ): Call<GitHubRepo>

    @POST("repos/{owner}/{repo}/contents/{path}")
    fun createOrUpdateFile(
        @Header("Authorization") token: String,
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Body fileContent: FileContent
    ): Call<GitHubRepoFile>
}

data class GitHubRepo(
    val name: String,
    @SerializedName("full_name") val fullName: String? = null
)

data class FileContent(
    val message: String,
    val content: String // Base64 encoded
)

data class GitHubRepoFile(
    val content: GitHubRepoFileContent
)

data class GitHubRepoFileContent(
    val sha: String
)
