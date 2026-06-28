package com.alpineterminal

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class FileViewModel(private val fileManager: FileResourceManager) : ViewModel() {
    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath

    private val _files = MutableStateFlow<List<AlpineFile>>(emptyList())
    val files: StateFlow<List<AlpineFile>> = _files

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    init {
        refreshFiles()
    }

    fun refreshFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val query = _searchQuery.value
            if (query.isEmpty()) {
                _files.value = fileManager.listFiles(_currentPath.value)
            } else {
                _files.value = fileManager.searchFiles(query)
            }
        }
    }

    fun navigateTo(path: String) {
        _currentPath.value = path
        refreshFiles()
    }

    fun navigateUp() {
        val current = _currentPath.value
        if (current.isEmpty()) return
        
        val parts = current.split("/").filter { it.isNotEmpty() }.toMutableList()
        if (parts.isNotEmpty()) {
            parts.removeAt(parts.size - 1)
            _currentPath.value = parts.joinToString("/")
        } else {
            _currentPath.value = ""
        }
        refreshFiles()
    }

    fun setQuery(query: String) {
        _searchQuery.value = query
        refreshFiles()
    }

    fun deleteFile(file: AlpineFile) {
        viewModelScope.launch(Dispatchers.IO) {
            fileManager.deleteFile(file.path)
            refreshFiles()
        }
    }
}
