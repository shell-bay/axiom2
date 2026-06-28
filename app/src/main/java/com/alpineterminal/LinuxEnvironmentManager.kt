package com.alpineterminal

import android.content.Context
import android.util.Log
import java.io.*
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry

class LinuxEnvironmentManager(private val context: Context) {
    private val TAG = "LinuxEnvManager"
    private val rootfsDir = context.filesDir.absolutePath + "/alpine_rootfs"
    
    // Use nativeLibraryDir to comply with Android 10+ execution policies
    private val prootBinaryPath = "${context.applicationInfo.nativeLibraryDir}/libproot.so"

    fun isEnvironmentReady(): Boolean {
        return File(rootfsDir).exists() && File(prootBinaryPath).exists()
    }

    fun setupEnvironment(rootfsUrl: String, prootUrl: String, onProgress: (Float) -> Unit, onComplete: () -> Unit, onError: (Exception) -> Unit) {
        Thread {
            try {
                downloadAndExtractRootfs(rootfsUrl, onProgress)
                downloadProot(prootUrl)
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                onError(e)
            }
        }.start()
    }

    private fun downloadAndExtractRootfs(url: String, onProgress: (Float) -> Unit) {
        Log.d(TAG, "Downloading rootfs from $url...")
        // In a real app, we would use OkHttp to download the zip file.
        // Here we simulate the extraction process.
        val rootDir = File(rootfsDir)
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
        
        // Simulate a long process
        for (i in 1..10) {
            Thread.sleep(200)
            onProgress(i / 10f)
        }
        
        // Create a dummy /bin/sh to simulate a shell
        val binDir = File(rootfsDir, "bin")
        binDir.mkdirs()
        File(binDir, "sh").writeText("""#!/bin/sh
echo 'Welcome to Alpine Linux!'
/bin/sh""")
    }

    private fun downloadProot(url: String) {
        Log.d(TAG, "Downloading PRoot binary...")
        val binDir = File(context.filesDir, "bin")
        if (!binDir.exists()) binDir.mkdirs()
        
        val prootFile = File(prootBinaryPath)
        prootFile.writeText("""#!/bin/sh
echo 'PRoot simulating execution...'""")
        prootFile.setExecutable(true)
    }

    fun executeCommand(command: String): String {
        return try {
            // This is a simulation. In reality, we would use ProcessBuilder 
            // to run: proot -r rootfsDir -0 -b /dev -b /proc -b /sys /bin/sh -c "command"
            val process = ProcessBuilder(prootBinaryPath, "-r", rootfsDir, "/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            output.toString()
        } catch (e: Exception) {
            "Error executing command: ${e.message}"
        }
    }
}
