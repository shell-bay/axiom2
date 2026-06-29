package com.alpineterminal

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val TerminalBg = Color(0xFF0D1117)
private val TerminalSurface = Color(0xFF161B22)
private val AccentBlue = Color(0xFF1F6FEB)
private val AccentGreen = Color(0xFF3FB950)
private val AccentOrange = Color(0xFFD29922)
private val AccentRed = Color(0xFFF85149)
private val AccentCyan = Color(0xFF58A6FF)
private val KeyBg = Color(0xFF21262D)
private val KeyActiveBg = Color(0xFF30363D)
private val TextDim = Color(0xFF8B949E)
private val TextMain = Color(0xFFE6EDF3)

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    settingsManager: SettingsManager,
    voiceInputManager: VoiceInputManager
) {
    val needsSetup by viewModel.needsSetup
    val setupState by viewModel.setupState
    val setupProgress by viewModel.setupProgress
    val setupMessage by viewModel.setupMessage
    val isShellRunning by viewModel.isShellRunning

    if (needsSetup || setupState == LinuxEnvironmentManager.SetupState.ERROR) {
        SetupScreen(
            setupState = setupState,
            setupProgress = setupProgress,
            setupMessage = setupMessage,
            onStartSetup = { viewModel.startSetup() },
            onReset = { viewModel.resetEnvironment() }
        )
    } else {
        TerminalSessionScreen(
            viewModel = viewModel,
            settingsManager = settingsManager,
            voiceInputManager = voiceInputManager,
            isConnected = isShellRunning
        )
    }
}

