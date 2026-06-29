package com.alpineterminal

import android.content.Context
import java.io.*

data class AlpineFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

class FileResourceManager(private val context: Context) {
    private val hostDir = context.filesDir

    fun listFiles(relativeDir: String = ""): List<AlpineFile> {
        val target = if (relativeDir.isEmpty()) hostDir else File(hostDir, relativeDir)
        if (!target.exists() || !target.isDirectory) return emptyList()
        return target.listFiles()?.map { file ->
            AlpineFile(
                name = file.name,
                path = file.absolutePath.removePrefix("${hostDir.absolutePath}/"),
                isDirectory = file.isDirectory,
                size = if (file.isDirectory) 0L else file.length(),
                lastModified = file.lastModified()
            )
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }

    fun readFile(relativePath: String): String {
        val file = File(hostDir, relativePath)
        if (!file.exists() || file.isDirectory) return ""
        return try { file.readText() } catch (_: Exception) { "" }
    }

    fun createFile(relativePath: String, content: String = ""): Boolean {
        return writeFile(relativePath, content)
    }

    fun writeFile(relativePath: String, content: String): Boolean {
        return try {
            val file = File(hostDir, relativePath)
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        } catch (_: Exception) { false }
    }

    fun deleteFile(relativePath: String): Boolean {
        val file = File(hostDir, relativePath)
        return if (file.exists()) { file.deleteRecursively(); true } else false
    }

    fun exportFileToAndroid(relativePath: String, destinationFileName: String): File? {
        val sourceFile = File(hostDir, relativePath)
        if (!sourceFile.exists() || sourceFile.isDirectory) return null
        val destFile = File(context.getExternalFilesDir(null), destinationFileName)
        return try {
            sourceFile.copyTo(destFile, overwrite = true)
            destFile
        } catch (_: Exception) { null }
    }

    fun searchFiles(query: String): List<AlpineFile> {
        val results = mutableListOf<AlpineFile>()
        hostDir.walkTopDown().forEach { file ->
            if (file.name.contains(query, ignoreCase = true)) {
                results.add(AlpineFile(
                    name = file.name,
                    path = file.absolutePath.removePrefix("${hostDir.absolutePath}/"),
                    isDirectory = file.isDirectory,
                    size = if (file.isDirectory) 0L else file.length(),
                    lastModified = file.lastModified()
                ))
            }
        }
        return results
    }
}
