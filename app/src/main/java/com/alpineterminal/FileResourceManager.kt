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
    private val rootfsDir = File(context.filesDir, "alpine_rootfs")

    fun isRootfsReady(): Boolean {
        return rootfsDir.exists() && File(rootfsDir, "bin/sh").exists()
    }

    fun listFiles(relativeDir: String = ""): List<AlpineFile> {
        if (!rootfsDir.exists()) return emptyList()
        val target = if (relativeDir.isEmpty()) rootfsDir else File(rootfsDir, relativeDir)
        if (!target.exists() || !target.isDirectory) return emptyList()
        return target.listFiles()?.map { file ->
            AlpineFile(
                name = file.name,
                path = file.absolutePath.removePrefix("${rootfsDir.absolutePath}/"),
                isDirectory = file.isDirectory,
                size = if (file.isDirectory) 0L else file.length(),
                lastModified = file.lastModified()
            )
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }

    fun readFile(relativePath: String): String {
        val file = File(rootfsDir, relativePath)
        if (!file.exists() || file.isDirectory) return ""
        return try { file.readText() } catch (_: Exception) { "" }
    }

    fun writeFile(relativePath: String, content: String): Boolean {
        return try {
            val file = File(rootfsDir, relativePath)
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        } catch (_: Exception) { false }
    }

    fun createFile(relativePath: String, content: String = ""): Boolean {
        return writeFile(relativePath, content)
    }

    fun deleteFile(relativePath: String): Boolean {
        val file = File(rootfsDir, relativePath)
        return if (file.exists()) { file.deleteRecursively(); true } else false
    }

    fun searchFiles(query: String): List<AlpineFile> {
        if (!rootfsDir.exists()) return emptyList()
        val results = mutableListOf<AlpineFile>()
        rootfsDir.walkTopDown().forEach { file ->
            if (file.name.contains(query, ignoreCase = true)) {
                results.add(AlpineFile(
                    name = file.name,
                    path = file.absolutePath.removePrefix("${rootfsDir.absolutePath}/"),
                    isDirectory = file.isDirectory,
                    size = if (file.isDirectory) 0L else file.length(),
                    lastModified = file.lastModified()
                ))
            }
        }
        return results
    }

    fun exportFileToAndroid(relativePath: String, destinationFileName: String): File? {
        val sourceFile = File(rootfsDir, relativePath)
        if (!sourceFile.exists() || sourceFile.isDirectory) return null
        val destFile = File(context.getExternalFilesDir(null), destinationFileName)
        return try {
            sourceFile.copyTo(destFile, overwrite = true)
            destFile
        } catch (_: Exception) { null }
    }

    fun getRootfsSize(): Long {
        if (!rootfsDir.exists()) return 0L
        return rootfsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun getFileCount(): Int {
        if (!rootfsDir.exists()) return 0
        return rootfsDir.walkTopDown().count() - 1
    }
}
