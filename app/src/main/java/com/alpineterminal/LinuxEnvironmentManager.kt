package com.alpineterminal

import android.content.Context
import android.os.Build
import android.util.Log
import com.alpineterminal.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

data class CpuArch(
    val abi: String,
    val arch: String
) {
    companion object {
        fun detect(): CpuArch {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val arch = when {
                abi.contains("arm64") || abi.contains("aarch64") -> "aarch64"
                abi.contains("armeabi") || abi.contains("armv7") -> "armv7"
                abi.contains("x86_64") -> "x86_64"
                abi.contains("x86") -> "x86"
                else -> "aarch64"
            }
            return CpuArch(abi, arch)
        }
    }
}

private const val TAG = "LinuxEnvManager"
private const val ROOTFS_DIR_NAME = "axiom_rootfs"
private const val SHELL_TIMEOUT_MS = 30000L
private const val COMMAND_TIMEOUT_MS = 60000L

class LinuxEnvironmentManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val arch = CpuArch.detect()
    private val rootfsDir = File(context.filesDir, ROOTFS_DIR_NAME)

    private val prootBinary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    private var shellProcess: Process? = null
    private var shellStdin: OutputStream? = null
    private var shellStdout: InputStream? = null
    private var readJob: Job? = null
    private var watchDogJob: Job? = null

    private val _output = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 256)
    val outputFlow: SharedFlow<String> = _output.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _setupState = MutableStateFlow(SetupState.IDLE)
    val setupState: StateFlow<SetupState> = _setupState.asStateFlow()

    private val _setupProgress = MutableStateFlow(0f)
    val setupProgress: StateFlow<Float> = _setupProgress.asStateFlow()

    private val _setupMessage = MutableStateFlow("")
    val setupMessage: StateFlow<String> = _setupMessage.asStateFlow()

    enum class SetupState {
        IDLE, EXTRACTING_ROOTFS, CONFIGURING_ENV, READY, ERROR
    }

    fun needsSetup(): Boolean {
        if (!prootBinary.exists() || !prootBinary.canRead()) return true
        if (!rootfsDir.exists() || !rootfsDir.isDirectory) return true
        return !File(rootfsDir, "bin/bash").exists()
    }

    fun performSetup(onComplete: () -> Unit) {
        scope.launch {
            try {
                ensureRootfsDir()
                configureRootfs()
                _setupState.value = SetupState.READY
                _setupProgress.value = 1f
                _setupMessage.value = "Environment ready"
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                _setupState.value = SetupState.ERROR
                _setupMessage.value = "Setup failed: ${e.message}"
            }
        }
    }

    private suspend fun ensureRootfsDir() {
        if (File(rootfsDir, "bin/bash").exists() || File(rootfsDir, "bin/sh").exists()) {
            _setupProgress.value = 1f
            _setupMessage.value = "Rootfs found"
            return
        }
        _setupState.value = SetupState.EXTRACTING_ROOTFS
        _setupMessage.value = "Extracting environment..."
        rootfsDir.mkdirs()

        withTimeout(120_000L) {
            context.resources.openRawResourceFd(R.raw.axiom_rootfs).use { fd ->
                val totalBytes = fd?.length ?: 0L
                val stream = fd?.createInputStream() ?: throw IOException("Cannot read rootfs archive")
                extractTarGz(stream, rootfsDir, totalBytes) { p ->
                    _setupProgress.value = p
                }
            }
        }
        _setupProgress.value = 0.75f
    }

    private suspend fun configureRootfs() {
        _setupState.value = SetupState.CONFIGURING_ENV
        _setupMessage.value = "Configuring environment..."
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        if (!resolvConf.exists() || resolvConf.readText().isBlank()) {
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }
        val shFile = File(rootfsDir, "bin/sh")
        if (!shFile.exists()) {
            val bashFile = File(rootfsDir, "bin/bash")
            if (bashFile.exists()) {
                shFile.parentFile?.mkdirs()
                shFile.writeText("#!/bin/bash\nexec /bin/bash \"$@\"\n")
                shFile.setExecutable(true)
            }
        }
        val profile = File(rootfsDir, "etc/profile")
        if (profile.exists()) {
            val current = profile.readText()
            if (!current.contains("export TERM")) {
                profile.appendText("""
export TERM=xterm-256color
export HOME=/root
export SHELL=/bin/bash
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export PS1='\[\e[1;32m\]\u@axiom\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\$ '
alias ll='ls -la'
alias la='ls -A'
""")
            }
        }
        File(rootfsDir, "root").mkdirs()
        File(rootfsDir, "tmp").mkdirs()
        File(rootfsDir, "dev/shm").mkdirs()
        _setupProgress.value = 0.95f
    }

    private suspend fun extractTarGz(inputStream: InputStream, destDir: File, totalSize: Long, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            GZIPInputStream(inputStream).use { gzIn ->
                val buf = ByteArray(512)
                var totalRead = 0L
                var entries = 0
                while (true) {
                    val header = ByteArray(512)
                    var headerRead = 0
                    while (headerRead < 512) {
                        val n = gzIn.read(header, headerRead, 512 - headerRead)
                        if (n == -1) return@withContext
                        headerRead += n
                    }
                    if (header.all { it == 0.toByte() }) break
                    val name = readTarStr(header, 0, 100)
                    if (name.isEmpty()) break
                    val size = readTarStr(header, 124, 12).trim().toLongOrNull() ?: 0L
                    val typeFlag = header[156].toInt().toChar()
                    val prefix = readTarStr(header, 345, 155)
                    val fullPath = if (prefix.isNotEmpty()) "$prefix/$name" else name
                    val entry = File(destDir, fullPath)
                    if (typeFlag == '5' || name.endsWith("/")) {
                        entry.mkdirs()
                    } else {
                        entry.parentFile?.mkdirs()
                        FileOutputStream(entry).use { fos ->
                            var remaining = size
                            val dataBuf = ByteArray(4096)
                            while (remaining > 0) {
                                val toRead = minOf(dataBuf.size.toLong(), remaining).toInt()
                                val n = gzIn.read(dataBuf, 0, toRead)
                                if (n == -1) break
                                fos.write(dataBuf, 0, n)
                                remaining -= n
                            }
                        }
                    }
                    val pad = (512 - (size % 512)) % 512
                    var skipped = 0L
                    while (skipped < pad) {
                        val n = gzIn.read(buf, 0, minOf(buf.size.toLong(), pad - skipped).toInt())
                        if (n == -1) break
                        skipped += n
                    }
                    totalRead += 512 + size + pad
                    entries++
                    if (entries % 50 == 0 && totalSize > 0) {
                        onProgress((totalRead.toFloat() / (totalSize + totalRead) * 0.5f + 0.25f))
                    }
                }
            }
        }
    }

    private fun readTarStr(data: ByteArray, offset: Int, maxLen: Int): String {
        val end = (offset until minOf(offset + maxLen, data.size)).firstOrNull { data[it] == 0.toByte() } ?: (offset + maxLen)
        return data.copyOfRange(offset, end).decodeToString().trim()
    }

    fun startShell() {
        if (_isRunning.value) return
        scope.launch {
            try {
                val prootPath = prootBinary.absolutePath
                var retries = 0
                while (!prootBinary.exists() && retries < 10) {
                    delay(200)
                    retries++
                }
                if (!prootBinary.exists()) {
                    _output.emit("\r\n\u001b[1;31mError: proot binary not found\u001b[0m\r\n")
                    return@launch
                }
                val shellPath = if (File(rootfsDir, "bin/bash").exists()) "/bin/bash" else "/bin/sh"
                val cmd = mutableListOf(
                    prootPath, "-r", rootfsDir.absolutePath, "-0",
                    "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "-b", "${context.filesDir.absolutePath}:/root/host",
                    "-b", "/system", "-b", "/vendor",
                    "-w", "/root", shellPath, "--login", "-i"
                )
                val env = mutableMapOf(
                    "TERM" to "xterm-256color",
                    "HOME" to "/root",
                    "SHELL" to shellPath,
                    "USER" to "root",
                    "LOGNAME" to "root",
                    "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "TMPDIR" to "/tmp",
                    "HOSTNAME" to "axiom"
                )
                val pb = ProcessBuilder(cmd)
                pb.environment().clear()
                pb.environment().putAll(env)
                pb.redirectErrorStream(true)
                shellProcess = pb.start()
                shellStdin = shellProcess!!.outputStream
                shellStdout = shellProcess!!.inputStream
                _isRunning.value = true

                readJob = launch {
                    try {
                        BufferedReader(InputStreamReader(shellStdout)).use { reader ->
                            val buf = CharArray(4096)
                            while (isActive) {
                                val n = reader.read(buf, 0, buf.size)
                                if (n == -1) break
                                _output.emit(String(buf, 0, n))
                            }
                        }
                    } catch (_: CancellationException) {}
                      catch (e: IOException) { if (isActive) Log.e(TAG, "Read error", e) }
                }

                watchDogJob = launch {
                    delay(SHELL_TIMEOUT_MS)
                    if (_output.subscriptionCount.value == 0) {
                        Log.w(TAG, "No output subscribers, watchdog triggered")
                    }
                }

                shellProcess!!.waitFor()
                _isRunning.value = false
                Log.d(TAG, "Shell exited")
            } catch (e: Exception) {
                Log.e(TAG, "Shell start failed", e)
                _isRunning.value = false
                _output.emit("\r\n\u001b[1;31mError: ${e.message}\u001b[0m\r\n")
            }
        }
    }

    fun writeCommand(command: String) {
        scope.launch {
            try { shellStdin?.let { it.write("$command\n".toByteArray(Charsets.UTF_8)); it.flush() } }
            catch (e: Exception) { Log.e(TAG, "Write error", e) }
        }
    }

    fun writeRaw(data: ByteArray) {
        scope.launch {
            try { shellStdin?.write(data); shellStdin?.flush() }
            catch (e: Exception) { Log.e(TAG, "Write error", e) }
        }
    }

    fun writeText(text: String) = writeRaw(text.toByteArray(Charsets.UTF_8))

    fun stopShell() {
        readJob?.cancel(null)
        watchDogJob?.cancel(null)
        readJob = null; watchDogJob = null
        try { shellStdin?.close() } catch (_: Exception) {}
        destroyProcess(shellProcess)
        shellProcess = null; shellStdin = null; shellStdout = null
        _isRunning.value = false
    }

    private fun destroyProcess(process: Process?) {
        if (process == null) return
        try {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(3, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            try { process.destroyForcibly() } catch (_: Exception) {}
        }
    }

    fun restartShell() { stopShell(); startShell() }

    fun isEnvironmentReady(): Boolean = rootfsDir.exists() && (File(rootfsDir, "bin/bash").exists() || File(rootfsDir, "bin/sh").exists())

    fun resetEnvironment() {
        stopShell()
        _setupState.value = SetupState.IDLE; _setupProgress.value = 0f; _setupMessage.value = ""
        try { if (rootfsDir.exists()) rootfsDir.deleteRecursively() } catch (_: Exception) {}
    }

    fun executeCommand(command: String): String {
        return try {
            val prootPath = prootBinary.absolutePath
            var retries = 0
            while (!prootBinary.exists() && retries < 10) {
                Thread.sleep(200)
                retries++
            }
            if (!prootBinary.exists()) return "Error: proot binary not found"
            val shellPath = if (File(rootfsDir, "bin/bash").exists()) "/bin/bash" else "/bin/sh"
            val envMap = mutableMapOf(
                "TERM" to "xterm-256color",
                "HOME" to "/root",
                "SHELL" to shellPath,
                "USER" to "root",
                "LOGNAME" to "root",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TMPDIR" to "/tmp"
            )
            val pb = ProcessBuilder(
                prootPath, "-r", rootfsDir.absolutePath, "-0",
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-w", "/root",
                shellPath, "-c", command
            )
            pb.environment().clear()
            pb.environment().putAll(envMap)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) output.append(line).append('\n')
            if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                output.append("\n[Command timed out after ${COMMAND_TIMEOUT_MS / 1000}s]")
            }
            output.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    fun getArch(): CpuArch = arch
    fun getRootfsDir(): File = rootfsDir

    fun onCleared() {
        stopShell(); scope.cancel()
    }
}
