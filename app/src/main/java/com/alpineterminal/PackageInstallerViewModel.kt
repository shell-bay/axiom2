package com.alpineterminal

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PackageInstallerViewModel(
    private val envManager: LinuxEnvironmentManager,
    private val notificationHelper: NotificationHelper
) : ViewModel() {

    private val _isInstalling = mutableStateOf<String?>(null)
    val isInstalling: State<String?> = _isInstalling

    private val _installLog = mutableStateOf("")
    val installLog: State<String> = _installLog

    private val _packageStatus = mutableStateOf<Map<String, Boolean>>(emptyMap())
    val packageStatus: State<Map<String, Boolean>> = _packageStatus

    val popularPackages = listOf(
        PackageInfo("Python 3", "python3", "Python programming language interpreter"),
        PackageInfo("Git", "git", "Distributed version control system"),
        PackageInfo("Curl", "curl", "HTTP/FTP command-line client"),
        PackageInfo("Nano", "nano", "Simple terminal text editor"),
        PackageInfo("Vim", "vim", "Advanced modal text editor"),
        PackageInfo("Htop", "htop", "Interactive process viewer"),
        PackageInfo("Neofetch", "neofetch", "System information display tool"),
        PackageInfo("GCC", "gcc", "GNU C/C++ compiler collection"),
        PackageInfo("Node.js", "nodejs", "JavaScript runtime environment"),
        PackageInfo("OpenSSH", "openssh", "SSH client and server"),
        PackageInfo("Tree", "tree", "Directory tree viewer"),
        PackageInfo("Wget", "wget", "Network download utility"),
        PackageInfo("Make", "make", "Build automation tool"),
        PackageInfo("Lua", "lua", "Lightweight scripting language"),
        PackageInfo("Busybox", "busybox", "Swiss-army knife of Unix tools"),
        PackageInfo("Tmux", "tmux", "Terminal multiplexer"),
        PackageInfo("Ripgrep", "ripgrep", "Recursive text search tool"),
        PackageInfo("Fd", "fd", "Fast file finder")
    )

    fun installPackage(packageName: String) {
        viewModelScope.launch {
            _isInstalling.value = packageName
            _installLog.value = "Installing $packageName...\n"
            try {
                val result = withContext(Dispatchers.IO) {
                    envManager.executeCommand("apt-get install -y $packageName 2>&1")
                }
                _installLog.value += result
                if (result.contains("Setting up", ignoreCase = true) || result.contains("already", ignoreCase = true)) {
                    _packageStatus.value = _packageStatus.value + (packageName to true)
                    notificationHelper.showNotification("Package Installed", "$packageName installed")
                } else {
                    notificationHelper.showNotification("Install Issue", "Check terminal output for $packageName")
                }
            } catch (e: Exception) {
                _installLog.value += "Error: ${e.message}\n"
                notificationHelper.showNotification("Install Failed", packageName)
            } finally {
                _isInstalling.value = null
            }
        }
    }

    fun uninstallPackage(packageName: String) {
        viewModelScope.launch {
            _isInstalling.value = packageName
            _installLog.value = "Removing $packageName...\n"
            try {
                val result = withContext(Dispatchers.IO) {
                    envManager.executeCommand("apt-get remove -y $packageName 2>&1")
                }
                _installLog.value += result
                _packageStatus.value = _packageStatus.value - packageName
                notificationHelper.showNotification("Package Removed", "$packageName uninstalled")
            } catch (e: Exception) {
                _installLog.value += "Error: ${e.message}\n"
            } finally {
                _isInstalling.value = null
            }
        }
    }

    fun updatePackages() {
        viewModelScope.launch {
            _isInstalling.value = "__update__"
            _installLog.value = "Updating package index...\n"
            try {
                val result = withContext(Dispatchers.IO) {
                    envManager.executeCommand("apt-get update 2>&1")
                }
                _installLog.value += result
            } catch (e: Exception) {
                _installLog.value += "Error: ${e.message}\n"
            } finally {
                _isInstalling.value = null
            }
        }
    }

    fun upgradePackages() {
        viewModelScope.launch {
            _isInstalling.value = "__upgrade__"
            _installLog.value = "Upgrading packages...\n"
            try {
                val result = withContext(Dispatchers.IO) {
                    envManager.executeCommand("apt-get upgrade -y 2>&1")
                }
                _installLog.value += result
            } catch (e: Exception) {
                _installLog.value += "Error: ${e.message}\n"
            } finally {
                _isInstalling.value = null
            }
        }
    }

    fun listInstalled(): String {
        return try {
            envManager.executeCommand("dpkg --get-selections 2>&1")
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

data class PackageInfo(
    val displayName: String,
    val packageName: String,
    val description: String
)
