package com.alpineterminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0D1117)
private val Surface = Color(0xFF161B22)
private val AccentGreen = Color(0xFF3FB950)
private val AccentBlue = Color(0xFF1F6FEB)
private val AccentOrange = Color(0xFFD29922)
private val AccentRed = Color(0xFFF85149)
private val TextMain = Color(0xFFE6EDF3)
private val TextDim = Color(0xFF8B949E)

@Composable
fun SettingsScreen(settingsManager: SettingsManager, terminalViewModel: TerminalViewModel? = null) {
    val themeMode by settingsManager.themeMode.collectAsState()
    val fontSize by settingsManager.terminalFontSize.collectAsState()
    val currentGithubToken by settingsManager.githubToken.collectAsState()
    var githubToken by remember { mutableStateOf(currentGithubToken) }
    var showResetDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = {
                Text("Reset Alpine Environment", fontWeight = FontWeight.Bold, color = TextMain)
            },
            text = {
                Text(
                    "This will delete the entire Alpine Linux installation, all files, and installed packages. The app will restart setup on next launch.",
                    color = TextDim
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        terminalViewModel?.resetEnvironment()
                        showResetDialog = false
                        android.widget.Toast.makeText(context, "Alpine environment reset", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentRed)
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel", color = TextDim)
                }
            },
            containerColor = Surface
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Bg).padding(16.dp)
    ) {
        Text(
            text = "Settings",
            color = TextMain,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Palette, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Appearance", color = TextMain, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Theme", color = TextDim, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SettingsManager.ThemeMode.values().forEach { mode ->
                                if (themeMode == mode) {
                                    Button(
                                        onClick = { settingsManager.setThemeMode(mode) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                                    ) {
                                        Text(mode.name, fontSize = 11.sp)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { settingsManager.setThemeMode(mode) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextDim)
                                    ) {
                                        Text(mode.name, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Terminal", color = TextMain, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Font Size: ${fontSize}sp", color = TextDim, fontSize = 13.sp)
                        Slider(
                            value = fontSize.toFloat(),
                            onValueChange = { settingsManager.setFontSize(it.toInt()) },
                            valueRange = 10f..24f,
                            steps = 14,
                            colors = SliderDefaults.colors(
                                thumbColor = AccentGreen,
                                activeTrackColor = AccentGreen
                            )
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Code, null, tint = AccentBlue, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("GitHub Integration", color = TextMain, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = githubToken,
                            onValueChange = { githubToken = it },
                            label = { Text("Personal Access Token") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentBlue,
                                cursorColor = AccentBlue,
                                focusedLabelColor = AccentBlue
                            ),
                            trailingIcon = {
                                IconButton(onClick = {
                                    settingsManager.setGithubToken(githubToken)
                                }) {
                                    Icon(Icons.Default.Save, "Save", tint = AccentGreen)
                                }
                            }
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Storage, null, tint = AccentOrange, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Environment Info", color = TextMain, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        listOf(
                            "Distribution" to "Alpine Linux",
                            "Package Manager" to "apk",
                            "Shell" to "/bin/sh (BusyBox)"
                        ).forEach { (label, value) ->
                            Row {
                                Text("$label:", color = TextDim, fontSize = 12.sp, modifier = Modifier.width(120.dp))
                                Text(value, color = TextMain, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = AccentRed, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reset", color = AccentRed, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Resetting will delete the entire Alpine Linux environment and all your files inside it.",
                            color = TextDim,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        Button(
                            onClick = { showResetDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reset Alpine Environment")
                        }
                    }
                }
            }
        }
    }
}