@Composable
private fun SetupScreen(
    setupState: LinuxEnvironmentManager.SetupState,
    setupProgress: Float,
    setupMessage: String,
    onStartSetup: () -> Unit,
    onReset: () -> Unit
) {
    val isWorking = setupState in listOf(
        LinuxEnvironmentManager.SetupState.DOWNLOADING_ROOTFS,
        LinuxEnvironmentManager.SetupState.EXTRACTING_ROOTFS,
        LinuxEnvironmentManager.SetupState.DOWNLOADING_PROOT,
        LinuxEnvironmentManager.SetupState.CONFIGURING_ENV
    )

    Box(
        modifier = Modifier.fillMaxSize().background(TerminalBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.Terminal,
                contentDescription = null,
                tint = AccentGreen,
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Axiom Alpine",
                color = TextMain,
                fontSize = 28.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Terminal Emulator",
                color = TextDim,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = TerminalSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (setupState) {
                        LinuxEnvironmentManager.SetupState.IDLE -> {
                            Text(
                                text = "Alpine Linux Environment",
                                color = TextMain,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "This will download and configure a minimal Alpine Linux root filesystem (~5MB) for your terminal.",
                                color = TextDim,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = onStartSetup,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Download & Install")
                            }
                        }

                        LinuxEnvironmentManager.SetupState.ERROR -> {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = AccentRed,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Setup Failed",
                                color = AccentRed,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = setupMessage,
                                color = TextDim,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onStartSetup,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Retry Setup")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onReset,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)
                            ) {
                                Text("Reset Environment")
                            }
                        }

                        else -> {
                            val icon = when (setupState) {
                                LinuxEnvironmentManager.SetupState.DOWNLOADING_ROOTFS -> Icons.Default.CloudDownload
                                LinuxEnvironmentManager.SetupState.EXTRACTING_ROOTFS -> Icons.Default.Unarchive
                                LinuxEnvironmentManager.SetupState.DOWNLOADING_PROOT -> Icons.Default.Build
                                LinuxEnvironmentManager.SetupState.CONFIGURING_ENV -> Icons.Default.Tune
                                LinuxEnvironmentManager.SetupState.READY -> Icons.Default.CheckCircle
                                else -> Icons.Default.HourglassEmpty
                            }

                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (setupState == LinuxEnvironmentManager.SetupState.READY) AccentGreen else AccentCyan,
                                modifier = Modifier.size(40.dp)
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = setupMessage,
                                color = TextMain,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            LinearProgressIndicator(
                                progress = { setupProgress },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = AccentGreen,
                                trackColor = Color(0xFF21262D)
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${(setupProgress * 100).toInt()}%",
                                color = TextDim,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalSessionScreen(
    viewModel: TerminalViewModel,
    settingsManager: SettingsManager,
    voiceInputManager: VoiceInputManager,
    isConnected: Boolean
) {
    val lines by viewModel.screenLines
    val cursorRow by viewModel.cursorRow
    val cursorCol by viewModel.cursorCol
    val cursorVisible by viewModel.cursorVisible
    val fontSize by settingsManager.terminalFontSize.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().background(TerminalBg)
    ) {
        TerminalToolbar(viewModel = viewModel, isConnected = isConnected)
        TerminalOutputArea(
            lines = lines,
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            cursorVisible = cursorVisible,
            fontSize = fontSize,
            modifier = Modifier.weight(1f)
        )
        TerminalInputArea(
            viewModel = viewModel,
            voiceInputManager = voiceInputManager,
            fontSize = fontSize
        )
        ExtraKeysRow(viewModel = viewModel)
    }
}

@Composable
private fun TerminalToolbar(
    viewModel: TerminalViewModel,
    isConnected: Boolean
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalSurface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) AccentGreen else AccentRed)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Axiom",
                color = TextMain,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = " | Alpine",
                color = AccentCyan,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            val isWakeLocked by viewModel.isWakeLockAcquired
            IconButton(onClick = { viewModel.toggleWakeLock(context) }, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    contentDescription = "WakeLock",
                    tint = if (isWakeLocked) AccentOrange else TextDim,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = { viewModel.restartShell() }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Restart", tint = TextDim, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { viewModel.clearTerminal() }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Clear", tint = TextDim, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun TerminalOutputArea(
    lines: List<StyledLine>,
    cursorRow: Int,
    cursorCol: Int,
    cursorVisible: Boolean,
    fontSize: Int,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(lines) { index, line ->
                if (line.isEmpty) {
                    Spacer(modifier = Modifier.height(2.dp))
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 0.dp)
                    ) {
                        if (index == cursorRow && cursorVisible) {
                            val text = line.text
                            val beforeCursor = text.take(cursorCol)
                            val cursorChar = text.getOrElse(cursorCol) { ' ' }
                            val afterCursor = text.drop(cursorCol + 1)

                            if (beforeCursor.isNotEmpty()) {
                                val croppedSegments = cropSegmentsToLength(line.segments, cursorCol)
                                androidx.compose.foundation.text.BasicText(
                                    text = StyledLine(croppedSegments).toAnnotatedString(),
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = fontSize.sp,
                                        lineHeight = (fontSize + 4).sp
                                    )
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .width((fontSize * 0.6).sp.value.dp)
                                    .background(Color.White)
                            ) {
                                Text(
                                    text = if (cursorChar == ' ') "\u00A0" else cursorChar.toString(),
                                    color = TerminalBg,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = fontSize.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = (fontSize + 4).sp
                                )
                            }

                            if (afterCursor.isNotEmpty()) {
                                val remainingSegments = cropSegmentsFrom(line.segments, cursorCol + 1)
                                androidx.compose.foundation.text.BasicText(
                                    text = StyledLine(remainingSegments).toAnnotatedString(),
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = fontSize.sp,
                                        lineHeight = (fontSize + 4).sp
                                    )
                                )
                            }
                        } else {
                            androidx.compose.foundation.text.BasicText(
                                text = line.toAnnotatedString(),
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = fontSize.sp,
                                    lineHeight = (fontSize + 4).sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun cropSegmentsToLength(segments: List<StyledSegment>, maxLen: Int): List<StyledSegment> {
    val result = mutableListOf<StyledSegment>()
    var remaining = maxLen
    for (seg in segments) {
        if (remaining <= 0) break
        if (seg.text.length <= remaining) {
            result.add(seg)
            remaining -= seg.text.length
        } else {
            result.add(seg.copy(text = seg.text.take(remaining)))
            break
        }
    }
    return result
}

private fun cropSegmentsFrom(segments: List<StyledSegment>, start: Int): List<StyledSegment> {
    val result = mutableListOf<StyledSegment>()
    var offset = 0
    var started = false
    for (seg in segments) {
        val segEnd = offset + seg.text.length
        if (!started && segEnd > start) {
            val cropStart = start - offset
            result.add(seg.copy(text = seg.text.drop(cropStart)))
            started = true
        } else if (started) {
            result.add(seg)
        }
        offset = segEnd
    }
    return result
}

@Composable
private fun TerminalInputArea(
    viewModel: TerminalViewModel,
    voiceInputManager: VoiceInputManager,
    fontSize: Int
) {
    var inputText by remember { mutableStateOf(TextFieldValue("")) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalSurface)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF0D1117), RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF21262D), RoundedCornerShape(4.dp))
        ) {
            BasicTextField(
                value = inputText,
                onValueChange = {
                    inputText = it
                },
                textStyle = TextStyle(
                    color = TextMain,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp
                ),
                cursorBrush = SolidColor(AccentCyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp) {
                            when (event.key) {
                                Key.Enter -> {
                                    val text = inputText.text.trim()
                                    if (text.isNotEmpty()) {
                                        viewModel.sendCommand(text)
                                    } else {
                                        viewModel.sendEnter()
                                    }
                                    inputText = TextFieldValue("")
                                    true
                                }
                                Key.DirectionUp -> {
                                    val cmd = viewModel.getActiveSession()?.navigateHistoryUp()
                                    if (cmd != null) inputText = TextFieldValue(cmd)
                                    true
                                }
                                Key.DirectionDown -> {
                                    val cmd = viewModel.getActiveSession()?.navigateHistoryDown()
                                    inputText = TextFieldValue(cmd ?: "")
                                    true
                                }
                                else -> false
                            }
                        } else false
                    },
                singleLine = true,
                decorationBox = { innerTextField ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "$ ",
                            color = AccentGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            fontWeight = FontWeight.Bold
                        )
                        innerTextField()
                    }
                }
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        IconButton(
            onClick = {
                voiceInputManager.startListening(
                    onResult = { result -> inputText = TextFieldValue(result) },
                    onError = { /* silent */ }
                )
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Default.Mic, "Voice", tint = TextDim, modifier = Modifier.size(18.dp))
        }

        FilledTonalButton(
            onClick = {
                val text = inputText.text.trim()
                if (text.isNotEmpty()) {
                    viewModel.sendCommand(text)
                } else {
                    viewModel.sendEnter()
                }
                inputText = TextFieldValue("")
            },
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = AccentBlue)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ExtraKeysRow(viewModel: TerminalViewModel) {
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalSurface)
            .padding(horizontal = 4.dp, vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf(
                "ESC" to { viewModel.sendText("\u001b") },
                "TAB" to { viewModel.sendText("\t") },
                "|" to { viewModel.sendText("|") },
                "&" to { viewModel.sendText("&") },
                ";" to { viewModel.sendText(";") },
                "#" to { viewModel.sendText("#") },
                "~" to { viewModel.sendText("~") },
                "\$" to { viewModel.sendText("\$") },
                "\\" to { viewModel.sendText("\\") }
            ).forEach { (label, onClick) ->
                KeyButton(label = label, isActive = false, onClick = onClick)
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf(
                "CTRL" to { ctrlActive = !ctrlActive },
                "ALT" to { altActive = !altActive },
                "-" to { viewModel.sendText("-") },
                "_" to { viewModel.sendText("_") },
                "=" to { viewModel.sendText("=") },
                "+" to { viewModel.sendText("+") },
                "*" to { viewModel.sendText("*") },
                "." to { viewModel.sendText(".") },
                "?" to { viewModel.sendText("?") }
            ).forEach { (label, onClick) ->
                val isActive = (label == "CTRL" && ctrlActive) || (label == "ALT" && altActive)
                KeyButton(
                    label = label,
                    isToggle = label == "CTRL" || label == "ALT",
                    isActive = isActive,
                    onClick = {
                        if (label == "CTRL" || label == "ALT") {
                            onClick()
                        } else {
                            if (ctrlActive && label.length == 1 && label[0] in 'A'..'Z') {
                                viewModel.sendControl(label[0] - 'A' + 1)
                            } else if (altActive && label.length == 1) {
                                viewModel.sendText("\u001b${label}")
                            } else {
                                onClick()
                            }
                        }
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf(
                "(" to { viewModel.sendText("(") },
                ")" to { viewModel.sendText(")") },
                "{" to { viewModel.sendText("{") },
                "}" to { viewModel.sendText("}") },
                "[" to { viewModel.sendText("[") },
                "]" to { viewModel.sendText("]") },
                "'" to { viewModel.sendText("'") },
                "\"" to { viewModel.sendText("\"") },
                "`" to { viewModel.sendText("`") }
            ).forEach { (label, onClick) ->
                KeyButton(label = label, isActive = false, onClick = onClick)
            }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    isToggle: Boolean = false,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    val bg = if (isActive) KeyActiveBg else KeyBg
    val textColor = if (isActive) AccentCyan else TextDim
    val shape = RoundedCornerShape(4.dp)

    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = if (label.length > 2) 6.dp else 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
        )
    }
}
