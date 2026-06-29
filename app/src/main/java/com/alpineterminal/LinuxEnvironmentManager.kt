package com.alpineterminal

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.io.*

class LinuxEnvironmentManager(private val context: Context) {
    private val TAG = "LinuxEnvManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var shellProcess: Process? = null
    private var shellStdin: OutputStream? = null
    private var shellStdout: InputStream? = null
    private val _output = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 128)
    val outputFlow: SharedFlow<String> = _output.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var readJob: Job? = null
    private var shellPid = -1

    private val _shellExited = MutableSharedFlow<Int>(replay = 1)
    val shellExited: SharedFlow<Int> = _shellExited.asSharedFlow()

    private fun findShell(): String {
        val candidates = listOf(
            "/system/bin/sh",
            "/system/xbin/sh",
            "/bin/sh",
            "/data/data/com.termux/files/usr/bin/bash",
            "/data/data/com.termux/files/usr/bin/zsh"
        )
        for (path in candidates) {
            if (File(path).exists()) {
                Log.d(TAG, "Found shell: $path")
                return path
            }
        }
        return "/system/bin/sh"
    }

    fun startShell() {
        if (_isRunning.value) return
        scope.launch {
            try {
                val shell = findShell()
                Log.d(TAG, "Starting shell: $shell")

                val env = mapOf(
                    "TERM" to "xterm-256color",
                    "HOME" to context.filesDir.absolutePath,
                    "TMPDIR" to (context.cacheDir.absolutePath),
                    "SHELL" to shell,
                    "USER" to "u0_a",
                    "LOGNAME" to "u0_a",
                    "PATH" to "/system/bin:/system/xbin:/data/data/com.termux/files/usr/bin:/bin:/usr/bin:${
                        context.filesDir.absolutePath
                    }/bin"
                )

                val pb = ProcessBuilder(shell, "--login")
                pb.environment().putAll(env)
                pb.redirectErrorStream(true)

                shellProcess = pb.start()
                shellStdin = shellProcess!!.outputStream
                shellStdout = shellProcess!!.inputStream

                try {
                    val pidField = shellProcess!!::class.java.getDeclaredField("pid")
                    pidField.isAccessible = true
                    shellPid = pidField.getInt(shellProcess!!)
                } catch (_: Exception) {}

                _isRunning.value = true

                readJob = launch {
                    try {
                        val reader = BufferedReader(InputStreamReader(shellStdout))
                        val buf = CharArray(4096)
                        while (isActive) {
                            val bytesRead = reader.read(buf, 0, buf.size)
                            if (bytesRead == -1) break
                            val chunk = String(buf, 0, bytesRead)
                            _output.emit(chunk)
                        }
                    } catch (e: IOException) {
                        if (isActive) Log.e(TAG, "Shell read error", e)
                    } catch (e: CancellationException) {
                        // Normal cancellation
                    }
                }

                val exitCode = shellProcess!!.waitFor()
                _shellExited.emit(exitCode)
                _isRunning.value = false
                Log.d(TAG, "Shell exited with code: $exitCode")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start shell", e)
                _isRunning.value = false
                _output.emit("\r\n\x1b[31mError: Could not start shell - ${e.message}\x1b[0m\r\n")
            }
        }
    }

    fun writeCommand(command: String) {
        scope.launch {
            try {
                shellStdin?.let { stdin ->
                    stdin.write((command + "\n").toByteArray(Charsets.UTF_8))
                    stdin.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Write error", e)
                _output.emit("\r\n\x1b[31mError writing to shell: ${e.message}\x1b[0m\r\n")
            }
        }
    }

    fun writeRaw(data: ByteArray) {
        scope.launch {
            try {
                shellStdin?.let { stdin ->
                    stdin.write(data)
                    stdin.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Write error", e)
            }
        }
    }

    fun writeText(text: String) {
        writeRaw(text.toByteArray(Charsets.UTF_8))
    }

    fun stopShell() {
        readJob?.cancel()
        readJob = null
        try {
            shellStdin?.close()
        } catch (_: Exception) {}
        try {
            shellProcess?.destroy()
        } catch (_: Exception) {}
        shellProcess = null
        shellStdin = null
        shellStdout = null
        _isRunning.value = false
    }

    fun restartShell() {
        stopShell()
        startShell()
    }

    fun isEnvironmentReady(): Boolean = true

    fun setupEnvironment(
        rootfsUrl: String,
        prootUrl: String,
        onProgress: (Float) -> Unit,
        onComplete: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        onComplete()
    }

    fun executeCommand(command: String): String {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
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

    fun onCleared() {
        stopShell()
        scope.cancel()
    }
}
