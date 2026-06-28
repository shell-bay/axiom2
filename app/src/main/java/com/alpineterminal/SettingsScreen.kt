package com.alpineterminal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(settingsManager: SettingsManager) {
    val themeMode by settingsManager.themeMode.collectAsState()
    val fontSize by settingsManager.terminalFontSize.collectAsState()
    val currentGithubToken by settingsManager.githubToken.collectAsState()
    var githubToken by remember { mutableStateOf(currentGithubToken) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Appearance", style = MaterialTheme.typography.titleLarge)
                Divider()
                
                Text("Theme Mode")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsManager.ThemeMode.values().forEach { mode ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = { settingsManager.setThemeMode(mode) }
                            )
                            Text(mode.name)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
            }

            item {
                Text("Terminal", style = MaterialTheme.typography.titleLarge)
                Divider()
                
                Text("Font Size: ${fontSize}sp")
                Slider(
                    value = fontSize.toFloat(),
                    onValueChange = { settingsManager.setFontSize(it.toInt()) },
                    valueRange = 10f..24f,
                    steps = 14
                )
            }

            item {
                Text("Integrations", style = MaterialTheme.typography.titleLarge)
                Divider()
                
                OutlinedTextField(
                    value = githubToken,
                    onValueChange = { githubToken = it },
                    label = { Text("GitHub PAT") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { settingsManager.setGithubToken(githubToken) }) {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                    }
                )
            }
        }
    }
}
