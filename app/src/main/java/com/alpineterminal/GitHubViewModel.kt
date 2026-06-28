package com.alpineterminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class GitHubViewModel(
    application: Application,
    private val gitHubManager: GitHubManager,
    private val notificationHelper: NotificationHelper
) : AndroidViewModel(application) {
    private val _isPushing = mutableStateOf(false)
    val isPushing: State<Boolean> = _isPushing

    private val _status = mutableStateOf("")
    val status: State<String> = _status

    fun pushProject(path: String, repoName: String) {
        viewModelScope.launch {
            _isPushing.value = true
            _status.value = "Pushing to GitHub..."
            try {
                gitHubManager.pushProject(path, repoName)
                _status.value = "Project pushed successfully!"
                notificationHelper.showNotification("GitHub Sync", "Project $repoName pushed successfully!")
            } catch (e: Exception) {
                _status.value = "Error: ${e.message}"
                notificationHelper.showNotification("GitHub Sync Error", e.message ?: "Unknown error")
            } finally {
                _isPushing.value = false
            }
        }
    }
}
