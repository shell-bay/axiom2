package com.alpineterminal

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class PackageInstallerViewModel(
    private val envManager: LinuxEnvironmentManager,
    private val notificationHelper: NotificationHelper
) : ViewModel() {
    
    private val _isInstalling = mutableStateOf<String?>(null)
    val isInstalling: State<String?> = _isInstalling

    val popularPackages = listOf(
        PackageInfo("Python 3", "python3", "The most popular language for data science and AI"),
        PackageInfo("Git", "git", "Distributed version control system"),
        PackageInfo("Curl", "curl", "Command line tool for transferring data with URLs"),
        PackageInfo("Nano", "nano", "Simple and intuitive text editor"),
        PackageInfo("Vim", "vim", "Powerful text editor for power users"),
        PackageInfo("Htop", "htop", "Interactive process viewer"),
        PackageInfo("Neofetch", "neofetch", "System information tool"),
        PackageInfo("Zsh", "zsh", "The shell with great plugins and themes"),
        PackageInfo("GCC", "gcc", "GNU Compiler Collection for C/C++")
    )

    fun installPackage(packageName: String) {
        viewModelScope.launch {
            _isInstalling.value = packageName
            try {
                val result = envManager.executeCommand("apk add $packageName")
                if (result.contains("Error", ignoreCase = true)) {
                    notificationHelper.showNotification("Install Failed", "Could not install $packageName")
                } else {
                    notificationHelper.showNotification("Success", "$packageName installed successfully!")
                }
            } catch (e: Exception) {
                notificationHelper.showNotification("Error", e.message ?: "Unknown error")
            } finally {
                _isInstalling.value = null
            }
        }
    }
}

data class PackageInfo(
    val displayName: String,
    val packageName: String,
    val description: String
)
