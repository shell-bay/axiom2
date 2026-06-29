package com.alpineterminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.isActive
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    if (needsSetup || setupState == LinuxEnvironmentManager.SetupState.ERROR) {
        SetupScreen(setupState, setupProgress, setupMessage, { viewModel.startSetup() }, { viewModel.resetEnvironment() })
    } else {
        TerminalSessionScreen(viewModel, settingsManager, voiceInputManager)
    }
}

@Composable
private fun SetupScreen(
    setupState: LinuxEnvironmentManager.SetupState, setupProgress: Float, setupMessage: String,
    onStartSetup: () -> Unit, onReset: () -> Unit
) {
    val isWorking = setupState in listOf(
        LinuxEnvironmentManager.SetupState.DOWNLOADING_ROOTFS, LinuxEnvironmentManager.SetupState.EXTRACTING_ROOTFS,
        LinuxEnvironmentManager.SetupState.DOWNLOADING_PROOT, LinuxEnvironmentManager.SetupState.CONFIGURING_ENV
    )
    Box(modifier = Modifier.fillMaxSize().background(TerminalBg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.Terminal, null, tint = AccentGreen, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(24.dp))
            Text("Axiom Alpine", color = TextMain, fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("Terminal Emulator", color = TextDim, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(32.dp))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = TerminalSurface), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (setupState) {
                        LinuxEnvironmentManager.SetupState.IDLE -> {
                            Text("Alpine Linux Environment", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Download and configure a minimal Alpine Linux root filesystem (~5MB).", color = TextDim, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = onStartSetup, colors = ButtonDefaults.buttonColors(containerColor = AccentGreen), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                                Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(8.dp)); Text("Download & Install")
                            }
                        }
                        LinuxEnvironmentManager.SetupState.ERROR -> {
                            Icon(Icons.Default.Warning, null, tint = AccentRed, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Setup Failed", color = AccentRed, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(setupMessage, color = TextDim, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = onStartSetup, colors = ButtonDefaults.buttonColors(containerColor = AccentBlue), modifier = Modifier.fillMaxWidth()) { Text("Retry Setup") }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed)) { Text("Reset Environment") }
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
                            Icon(icon, null, tint = if (setupState == LinuxEnvironmentManager.SetupState.READY) AccentGreen else AccentCyan, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(setupMessage, color = TextMain, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(progress = setupProgress, modifier = Modifier.fillMaxWidth().height(6.dp), color = AccentGreen, trackColor = Color(0xFF21262D))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("${(setupProgress * 100).toInt()}%", color = TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalSessionScreen(viewModel: TerminalViewModel, settingsManager: SettingsManager, voiceInputManager: VoiceInputManager) {
    val lines by viewModel.screenLines
    val cursorRow by viewModel.cursorRow; val cursorCol by viewModel.cursorCol
    val cursorVisible by viewModel.cursorVisible
    val fontSize by settingsManager.terminalFontSize.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(TerminalBg)) {
        TerminalToolbar(viewModel, viewModel.isShellRunning.value)
        TerminalOutputArea(lines, cursorRow, cursorCol, cursorVisible, fontSize,
            onZoom = { delta -> settingsManager.adjustFontSize(delta) },
            onCopyPlain = { viewModel.getPlainTerminalText() },
            modifier = Modifier.weight(1f))
        TerminalInputArea(viewModel, voiceInputManager, fontSize)
        ExtraKeysRow(viewModel)
    }
}

@Composable
private fun TerminalToolbar(viewModel: TerminalViewModel, isConnected: Boolean) {
    val context = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth().background(TerminalSurface).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (isConnected) AccentGreen else AccentRed))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Axiom", color = TextMain, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(" | Alpine", color = AccentCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.toggleWakeLock(context) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.PowerSettingsNew, null,
                    tint = if (viewModel.isWakeLockAcquired.value) AccentOrange else TextDim, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { viewModel.restartShell() }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Refresh, null, tint = TextDim, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { viewModel.clearTerminal() }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, null, tint = TextDim, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun TerminalOutputArea(
    lines: List<StyledLine>, cursorRow: Int, cursorCol: Int, cursorVisible: Boolean,
    fontSize: Int, onZoom: (Int) -> Unit, onCopyPlain: () -> String, modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var showCopyMenu by remember { mutableStateOf(false) }
    val blinkAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        while (isActive) {
            blinkAlpha.animateTo(0f, animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse))
            blinkAlpha.animateTo(1f, animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse))
        }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Box(modifier = modifier.fillMaxWidth()
        .pointerInput(Unit) {
            detectTransformGestures { _, _, zoom, _ ->
                if (zoom != 1f) onZoom(if (zoom > 1f) 1 else -1)
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onLongPress = { showCopyMenu = true }
            )
        }
    ) {
        if (showCopyMenu) {
            Card(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).widthIn(min = 120.dp),
                colors = CardDefaults.cardColors(containerColor = TerminalSurface),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column {
                    TextButton(onClick = {
                        val text = onCopyPlain()
                        if (text.isNotBlank()) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Terminal", text))
                            Toast.makeText(context, "Copied terminal output", Toast.LENGTH_SHORT).show()
                        }
                        showCopyMenu = false
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp), tint = TextDim)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy All", color = TextMain, fontSize = 13.sp)
                    }
                    TextButton(onClick = { showCopyMenu = false }, modifier = Modifier.fillMaxWidth()) {
                        Text("Dismiss", color = TextDim, fontSize = 13.sp)
                    }
                }
            }
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
            itemsIndexed(lines) { idx, line ->
                if (line.isEmpty) { Spacer(modifier = Modifier.height(2.dp)) }
                else {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 0.dp)) {
                        val isCursorLine = idx == cursorRow && cursorVisible
                        if (isCursorLine) renderCursorLine(line, cursorCol, fontSize, blinkAlpha.value)
                        else {
                            androidx.compose.foundation.text.BasicText(
                                text = line.toAnnotatedString(),
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize + 4).sp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun renderCursorLine(line: StyledLine, cursorCol: Int, fontSize: Int, blinkAlpha: Float) {
    val text = line.text
    val before = text.take(cursorCol)
    val cursorChar = text.getOrElse(cursorCol) { ' ' }
    val after = text.drop(cursorCol + 1)
    if (before.isNotEmpty()) {
        androidx.compose.foundation.text.BasicText(
            text = StyledLine(cropSegmentsToLength(line.segments, cursorCol)).toAnnotatedString(),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize + 4).sp)
        )
    }
    Box(modifier = Modifier
        .width((fontSize * 0.6).sp.value.dp)
        .background(Color.White.copy(alpha = blinkAlpha))
    ) {
        Text(if (cursorChar == ' ') "\u00A0" else cursorChar.toString(),
            color = TerminalBg, fontFamily = FontFamily.Monospace, fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold, lineHeight = (fontSize + 4).sp)
    }
    if (after.isNotEmpty()) {
        androidx.compose.foundation.text.BasicText(
            text = StyledLine(cropSegmentsFrom(line.segments, cursorCol + 1)).toAnnotatedString(),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize + 4).sp)
        )
    }
}

private fun cropSegmentsToLength(segments: List<StyledSegment>, maxLen: Int): List<StyledSegment> {
    val r = mutableListOf<StyledSegment>(); var rem = maxLen
    for (s in segments) { if (rem <= 0) break
        if (s.text.length <= rem) { r.add(s); rem -= s.text.length }
        else { r.add(s.copy(text = s.text.take(rem))); break } }
    return r
}

private fun cropSegmentsFrom(segments: List<StyledSegment>, start: Int): List<StyledSegment> {
    val r = mutableListOf<StyledSegment>(); var off = 0; var started = false
    for (s in segments) { val e = off + s.text.length
        if (!started && e > start) { r.add(s.copy(text = s.text.drop(start - off))); started = true }
        else if (started) r.add(s)
        off = e }
    return r
}

@Composable
private fun TerminalInputArea(viewModel: TerminalViewModel, voiceInputManager: VoiceInputManager, fontSize: Int) {
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    Row(modifier = Modifier.fillMaxWidth().background(TerminalSurface).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f).background(Color(0xFF0D1117), RoundedCornerShape(4.dp)).border(1.dp, Color(0xFF21262D), RoundedCornerShape(4.dp))) {
            BasicTextField(
                value = inputText, onValueChange = { inputText = it },
                textStyle = TextStyle(color = TextMain, fontFamily = FontFamily.Monospace, fontSize = fontSize.sp),
                cursorBrush = SolidColor(AccentCyan),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp) when (event.key) {
                        Key.Enter -> { val t = inputText.text.trim(); if (t.isNotEmpty()) viewModel.sendCommand(t) else viewModel.sendEnter(); inputText = TextFieldValue(""); true }
                        Key.DirectionUp -> { val cmd = viewModel.getActiveSession()?.navigateHistoryUp(); if (cmd != null) inputText = TextFieldValue(cmd); true }
                        Key.DirectionDown -> { val cmd = viewModel.getActiveSession()?.navigateHistoryDown(); inputText = TextFieldValue(cmd ?: ""); true }
                        else -> false
                    } else false
                },
                singleLine = true,
                decorationBox = { inner -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$ ", color = AccentGreen, fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
                    inner()
                }}
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = { voiceInputManager.startListening(onResult = { r -> inputText = TextFieldValue(r) }, onError = {}) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Mic, "Voice", tint = TextDim, modifier = Modifier.size(18.dp))
        }
        FilledTonalButton(onClick = {
            val t = inputText.text.trim(); if (t.isNotEmpty()) viewModel.sendCommand(t) else viewModel.sendEnter(); inputText = TextFieldValue("")
        }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = AccentBlue)) {
            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ExtraKeysRow(viewModel: TerminalViewModel) {
    var ctrl by remember { mutableStateOf(false) }; var alt by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().background(TerminalSurface).padding(horizontal = 4.dp, vertical = 3.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("ESC" to { viewModel.sendText("\u001b") }, "TAB" to { viewModel.sendText("\t") },
                "|" to { viewModel.sendText("|") }, "&" to { viewModel.sendText("&") },
                ";" to { viewModel.sendText(";") }, "#" to { viewModel.sendText("#") },
                "~" to { viewModel.sendText("~") }, "\$" to { viewModel.sendText("\$") }, "\\" to { viewModel.sendText("\\") })
                .forEach { (l, a) -> KeyButton(l, false, false) { a() } }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(
                "CTRL" to { ctrl = !ctrl }, "ALT" to { alt = !alt },
                "-" to { viewModel.sendText("-") }, "_" to { viewModel.sendText("_") },
                "=" to { viewModel.sendText("=") }, "+" to { viewModel.sendText("+") },
                "*" to { viewModel.sendText("*") }, "." to { viewModel.sendText(".") }, "?" to { viewModel.sendText("?") }
            ).forEach { (l, a) ->
                val isMod = l == "CTRL" || l == "ALT"
                val isActive = (l == "CTRL" && ctrl) || (l == "ALT" && alt)
                KeyButton(l, isMod, isActive, ctrl, alt) {
                    if (isMod) a()
                    else {
                        val char = l.singleOrNull()
                        if (ctrl && char != null && char in 'A'..'Z') viewModel.sendControl(char - 'A' + 1)
                        else if (alt && char != null) viewModel.sendText("\u001b$char")
                        else a()
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("(" to { viewModel.sendText("(") }, ")" to { viewModel.sendText(")") },
                "{" to { viewModel.sendText("{") }, "}" to { viewModel.sendText("}") },
                "[" to { viewModel.sendText("[") }, "]" to { viewModel.sendText("]") },
                "'" to { viewModel.sendText("'") }, "\"" to { viewModel.sendText("\"") }, "`" to { viewModel.sendText("`") })
                .forEach { (l, a) -> KeyButton(l, false, false) { a() } }
        }
    }
}

@Composable
private fun KeyButton(label: String, isToggle: Boolean = false, isActive: Boolean = false, ctrl: Boolean = false, alt: Boolean = false, onClick: () -> Unit) {
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (isActive) KeyActiveBg else KeyBg)
        .clickable(onClick = onClick).padding(horizontal = if (label.length > 2) 6.dp else 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (isActive) AccentCyan else TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
    }
}
