package com.alpineterminal

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TerminalViewModel(private val envManager: LinuxEnvironmentManager) : ViewModel() {
    private val _terminalOutput = MutableStateFlow("")
    val terminalOutput: StateFlow<String> = _terminalOutput

    private val _isInitializing = mutableStateOf(true)
    val isInitializing: State<Boolean> = _isInitializing

    private val _progress = mutableStateOf(0f)
    val progress: State<Float> = _progress

    init {
        initializeEnvironment()
    }

    private fun initializeEnvironment() {
        if (envManager.isEnvironmentReady()) {
            _isInitializing.value = false
        } else {
            envManager.setupEnvironment(
                rootfsUrl = "https://example.com/alpine.tar.gz",
                prootUrl = "https://example.com/proot",
                onProgress = { p -> _progress.value = p },
                onComplete = { _isInitializing.value = false },
                onError = { e -> 
                    _terminalOutput.value += "Error initializing: ${e.message}
"
                    _isInitializing.value = false 
                }
            )
        }
    }

    fun sendCommand(command: String) {
        viewModelScope.launch {
            _terminalOutput.value += "> $command
"
            val result = envManager.executeCommand(command)
            _terminalOutput.value += result + "
"
        }
    }

    fun clearTerminal() {
        _terminalOutput.value = ""
    }
}
