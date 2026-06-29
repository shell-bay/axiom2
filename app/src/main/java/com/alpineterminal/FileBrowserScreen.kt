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
import androidx.compose.ui.platform.LocalContext
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
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }

    if (showCreateFileDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFileDialog = false },
            title = { Text("Create New File") },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text("File name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFileName.isNotBlank()) {
                        viewModel.createFile(newFileName)
                        newFileName = ""
                        showCreateFileDialog = false
                    }
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFileDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Files",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Search Bar
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { 
                    searchQuery = it
                    viewModel.setQuery(it)
                },
                modifier = Modifier.weight(1f),
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
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = { showCreateFileDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Create File")
            }
        }

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
    val context = LocalContext.current

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
                    if (!file.isDirectory) {
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                try {
                                    val fileManager = FileResourceManager(context)
                                    val exportedFile = fileManager.exportFileToAndroid(file.path, file.name)
                                    if (exportedFile != null && exportedFile.exists()) {
                                        val fileUri = androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "com.alpineterminal.fileprovider",
                                            exportedFile
                                        )
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = context.contentResolver.getType(fileUri) ?: "application/octet-stream"
                                            if (file.name.endsWith(".gif", ignoreCase = true)) {
                                                type = "image/gif"
                                            } else if (file.name.endsWith(".png", ignoreCase = true)) {
                                                type = "image/png"
                                            } else if (file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".jpeg", ignoreCase = true)) {
                                                type = "image/jpeg"
                                            }
                                            putExtra(android.content.Intent.EXTRA_STREAM, fileUri)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(intent, "Share File"))
                                    }
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Error sharing file: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
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
