package com.alpineterminal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat

enum class AppScreen {
    TERMINAL, FILES, PACKAGES, SSH, SETTINGS, EDITOR
}

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        val envManager = LinuxEnvironmentManager(this)
        val fileResourceManager = FileResourceManager(this)
        val settingsManager = SettingsManager(this)
        val voiceInputManager = VoiceInputManager(this)
        val notificationHelper = NotificationHelper(this)

        val terminalViewModel = TerminalViewModel(envManager)
        terminalViewModel.setContext(this)
        val sshViewModel = SshViewModel(settingsManager)
        val fileViewModel = FileViewModel(fileResourceManager)
        val packageViewModel = PackageInstallerViewModel(envManager, notificationHelper)
        val gitHubManager = GitHubManager(this, settingsManager)
        val gitHubViewModel = GitHubViewModel(this.application, gitHubManager, notificationHelper)

        setContent {
            val themeMode by settingsManager.themeMode.collectAsState()
            val isDark = themeMode == SettingsManager.ThemeMode.DARK || themeMode == SettingsManager.ThemeMode.SYSTEM

            MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
                var currentScreen by remember { mutableStateOf(AppScreen.TERMINAL) }
                var selectedFile by remember { mutableStateOf<AlpineFile?>(null) }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (currentScreen == AppScreen.EDITOR && selectedFile != null) {
                        TextEditorScreen(
                            file = selectedFile!!,
                            fileManager = fileResourceManager,
                            onSave = { fileViewModel.refreshFiles() },
                            onBack = { currentScreen = AppScreen.FILES }
                        )
                    } else {
                        Scaffold(
                            bottomBar = {
                                NavigationBar {
                                    NavigationBarItem(
                                        selected = currentScreen == AppScreen.TERMINAL,
                                        onClick = { currentScreen = AppScreen.TERMINAL },
                                        label = { Text("Terminal") },
                                        icon = { Icon(Icons.Default.Terminal, null) }
                                    )
                                    NavigationBarItem(
                                        selected = currentScreen == AppScreen.SSH,
                                        onClick = { currentScreen = AppScreen.SSH },
                                        label = { Text("SSH") },
                                        icon = { Icon(Icons.Default.Lan, null) }
                                    )
                                    NavigationBarItem(
                                        selected = currentScreen == AppScreen.FILES,
                                        onClick = { currentScreen = AppScreen.FILES },
                                        label = { Text("Files") },
                                        icon = { Icon(Icons.Default.Folder, null) }
                                    )
                                    NavigationBarItem(
                                        selected = currentScreen == AppScreen.PACKAGES,
                                        onClick = { currentScreen = AppScreen.PACKAGES },
                                        label = { Text("Packages") },
                                        icon = { Icon(Icons.Default.List, null) }
                                    )
                                    NavigationBarItem(
                                        selected = currentScreen == AppScreen.SETTINGS,
                                        onClick = { currentScreen = AppScreen.SETTINGS },
                                        label = { Text("Settings") },
                                        icon = { Icon(Icons.Default.Settings, null) }
                                    )
                                }
                            }
                        ) { padding ->
                            Box(modifier = Modifier.padding(padding).imePadding()) {
                                when (currentScreen) {
                                    AppScreen.TERMINAL -> TerminalScreen(terminalViewModel, settingsManager, voiceInputManager)
                                    AppScreen.SSH -> SshScreen(sshViewModel)
                                    AppScreen.FILES -> FileBrowserScreen(
                                        viewModel = fileViewModel,
                                        gitHubViewModel = gitHubViewModel,
                                        onFileClick = { file -> selectedFile = file; currentScreen = AppScreen.EDITOR }
                                    )
                                    AppScreen.PACKAGES -> PackageInstallerScreen(viewModel = packageViewModel)
                                    AppScreen.SETTINGS -> SettingsScreen(settingsManager, terminalViewModel)
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (permissions.isNotEmpty()) requestPermissionLauncher.launch(permissions.toTypedArray())
    }
}
