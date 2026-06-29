package com.alpineterminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    settingsManager: SettingsManager,
    voiceInputManager: VoiceInputManager
) {
    val output by viewModel.terminalOutput.collectAsState()
    val isInitializing by viewModel.isInitializing
    val progress by viewModel.progress
    val fontSize by settingsManager.terminalFontSize.collectAsState()
    val context = LocalContext.current

    if (isInitializing) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text("Installing Alpine Linux...", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(progress = progress, modifier = Modifier.width(200.dp))
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
                .padding(8.dp)
        ) {
            // Action buttons row (Copy & Clear)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Alpine Console",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace
                )
                Row {
                    IconButton(onClick = {
                        try {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Terminal Output", output)
                            clipboard.setPrimaryClip(clip)
                            android.widget.Toast.makeText(context, "Output copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Failed to copy output", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Output", tint = Color.LightGray)
                    }
                    IconButton(onClick = { viewModel.clearTerminal() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Terminal", tint = Color.LightGray)
                    }
                }
            }

            Divider(color = Color.DarkGray, thickness = 1.dp, modifier = Modifier.padding(bottom = 8.dp))

            // Terminal Output Area
            Box(modifier = Modifier.weight(1f)) {
                val lines = output.split("\n")
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            color = Color.Green,
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Command Input Area
            var inputText by remember { mutableStateOf("") }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "alpine$ ",
                    color = Color.Cyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp
                )
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = fontSize.sp),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(onClick = {
                    voiceInputManager.startListening(
                        onResult = { result -> inputText = result },
                        onError = { error -> /* Handle error, e.g., show toast */ }
                    )
                }) {
                    Icon(Icons.Default.Mic, contentDescription = "Voice Input", tint = Color.White)
                }
                Button(
                    onClick = {
                        viewModel.sendCommand(inputText)
                        inputText = ""
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Run", fontSize = 12.sp)
                }
            }
        }
    }
}
