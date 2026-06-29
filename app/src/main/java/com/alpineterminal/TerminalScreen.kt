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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    settingsManager: SettingsManager
) {
    val setupState by viewModel.setupState
    val setupProgress by viewModel.setupProgress
    val setupMessage by viewModel.setupMessage

    when {
        setupState == LinuxEnvironmentManager.SetupState.ERROR -> {
            SetupErrorScreen(setupMessage, { viewModel.resetEnvironment() })
        }
        setupState != LinuxEnvironmentManager.SetupState.READY -> {
            LoadingScreen(setupState, setupProgress, setupMessage)
        }
        else -> {
            TerminalSessionScreen(viewModel, settingsManager)
        }
    }
}

@Composable
private fun LoadingScreen(
    setupState: LinuxEnvironmentManager.SetupState, setupProgress: Float, setupMessage: String
) {
    val phase = remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        phase.value = 1f
    }
    Box(modifier = Modifier.fillMaxSize().background(TerminalBg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("Axiom", color = AccentGreen, fontSize = 36.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("Terminal", color = TextDim, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(48.dp))
            if (setupState == LinuxEnvironmentManager.SetupState.EXTRACTING_ROOTFS || setupState == LinuxEnvironmentManager.SetupState.CONFIGURING_ENV) {
                LinearProgressIndicator(progress = setupProgress, modifier = Modifier.width(200.dp).height(4.dp), color = AccentGreen, trackColor = Color(0xFF21262D))
                Spacer(modifier = Modifier.height(12.dp))
                Text(setupMessage, color = TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            } else {
                CircularProgressIndicator(color = AccentGreen, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            }
        }
    }
}

@Composable
private fun SetupErrorScreen(message: String, onReset: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(TerminalBg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.Warning, null, tint = AccentRed, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("Setup Failed", color = AccentRed, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(message, color = TextDim, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onReset, colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)) { Text("Retry") }
        }
    }
}

@Composable
private fun TerminalSessionScreen(viewModel: TerminalViewModel, settingsManager: SettingsManager) {
    val lines by viewModel.screenLines
    val cursorRow by viewModel.cursorRow; val cursorCol by viewModel.cursorCol
    val cursorVisible by viewModel.cursorVisible
    val fontSize by settingsManager.terminalFontSize.collectAsState()
    val focusRequester = remember { FocusRequester() }

    var ctrl by remember { mutableStateOf(false) }; var alt by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(TerminalBg)) {
        TerminalToolbar(viewModel, viewModel.isShellRunning.value)
        TerminalOutputArea(lines, cursorRow, cursorCol, cursorVisible, fontSize,
            onZoom = { delta -> settingsManager.adjustFontSize(delta) },
            onCopyPlain = { viewModel.getPlainTerminalText() },
            inputText = inputText,
            onInputChange = { inputText = it },
            onInputSubmit = {
                val cmd = inputText.trim()
                if (cmd.isNotEmpty()) viewModel.sendCommand(cmd) else viewModel.sendEnter()
                inputText = ""
            },
            focusRequester = focusRequester,
            modifier = Modifier.weight(1f))
        ExtraKeysRow(viewModel, ctrl, alt, { ctrl = it }, { alt = it })
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
    fontSize: Int, onZoom: (Int) -> Unit, onCopyPlain: () -> String,
    inputText: String, onInputChange: (String) -> Unit, onInputSubmit: () -> Unit,
    focusRequester: FocusRequester, modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var showCopyMenu by remember { mutableStateOf(false) }
    val blinkAlpha = remember { Animatable(1f) }

    LaunchedEffect(cursorVisible) {
        if (cursorVisible) {
            while (isActive) {
                blinkAlpha.animateTo(0f, animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse))
                blinkAlpha.animateTo(1f, animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse))
            }
        } else {
            blinkAlpha.snapTo(1f)
        }
    }

    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) true
            else info.visibleItemsInfo.lastOrNull()?.index ?: 0 >= info.totalItemsCount - 2
        }
    }

    LaunchedEffect(lines.size) {
        if (isAtBottom && lines.isNotEmpty()) {
            listState.scrollToItem(lines.size - 1)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(modifier = modifier.fillMaxWidth()
        .pointerInput(Unit) {
            detectTransformGestures { _, _, zoom, _ ->
                if (zoom != 1f) onZoom(if (zoom > 1f) 1 else -1)
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onLongPress = { showCopyMenu = true },
                onTap = { focusRequester.requestFocus() }
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

            item {
                InputLine(
                    inputText = inputText,
                    onInputChange = onInputChange,
                    onInputSubmit = onInputSubmit,
                    fontSize = fontSize,
                    focusRequester = focusRequester
                )
            }
        }
    }
}

@Composable
private fun InputLine(
    inputText: String, onInputChange: (String) -> Unit, onInputSubmit: () -> Unit,
    fontSize: Int, focusRequester: FocusRequester
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$ ", color = AccentGreen, fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
        BasicTextField(
            value = inputText,
            onValueChange = { newVal ->
                val newlineIdx = newVal.indexOf('\n')
                if (newlineIdx >= 0) {
                    val textBefore = newVal.substring(0, newlineIdx)
                    if (textBefore.isNotEmpty()) {
                        onInputChange(textBefore)
                    }
                    onInputSubmit()
                } else {
                    onInputChange(newVal)
                }
            },
            textStyle = TextStyle(color = TextMain, fontFamily = FontFamily.Monospace, fontSize = fontSize.sp),
            cursorBrush = SolidColor(AccentCyan),
            modifier = Modifier.weight(1f)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                        onInputSubmit()
                        true
                    } else false
                }
                .focusRequester(focusRequester),
            maxLines = 1
        )
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
private fun ExtraKeysRow(viewModel: TerminalViewModel, ctrl: Boolean, alt: Boolean, onCtrlChange: (Boolean) -> Unit, onAltChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().background(TerminalSurface).padding(horizontal = 4.dp, vertical = 3.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "ESC" to { viewModel.sendText("\u001b") },
                    "TAB" to { viewModel.sendText("\t") },
                    "CTRL" to { onCtrlChange(!ctrl) },
                    "ALT" to { onAltChange(!alt) }
                ).forEach { (l, a) ->
                    val isMod = l == "CTRL" || l == "ALT"
                    val isActive = (l == "CTRL" && ctrl) || (l == "ALT" && alt)
                    KeyButton(l, isMod, isActive) { if (isMod) a() else a() }
                }
            }
            if (ctrl || alt) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
                    Text("+ letter → ", color = AccentCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    listOf("C", "Z", "D", "L").forEach { ch ->
                        KeyButton(ch, false, false) {
                            if (ctrl) viewModel.sendControl(ch.first() - 'A' + 1)
                            else viewModel.sendText("\u001b${ch.lowercase()}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, isToggle: Boolean = false, isActive: Boolean = false, onClick: () -> Unit) {
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (isActive) KeyActiveBg else KeyBg)
        .clickable(onClick = onClick).padding(horizontal = if (label.length > 2) 6.dp else 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (isActive) AccentCyan else TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
    }
}
