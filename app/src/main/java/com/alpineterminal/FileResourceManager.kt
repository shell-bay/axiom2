package com.alpineterminal

import android.content.Context
import java.io.*
import java.util.*

data class AlpineFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

class FileResourceManager(private val context: Context) {
    private val rootfsDir = context.filesDir.absolutePath + "/alpine_rootfs"

    fun listFiles(relativeDir: String = ""): List<AlpineFile> {
        val dirPath = if (relativeDir.isEmpty()) rootfsDir else "$rootfsDir/$relativeDir"
        val directory = File(dirPath)
        
        if (!directory.exists() || !directory.isDirectory) return emptyList()
        
        return directory.listFiles()?.map { file ->
            AlpineFile(
                name = file.name,
                path = file.absolutePath.removePrefix("$rootfsDir/"),
                isDirectory = file.isDirectory,
                size = file.length(),
                lastModified = file.lastModified()
            )
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }

    fun readFile(relativePath: String): String {
        val file = File("$rootfsDir/$relativePath")
        if (!file.exists() || file.isDirectory) return ""
        return file.readText()
    }

    fun writeFile(relativePath: String, content: String): Boolean {
        return try {
            val file = File("$rootfsDir/$relativePath")
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun createFile(relativePath: String, content: String = ""): Boolean {
        return writeFile(relativePath, content)
    }

    fun deleteFile(relativePath: String): Boolean {
        val file = File("$rootfsDir/$relativePath")
        return if (file.exists()) {
            file.deleteRecursively()
        } else {
            false
        }
    }

    fun searchFiles(query: String): List<AlpineFile> {
        val root = File(rootfsDir)
        val results = mutableListOf<AlpineFile>()
        
        root.walkTopDown().forEach { file ->
            if (file.name.contains(query, ignoreCase = true)) {
                results.add(AlpineFile(
                    name = file.name,
                    path = file.absolutePath.removePrefix("$rootfsDir/"),
                    isDirectory = file.isDirectory,
                    size = file.length(),
                    lastModified = file.lastModified()
                ))
            }
        }
        return results
    }

    fun exportFileToAndroid(relativePath: String, destinationFileName: String): File? {
        val sourceFile = File("$rootfsDir/$relativePath")
        if (!sourceFile.exists() || sourceFile.isDirectory) return null
        
        val destFile = File(context.getExternalFilesDir(null), destinationFileName)
        sourceFile.copyTo(destFile, overwrite = true)
        return destFile
    }
}
