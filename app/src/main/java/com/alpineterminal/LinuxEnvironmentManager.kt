package com.alpineterminal

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class AlpineArch(
    val abi: String,
    val alpineArch: String,
    val alpineVersion: String = "3.21"
) {
    val rootfsUrl: String
        get() = "https://dl-cdn.alpinelinux.org/alpine/v$alpineVersion/releases/$alpineArch/alpine-minirootfs-$alpineVersion.3-$alpineArch.tar.gz"

    val prootUrl: String
        get() = "https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-${archToProot()}-static"

    private fun archToProot(): String = when (alpineArch) {
        "aarch64" -> "aarch64"
        "armv7" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> "aarch64"
    }

    companion object {
        fun detect(): AlpineArch {
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val alpineArch = when {
                abi.contains("arm64") || abi.contains("aarch64") -> "aarch64"
                abi.contains("armeabi") || abi.contains("armv7") -> "armv7"
                abi.contains("x86_64") -> "x86_64"
                abi.contains("x86") -> "x86"
                else -> "aarch64"
            }
            return AlpineArch(abi, alpineArch)
        }
    }
}

private const val TAG = "LinuxEnvManager"
private const val PROOT_BIN_NAME = "proot"
private const val ROOTFS_DIR_NAME = "alpine_rootfs"

class LinuxEnvironmentManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val arch = AlpineArch.detect()
    private val rootfsDir = File(context.filesDir, ROOTFS_DIR_NAME)
    private val prootBinary = File(context.filesDir, PROOT_BIN_NAME)

    private var shellProcess: Process? = null
    private var shellStdin: OutputStream? = null
    private var shellStdout: InputStream? = null
    private var readJob: Job? = null

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
        IDLE, DOWNLOADING_ROOTFS, EXTRACTING_ROOTFS, DOWNLOADING_PROOT,
        CONFIGURING_ENV, READY, ERROR
    }

    fun needsSetup(): Boolean {
        if (!rootfsDir.exists() || !rootfsDir.isDirectory) return true
        if (!prootBinary.exists() || !prootBinary.canExecute()) return true
        val binSh = File(rootfsDir, "bin/sh")
        if (!binSh.exists()) return true
        return false
    }

    fun performSetup(onComplete: () -> Unit) {
        scope.launch {
            try {
                ensureRootfsDir()
                ensureProotBinary()
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
        val binSh = File(rootfsDir, "bin/sh")
        if (binSh.exists()) {
            _setupProgress.value = 0.5f
            _setupMessage.value = "Rootfs already exists"
            return
        }

        _setupState.value = SetupState.DOWNLOADING_ROOTFS
        _setupMessage.value = "Downloading Alpine Linux ${arch.alpineArch}..."

        rootfsDir.mkdirs()
        val tarGzFile = File(context.cacheDir, "alpine-rootfs.tar.gz")

        downloadFile(arch.rootfsUrl, tarGzFile) { progress ->
            _setupProgress.value = progress * 0.4f
        }

        _setupState.value = SetupState.EXTRACTING_ROOTFS
        _setupMessage.value = "Extracting Alpine rootfs..."
        extractTarGz(tarGzFile, rootfsDir) { progress ->
            _setupProgress.value = 0.4f + progress * 0.35f
        }

        tarGzFile.delete()
        _setupProgress.value = 0.75f
    }

    private suspend fun ensureProotBinary() {
        if (prootBinary.exists() && prootBinary.canExecute()) {
            _setupProgress.value = 0.85f
            _setupMessage.value = "Proot binary ready"
            return
        }

        _setupState.value = SetupState.DOWNLOADING_PROOT
        _setupMessage.value = "Downloading proot for ${arch.abi}..."

        val cachedProot = File(context.cacheDir, PROOT_BIN_NAME)

        try {
            downloadFile(arch.prootUrl, cachedProot) { progress ->
                _setupProgress.value = 0.75f + progress * 0.10f
            }
            cachedProot.copyTo(prootBinary, overwrite = true)
            prootBinary.setExecutable(true, false)
            cachedProot.delete()
            _setupProgress.value = 0.85f
        } catch (e: Exception) {
            Log.w(TAG, "Could not download proot binary: ${e.message}")
            _setupMessage.value = "Creating fallback shell (proot unavailable)..."
            createFallbackShell()
            _setupProgress.value = 0.85f
        }
    }

    private fun createFallbackShell() {
        prootBinary.writeText("""#!/system/bin/sh
exec /system/bin/sh --login "$@"
""")
        prootBinary.setExecutable(true, false)
    }

    private suspend fun configureRootfs() {
        _setupState.value = SetupState.CONFIGURING_ENV
        _setupMessage.value = "Configuring Alpine environment..."

        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        if (!resolvConf.exists() || resolvConf.readText().isBlank()) {
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }

        val profile = File(rootfsDir, "etc/profile")
        if (profile.exists()) {
            val current = profile.readText()
            if (!current.contains("export PATH")) {
                profile.appendText("""
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export PS1='\u@alpine:\w\$ '
alias ll='ls -la'
alias la='ls -A'
""")
            }
        }

        val homeDir = File(rootfsDir, "root")
        homeDir.mkdirs()

        val fstab = File(rootfsDir, "etc/fstab")
        if (!fstab.exists()) {
            fstab.writeText("# Android mounts added by Axiom\n")
        }

        _setupProgress.value = 0.95f
    }

    private suspend fun downloadFile(urlStr: String, target: File, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = true

            try {
                connection.connect()
                val totalBytes = connection.contentLengthLong
                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(target)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead: Long = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalBytes > 0) {
                        onProgress(totalRead.toFloat() / totalBytes.toFloat())
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun extractTarGz(tarGzFile: File, destDir: File, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val fileSize = tarGzFile.length()
            val gzIn = GZIPInputStream(FileInputStream(tarGzFile))
            val buf = ByteArray(512)
            var totalRead = 0L
            var entriesProcessed = 0

            try {
                while (true) {
                    val header = ByteArray(512)
                    var headerRead = 0
                    while (headerRead < 512) {
                        val n = gzIn.read(header, headerRead, 512 - headerRead)
                        if (n == -1) return@withContext
                        headerRead += n
                    }

                    if (header.all { it == 0.toByte() }) break

                    val name = readTarString(header, 0, 100)
                    if (name.isEmpty()) break

                    val sizeStr = readTarString(header, 124, 12)
                    val size = sizeStr.trim().toLongOrNull() ?: 0L
                    val typeFlag = header[156].toInt().toChar()
                    val prefix = readTarString(header, 345, 155)

                    val fullPath = if (prefix.isNotEmpty()) "$prefix/$name" else name
                    val entry = File(destDir, fullPath)

                    if (typeFlag == '5' || name.endsWith("/")) {
                        entry.mkdirs()
                    } else {
                        entry.parentFile?.mkdirs()
                        val fos = FileOutputStream(entry)
                        var remaining = size
                        val dataBuf = ByteArray(4096)
                        while (remaining > 0) {
                            val toRead = minOf(dataBuf.size.toLong(), remaining).toInt()
                            val n = gzIn.read(dataBuf, 0, toRead)
                            if (n == -1) break
                            fos.write(dataBuf, 0, n)
                            remaining -= n
                        }
                        fos.close()
                        entry.setExecutable(false, false)
                    }

                    val padding = (512 - (size % 512)) % 512
                    var skipped = 0L
                    while (skipped < padding) {
                        val n = gzIn.read(buf, 0, minOf(buf.size.toLong(), padding - skipped).toInt())
                        if (n == -1) break
                        skipped += n
                    }

                    totalRead += 512 + size + padding
                    entriesProcessed++
                    if (entriesProcessed % 50 == 0 && fileSize > 0) {
                        onProgress(totalRead.toFloat() / fileSize.toFloat())
                    }
                }
            } finally {
                gzIn.close()
            }
        }
    }

    private fun readTarString(data: ByteArray, offset: Int, maxLen: Int): String {
        val end = (offset until minOf(offset + maxLen, data.size))
            .firstOrNull { data[it] == 0.toByte() } ?: (offset + maxLen)
        return data.copyOfRange(offset, end).decodeToString().trim()
    }

    fun startShell() {
        if (_isRunning.value) return

        scope.launch {
            try {
                val prootPath = prootBinary.absolutePath
                val rootfsPath = rootfsDir.absolutePath

                val cmd = mutableListOf(
                    prootPath,
                    "-r", rootfsPath,
                    "-0",
                    "-b", "/dev",
                    "-b", "/proc",
                    "-b", "/sys",
                    "-b", "/data/data/com.alpineterminal/files:/root/host",
                    "-b", "/system",
                    "-b", "/vendor",
                    "-w", "/root",
                    "/bin/sh", "--login"
                )

                val env = mapOf(
                    "TERM" to "xterm-256color",
                    "HOME" to "/root",
                    "SHELL" to "/bin/sh",
                    "USER" to "root",
                    "LOGNAME" to "root",
                    "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "TMPDIR" to "/tmp",
                    "LANG" to "C.UTF-8",
                    "HOSTNAME" to "axiom-alpine"
                )

                val pb = ProcessBuilder(cmd)
                pb.environment().putAll(env)
                pb.redirectErrorStream(true)

                shellProcess = pb.start()
                shellStdin = shellProcess!!.outputStream
                shellStdout = shellProcess!!.inputStream
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
                    }
                }

                shellProcess!!.waitFor()
                _isRunning.value = false
                Log.d(TAG, "Shell process exited")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proot shell", e)
                _isRunning.value = false
                _output.emit("\r\n\u001b[1;31mError: ${e.message}\u001b[0m\r\n")
            }
        }
    }

    fun writeCommand(command: String) {
        scope.launch {
            try {
                shellStdin?.let {
                    it.write((command + "\n").toByteArray(Charsets.UTF_8))
                    it.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Write error", e)
            }
        }
    }

    fun writeRaw(data: ByteArray) {
        scope.launch {
            try {
                shellStdin?.write(data)
                shellStdin?.flush()
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
        try { shellStdin?.close() } catch (_: Exception) {}
        try { shellProcess?.destroy() } catch (_: Exception) {}
        shellProcess = null
        shellStdin = null
        shellStdout = null
        _isRunning.value = false
    }

    fun restartShell() {
        stopShell()
        startShell()
    }

    fun isEnvironmentReady(): Boolean {
        return rootfsDir.exists() && File(rootfsDir, "bin/sh").exists()
    }

    fun resetEnvironment() {
        stopShell()
        _setupState.value = SetupState.IDLE
        _setupProgress.value = 0f
        _setupMessage.value = ""
        try {
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
        } catch (_: Exception) {}
        try {
            if (prootBinary.exists()) prootBinary.delete()
        } catch (_: Exception) {}
    }

    fun executeCommand(command: String): String {
        return try {
            val prootPath = prootBinary.absolutePath
            val rootfsPath = rootfsDir.absolutePath
            val process = ProcessBuilder(
                prootPath, "-r", rootfsPath, "-0",
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "/bin/sh", "-c", command
            )
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
            "Error: ${e.message}"
        }
    }

    fun getArch(): AlpineArch = arch
    fun getRootfsDir(): File = rootfsDir
    fun getProotBinary(): File = prootBinary
    fun getShellPid(): Int {
        return try {
            val pidField = shellProcess?.javaClass?.getDeclaredField("pid")
            pidField?.isAccessible = true
            pidField?.getInt(shellProcess) ?: -1
        } catch (_: Exception) { -1 }
    }

    fun onCleared() {
        stopShell()
        scope.cancel()
    }
}
