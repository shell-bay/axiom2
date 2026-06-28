package com.alpineterminal

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class GitHubManager(private val context: Context, private val settingsManager: SettingsManager) {
    private val api: GitHubApiService = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GitHubApiService::class.java)

    suspend fun pushProject(relativePath: String, repoName: String) {
        withContext(Dispatchers.IO) {
            val token = settingsManager.githubToken.value
            if (token.isEmpty()) throw Exception("GitHub PAT not configured")

            val authHeader = "token $token"
            val files = listAllFiles(relativePath)
            
            val owner = getGitHubUsername(authHeader) ?: throw Exception("Could not identify GitHub user")

            files.forEach { file ->
                val content = readAndEncodeFile(file)
                val pathInRepo = file.removePrefix("$relativePath/")
                
                api.createOrUpdateFile(
                    authHeader, owner, repoName, pathInRepo,
                    FileContent("Updated from AlpineTerminal", content)
                ).execute()
            }
        }
    }

    private fun listAllFiles(dirPath: String): List<String> {
        val root = File(context.filesDir, "alpine_rootfs/$dirPath")
        val fileList = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile }.forEach { 
            fileList.add(it.absolutePath.removePrefix(context.filesDir.absolutePath + "/alpine_rootfs/"))
        }
        return fileList
    }

    private fun readAndEncodeFile(path: String): String {
        val file = File(context.filesDir, "alpine_rootfs/$path")
        val bytes = file.readBytes()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun getGitHubUsername(authHeader: String): String? {
        return try {
            val response = api.listRepositories(authHeader).execute()
            if (response.isSuccessful) {
                response.body()?.firstOrNull()?.fullName?.split("/")?.firstOrNull()
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
