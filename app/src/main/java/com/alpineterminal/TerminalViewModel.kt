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

    private val _isInitializing = mutableStateOf(true)
    val isInitializing: State<Boolean> = _isInitializing

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

    private var outputJob: Job? = null
    private val pendingInput = StringBuilder()

    // WakeLock
    private var wakeLock: PowerManager.WakeLock? = null
    private val _isWakeLockAcquired = mutableStateOf(false)
    val isWakeLockAcquired: State<Boolean> = _isWakeLockAcquired

    init {
        sessions.add(TerminalSession(1, "Session 1"))
        initializeShell()
    }

    private fun initializeShell() {
        envManager.startShell()
        _isInitializing.value = false
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

        viewModelScope.launch {
            envManager.shellExited.collect { exitCode ->
                _isShellRunning.value = false
                if (exitCode != 0) {
                    terminalMachine.feed("\r\n\x1b[1;31mShell exited with code $exitCode\x1b[0m\r\n")
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
        envManager.restartShell()
        _isShellRunning.value = true
        terminalMachine.clear()
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

    override fun onCleared() {
        super.onCleared()
        outputJob?.cancel()
        envManager.onCleared()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
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
