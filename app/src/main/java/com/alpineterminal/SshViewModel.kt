package com.alpineterminal

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class SshViewModel(
    private val settingsManager: SettingsManager
) : ViewModel() {

    private val sshManager = SshManager()

    private val _screen = mutableStateOf<SshScreenState>(SshScreenState.CONNECTION_LIST)
    val screen: State<SshScreenState> = _screen

    private val _connections = mutableStateOf<List<SshConnection>>(emptyList())
    val connections: State<List<SshConnection>> = _connections

    private val _terminalMachine = TerminalStateMachine()
    val terminalMachine: TerminalStateMachine get() = _terminalMachine

    private val _screenLines = mutableStateOf<List<StyledLine>>(emptyList())
    val screenLines: State<List<StyledLine>> = _screenLines

    private val _isConnected = mutableStateOf(false)
    val isConnected: State<Boolean> = _isConnected

    private val _connectionStatus = mutableStateOf("")
    val connectionStatus: State<String> = _connectionStatus

    private val _error = mutableStateOf<String?>(null)
    val error: State<String?> = _error

    private val _cursorRow = mutableStateOf(0)
    val cursorRow: State<Int> = _cursorRow
    private val _cursorCol = mutableStateOf(0)
    val cursorCol: State<Int> = _cursorCol
    private val _cursorVisible = mutableStateOf(true)
    val cursorVisible: State<Boolean> = _cursorVisible

    // Connection form fields
    private val _formName = mutableStateOf("")
    val formName: State<String> = _formName
    private val _formHost = mutableStateOf("")
    val formHost: State<String> = _formHost
    private val _formPort = mutableStateOf("22")
    val formPort: State<String> = _formPort
    private val _formUsername = mutableStateOf("")
    val formUsername: State<String> = _formUsername
    private val _formPassword = mutableStateOf("")
    val formPassword: State<String> = _formPassword
    private val _formAuthType = mutableStateOf(AuthType.PASSWORD)
    val formAuthType: State<AuthType> = _formAuthType
    private val _editingId = mutableStateOf<String?>(null)
    val editingId: State<String?> = _editingId

    private var outputJob: Job? = null

    enum class SshScreenState {
        CONNECTION_LIST, CONNECTION_FORM, TERMINAL
    }

    init {
        refreshConnections()
        viewModelScope.launch {
            sshManager.connectionStatus.collect { _connectionStatus.value = it }
        }
        viewModelScope.launch {
            sshManager.isConnected.collect { _isConnected.value = it }
        }
        viewModelScope.launch {
            sshManager.errorFlow.collect { err ->
                _error.value = err
                _screenLines.value = _terminalMachine.getScreenLines()
            }
        }
    }

    private fun refreshConnections() {
        _connections.value = settingsManager.sshConnections.value
    }

    fun showConnectionList() {
        refreshConnections()
        _screen.value = SshScreenState.CONNECTION_LIST
    }

    fun showNewConnection() {
        _formName.value = ""; _formHost.value = ""; _formPort.value = "22"
        _formUsername.value = ""; _formPassword.value = ""; _formAuthType.value = AuthType.PASSWORD
        _editingId.value = null
        _screen.value = SshScreenState.CONNECTION_FORM
    }

    fun showEditConnection(conn: SshConnection) {
        _formName.value = conn.name; _formHost.value = conn.host
        _formPort.value = conn.port.toString(); _formUsername.value = conn.username
        _formPassword.value = ""; _formAuthType.value = conn.authType
        _editingId.value = conn.id
        _screen.value = SshScreenState.CONNECTION_FORM
    }

    fun connectTo(conn: SshConnection) {
        val auth = SshManager.SshAuth(
            host = conn.host, port = conn.port,
            username = conn.username,
            password = _formPassword.value,
            privateKey = settingsManager.sshPrivateKey.value
        )
        settingsManager.saveSshConnection(conn.copy(lastUsed = System.currentTimeMillis()))
        _terminalMachine.clear()
        _screenLines.value = _terminalMachine.getScreenLines()
        _screen.value = SshScreenState.TERMINAL
        _error.value = null
        sshManager.connect(auth)
        connectOutput()
    }

    private fun connectOutput() {
        outputJob?.cancel()
        outputJob = viewModelScope.launch {
            sshManager.outputFlow.collect { chunk ->
                _terminalMachine.feed(chunk)
                _screenLines.value = _terminalMachine.getScreenLines()
                _cursorRow.value = _terminalMachine.getCursorRow()
                _cursorCol.value = _terminalMachine.getCursorCol()
                _cursorVisible.value = _terminalMachine.isCursorVisible()
            }
        }
    }

    fun disconnectFromSsh() {
        outputJob?.cancel()
        sshManager.disconnect()
    }

    fun sendCommand(cmd: String) {
        if (cmd.isBlank()) return
        sshManager.writeCommand(cmd)
    }

    fun sendText(text: String) = sshManager.writeText(text)
    fun sendData(data: ByteArray) = sshManager.writeData(data)
    fun sendControl(code: Int) = sshManager.writeData(byteArrayOf(code.toByte()))
    fun sendEnter() = sshManager.writeData(byteArrayOf(13, 10))

    fun clearTerminal() {
        _terminalMachine.clear()
        _screenLines.value = _terminalMachine.getScreenLines()
    }

    fun saveConnection() {
        val port = _formPort.value.toIntOrNull() ?: 22
        val conn = SshConnection(
            id = _editingId.value ?: java.util.UUID.randomUUID().toString(),
            name = _formName.value.ifBlank { _formHost.value },
            host = _formHost.value,
            port = port.coerceIn(1, 65535),
            username = _formUsername.value,
            authType = _formAuthType.value,
            lastUsed = System.currentTimeMillis()
        )
        settingsManager.saveSshConnection(conn)
        if (_formPassword.value.isNotBlank()) {
            settingsManager.setSshPrivateKey(_formPassword.value)
        }
        showConnectionList()
    }

    fun deleteConnection(id: String) {
        settingsManager.deleteSshConnection(id)
        refreshConnections()
    }

    fun updateFormName(v: String) { _formName.value = v }
    fun updateFormHost(v: String) { _formHost.value = v }
    fun updateFormPort(v: String) { _formPort.value = v }
    fun updateFormUsername(v: String) { _formUsername.value = v }
    fun updateFormPassword(v: String) { _formPassword.value = v }
    fun updateFormAuthType(v: AuthType) { _formAuthType.value = v }

    fun getConnectionById(id: String): SshConnection? = _connections.value.find { it.id == id }
    fun getFormPassword(): String = _formPassword.value
    fun getSshPrivateKey(): String = settingsManager.sshPrivateKey.value

    override fun onCleared() {
        super.onCleared()
        outputJob?.cancel()
        sshManager.onCleared()
    }
}
