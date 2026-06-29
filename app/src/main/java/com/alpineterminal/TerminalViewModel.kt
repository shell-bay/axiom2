package com.alpineterminal

import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class TerminalViewModel(private val envManager: LinuxEnvironmentManager) : ViewModel() {

    private val terminalMachine = TerminalStateMachine()

    val sessions = mutableStateListOf<TerminalSession>()
    private val _currentSessionIndex = mutableStateOf(0)
    val currentSessionIndex: State<Int> = _currentSessionIndex

    private val _setupState = mutableStateOf(LinuxEnvironmentManager.SetupState.IDLE)
    val setupState: State<LinuxEnvironmentManager.SetupState> = _setupState
    private val _setupProgress = mutableStateOf(0f)
    val setupProgress: State<Float> = _setupProgress
    private val _setupMessage = mutableStateOf("Initializing...")
    val setupMessage: State<String> = _setupMessage
    private val _needsSetup = mutableStateOf(true)
    val needsSetup: State<Boolean> = _needsSetup

    private val _screenLines = mutableStateOf<List<StyledLine>>(emptyList())
    val screenLines: State<List<StyledLine>> = _screenLines

    private val _cursorRow = mutableStateOf(0)
    val cursorRow: State<Int> = _cursorRow
    private val _cursorCol = mutableStateOf(0)
    val cursorCol: State<Int> = _cursorCol
    private val _cursorVisible = mutableStateOf(true)
    val cursorVisible: State<Boolean> = _cursorVisible

    private val _isShellRunning = mutableStateOf(false)
    val isShellRunning: State<Boolean> = _isShellRunning

    private val _isConnected = mutableStateOf(false)
    val isConnected: State<Boolean> = _isConnected

    private var outputJob: Job? = null
    private var setupJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private val _isWakeLockAcquired = mutableStateOf(false)
    val isWakeLockAcquired: State<Boolean> = _isWakeLockAcquired

    init {
        sessions.add(TerminalSession(1, "Session 1"))
        checkEnvironment()
    }

    private fun checkEnvironment() {
        _needsSetup.value = envManager.needsSetup()
        if (!_needsSetup.value) {
            connectShell()
        } else {
            _setupState.value = LinuxEnvironmentManager.SetupState.IDLE
            _setupMessage.value = "Alpine environment needs setup"
        }
    }

    fun startSetup() {
        if (setupJob?.isActive == true) return

        setupJob = viewModelScope.launch {
            _needsSetup.value = false

            launch {
                envManager.setupProgress.collect { p ->
                    _setupProgress.value = p
                }
            }

            launch {
                envManager.setupState.collect { s ->
                    _setupState.value = s
                    _setupMessage.value = envManager.setupMessage.value
                }
            }

            envManager.performSetup {
                connectShell()
            }

            // Also handle error state
            if (envManager.setupState.value == LinuxEnvironmentManager.SetupState.ERROR) {
                _setupMessage.value = envManager.setupMessage.value
            }
        }
    }

    private fun connectShell() {
        envManager.startShell()
        _isShellRunning.value = true
        _isConnected.value = true
        _setupState.value = LinuxEnvironmentManager.SetupState.READY
        _setupMessage.value = "Alpine Linux ready"

        terminalMachine.feed("\u001b[1;32mWelcome to Axiom Alpine Terminal\u001b[0m\r\n")
        terminalMachine.feed("\u001b[1;34mAlpine Linux ${envManager.getArch().alpineVersion} | ${envManager.getArch().alpineArch}\u001b[0m\r\n")
        terminalMachine.feed("Type \u001b[33mhelp\u001b[0m for available commands\r\n\r\n")

        outputJob = viewModelScope.launch {
            envManager.outputFlow.collect { chunk ->
                terminalMachine.feed(chunk)
                _screenLines.value = terminalMachine.getScreenLines()
                val (cr, cc) = terminalMachine.getCursorPosition()
                _cursorRow.value = cr
                _cursorCol.value = cc
                _cursorVisible.value = terminalMachine.isCursorVisible()
            }
        }

        viewModelScope.launch {
            envManager.isRunning.collect { running ->
                _isShellRunning.value = running
                if (!running && outputJob?.isActive == true) {
                    terminalMachine.feed("\r\n\u001b[1;31mShell disconnected\u001b[0m\r\n")
                    _screenLines.value = terminalMachine.getScreenLines()
                }
            }
        }
    }

    fun sendCommand(command: String) {
        if (command.isBlank()) return
        val activeSession = getActiveSession() ?: return
        activeSession.addHistory(command)
        envManager.writeCommand(command)
    }

    fun sendText(text: String) {
        envManager.writeText(text)
    }

    fun sendControl(code: Int) {
        envManager.writeRaw(byteArrayOf(code.toByte()))
    }

    fun sendEnter() {
        envManager.writeRaw(byteArrayOf(13, 10))
    }

    fun clearTerminal() {
        terminalMachine.clear()
        _screenLines.value = terminalMachine.getScreenLines()
        getActiveSession()?.clear()
    }

    fun getActiveSession(): TerminalSession? {
        return if (sessions.isNotEmpty() && _currentSessionIndex.value in sessions.indices) {
            sessions[_currentSessionIndex.value]
        } else null
    }

    fun selectSession(index: Int) {
        if (index in sessions.indices) {
            _currentSessionIndex.value = index
        }
    }

    fun createNewSession() {
        val nextId = (sessions.map { it.id }.maxOrNull() ?: 0) + 1
        sessions.add(TerminalSession(nextId, "Session $nextId"))
        _currentSessionIndex.value = sessions.size - 1
    }

    fun closeSession(index: Int) {
        if (sessions.size > 1 && index in sessions.indices) {
            sessions.removeAt(index)
            if (_currentSessionIndex.value >= sessions.size) {
                _currentSessionIndex.value = sessions.size - 1
            }
        } else if (sessions.size == 1 && index == 0) {
            sessions[0].clear()
        }
    }

    fun toggleWakeLock(context: Context) {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AlpineTerminal::WakeLock"
                )
            }
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    _isWakeLockAcquired.value = false
                } else {
                    lock.acquire(10 * 60 * 1000L)
                    _isWakeLockAcquired.value = true
                }
            }
        } catch (_: Exception) {}
    }

    fun restartShell() {
        outputJob?.cancel()
        terminalMachine.clear()
        envManager.restartShell()
        _isShellRunning.value = true

        outputJob = viewModelScope.launch {
            envManager.outputFlow.collect { chunk ->
                terminalMachine.feed(chunk)
                _screenLines.value = terminalMachine.getScreenLines()
                val (cr, cc) = terminalMachine.getCursorPosition()
                _cursorRow.value = cr
                _cursorCol.value = cc
                _cursorVisible.value = terminalMachine.isCursorVisible()
            }
        }
    }

    fun resetEnvironment() {
        outputJob?.cancel()
        envManager.resetEnvironment()
        _setupState.value = LinuxEnvironmentManager.SetupState.IDLE
        _setupProgress.value = 0f
        _setupMessage.value = "Environment reset. Restart setup to continue."
        _needsSetup.value = true
        _isConnected.value = false
        _isShellRunning.value = false
    }

    override fun onCleared() {
        super.onCleared()
        outputJob?.cancel()
        setupJob?.cancel()
        envManager.onCleared()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }
}

class TerminalSession(
    val id: Int,
    val name: String
) {
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _history = mutableStateListOf<String>()
    val history: List<String> = _history
    private var historyIndex = -1

    fun append(text: String) {
        _output.value += text
    }

    fun clear() {
        _output.value = ""
    }

    fun addHistory(command: String) {
        _history.add(command)
        historyIndex = _history.size
    }

    fun navigateHistoryUp(): String? {
        if (_history.isEmpty()) return null
        if (historyIndex > 0) {
            historyIndex--
            return _history[historyIndex]
        }
        return null
    }

    fun navigateHistoryDown(): String? {
        if (_history.isEmpty()) return null
        if (historyIndex < _history.size - 1) {
            historyIndex++
            return _history[historyIndex]
        }
        return ""
    }
}
