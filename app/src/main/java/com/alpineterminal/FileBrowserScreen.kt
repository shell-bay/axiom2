package com.alpineterminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FileBrowserScreen(
    viewModel: FileViewModel,
    gitHubViewModel: GitHubViewModel,
    onFileClick: (AlpineFile) -> Unit
) {
    val files by viewModel.files.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Files",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { 
                searchQuery = it
                viewModel.setQuery(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search files...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { 
                        searchQuery = ""
                        viewModel.setQuery("")
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Navigation Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.navigateUp() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Up")
            }
            Text(
                text = if (currentPath.isEmpty()) "/root" else "/root/$currentPath",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // File List
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(files) { file ->
                FileItem(
                    file = file,
                    onClick = {
                        if (file.isDirectory) {
                            viewModel.navigateTo(file.path)
                        } else {
                            onFileClick(file)
                        }
                    },
                    onDelete = { viewModel.deleteFile(file) }
                )
            }
        }
    }
}

@Composable
fun FileItem(
    file: AlpineFile,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.clickable { onClick() },
        headlineContent = { Text(file.name) },
        supportingContent = { 
            Text(if (file.isDirectory) "Folder" else "${file.size / 1024} KB") 
        },
        leadingContent = {
            Icon(
                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                tint = if (file.isDirectory) Color(0xFFFFC107) else Color.Gray
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            onDelete()
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Download") },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        onClick = {
                            // Export implementation will be here
                            showMenu = false
                        }
                    )
                }
            }
        }
    )
}
