package com.alpineterminal

import android.util.Log
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*

private const val TAG = "SshManager"

class SshManager {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var stdin: OutputStream? = null
    private var stdout: InputStream? = null
    private var readJob: Job? = null
    private var connectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _output = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 256)
    val outputFlow: SharedFlow<String> = _output.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionStatus = MutableStateFlow("")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _error = MutableSharedFlow<String>(replay = 0)
    val errorFlow: SharedFlow<String> = _error.asSharedFlow()

    data class SshAuth(
        val host: String,
        val port: Int = 22,
        val username: String,
        val password: String = "",
        val privateKey: String = ""
    )

    fun connect(auth: SshAuth) {
        disconnect()
        connectJob = scope.launch {
            try {
                _connectionStatus.value = "Connecting to ${auth.host}:${auth.port}..."
                _isConnected.value = false

                val jsch = JSch()
                if (auth.privateKey.isNotBlank()) {
                    jsch.addIdentity("ssh-key", auth.privateKey.toByteArray(), null, null)
                }

                session = jsch.getSession(auth.username, auth.host, auth.port).apply {
                    if (auth.password.isNotBlank()) setPassword(auth.password)
                    setConfig("StrictHostKeyChecking", "no")
                    setConfig("PreferredAuthentications",
                        if (auth.privateKey.isNotBlank()) "publickey,password,keyboard-interactive"
                        else "password,keyboard-interactive")
                    connect(15000)
                }

                channel = session?.openChannel("shell") as ChannelShell
                channel?.setPty(true)
                channel?.setPtySize(80, 24, 0, 0)

                stdin = channel?.outputStream
                stdout = channel?.inputStream

                channel?.connect(10000)

                _isConnected.value = true
                _connectionStatus.value = "Connected to ${auth.username}@${auth.host}"

                readJob = launch {
                    try {
                        val reader = BufferedReader(InputStreamReader(stdout))
                        val buf = CharArray(4096)
                        while (isActive) {
                            val n = reader.read(buf, 0, buf.size)
                            if (n == -1) break
                            _output.emit(String(buf, 0, n))
                        }
                    } catch (_: CancellationException) {}
                    catch (e: IOException) {
                        if (isActive) { _error.emit("Connection lost: ${e.message}"); Log.e(TAG, "SSH read error", e) }
                    }
                }

                session?.setServerAliveInterval(10000)
                session?.setServerAliveCountMax(3)

            } catch (e: JSchException) {
                _isConnected.value = false
                _connectionStatus.value = "Connection failed"
                _error.emit("SSH error: ${e.message}")
                Log.e(TAG, "SSH connect error", e)
            } catch (e: Exception) {
                _isConnected.value = false
                _connectionStatus.value = "Connection failed"
                _error.emit("Error: ${e.message}")
                Log.e(TAG, "SSH error", e)
            }
        }
    }

    fun writeCommand(command: String) {
        scope.launch {
            try { stdin?.let { it.write("$command\n".toByteArray(Charsets.UTF_8)); it.flush() } }
            catch (e: Exception) { Log.e(TAG, "SSH write error", e) }
        }
    }

    fun writeData(data: ByteArray) {
        scope.launch {
            try { stdin?.write(data); stdin?.flush() }
            catch (e: Exception) { Log.e(TAG, "SSH write error", e) }
        }
    }

    fun writeText(text: String) = writeData(text.toByteArray(Charsets.UTF_8))

    fun disconnect() {
        readJob?.cancel(null)
        readJob = null
        connectJob?.cancel(null)
        connectJob = null
        try { stdin?.close() } catch (_: Exception) {}
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        channel = null; session = null; stdin = null; stdout = null
        _isConnected.value = false
        _connectionStatus.value = "Disconnected"
    }

    fun resizeTerminal(cols: Int, rows: Int) {
        try { channel?.setPtySize(cols, rows, 0, 0) } catch (_: Exception) {}
    }

    fun onCleared() { disconnect(); scope.cancel() }
}
