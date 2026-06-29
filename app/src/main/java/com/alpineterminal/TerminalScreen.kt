package com.alpineterminal

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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

private val TerminalBg = Color(0xFF1A1A2E)
private val TerminalSurface = Color(0xFF16213E)
private val AccentBlue = Color(0xFF0F3460)
private val AccentCyan = Color(0xFF00D4FF)
private val KeyBg = Color(0xFF2A2A4A)
private val KeyActiveBg = Color(0xFF3A3A6A)

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    settingsManager: SettingsManager,
    voiceInputManager: VoiceInputManager
) {
    val lines by viewModel.screenLines
    val cursorRow by viewModel.cursorRow
    val cursorCol by viewModel.cursorCol
    val cursorVisible by viewModel.cursorVisible
    val fontSize by settingsManager.terminalFontSize.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBg)
    ) {
        TerminalToolbar(viewModel, context)
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
        ExtraKeysRow(viewModel)
    }
}

@Composable
private fun TerminalToolbar(viewModel: TerminalViewModel, context: android.content.Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalSurface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Axiom Terminal",
            color = AccentCyan,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val isWakeLocked by viewModel.isWakeLockAcquired
            IconButton(onClick = { viewModel.toggleWakeLock(context) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    contentDescription = "WakeLock",
                    tint = if (isWakeLocked) AccentCyan else Color.Gray,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = { viewModel.restartShell() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Restart Shell", tint = Color.Gray, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { viewModel.clearTerminal() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Clear", tint = Color.Gray, modifier = Modifier.size(18.dp))
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
    val showCursor by remember { mutableStateOf(true) }

    // Auto-scroll to bottom
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(lines) { index, line ->
                if (line.isEmpty) {
                    Spacer(modifier = Modifier.height(fontSize.sp.value.dp * 0.2f))
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 0.dp)
                    ) {
                        if (index == cursorRow && cursorVisible) {
                            val text = line.text
                            val beforeCursor = text.take(cursorCol)
                            val cursorChar = text.getOrElse(cursorCol) { ' ' }
                            val afterCursor = text.drop(cursorCol + 1)

                            if (beforeCursor.isNotEmpty()) {
                                androidx.compose.foundation.text.BasicText(
                                    text = if (line.segments.isNotEmpty()) {
                                        val croppedSegments = cropSegmentsToLength(line.segments, cursorCol)
                                        StyledLine(croppedSegments).toAnnotatedString()
                                    } else {
                                        androidx.compose.ui.text.AnnotatedString(beforeCursor)
                                    },
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
                                    .padding(0.dp)
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
    val context = LocalContext.current
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var historyIndex by remember { mutableStateOf(-1) }
    val activeSession = remember { viewModel.getActiveSession() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalSurface)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = inputText,
            onValueChange = {
                inputText = it
                historyIndex = -1
            },
            textStyle = TextStyle(
                color = Color(0xFFE0E0E0),
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp
            ),
            cursorBrush = SolidColor(AccentCyan),
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF0D1117), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp) {
                        when (event.key) {
                            Key.Enter -> {
                                val text = inputText.text.trim()
                                if (text.isNotEmpty()) {
                                    viewModel.sendCommand(text)
                                    inputText = TextFieldValue("")
                                    historyIndex = -1
                                } else {
                                    viewModel.sendEnter()
                                }
                                true
                            }
                            Key.DirectionUp -> {
                                val session = viewModel.getActiveSession()
                                val cmd = session?.navigateHistoryUp()
                                if (cmd != null) {
                                    inputText = TextFieldValue(cmd)
                                }
                                true
                            }
                            Key.DirectionDown -> {
                                val session = viewModel.getActiveSession()
                                val cmd = session?.navigateHistoryDown()
                                inputText = TextFieldValue(cmd ?: "")
                                true
                            }
                            else -> false
                        }
                    } else false
                },
            singleLine = true
        )

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
            Icon(Icons.Default.Mic, "Voice", tint = Color(0xFF8888AA), modifier = Modifier.size(18.dp))
        }

        FilledTonalButton(
            onClick = {
                val text = inputText.text.trim()
                if (text.isNotEmpty()) {
                    viewModel.sendCommand(text)
                    inputText = TextFieldValue("")
                    historyIndex = -1
                } else {
                    viewModel.sendEnter()
                }
            },
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = AccentBlue)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = AccentCyan, modifier = Modifier.size(16.dp))
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
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        KeyRow(
            keys = listOf(
                "ESC" to { viewModel.sendText("\u001b") },
                "TAB" to { viewModel.sendText("\t") },
                "|" to { viewModel.sendText("|") },
                "&" to { viewModel.sendText("&") },
                ";" to { viewModel.sendText(";") },
                "#" to { viewModel.sendText("#") },
                "~" to { viewModel.sendText("~") },
                "^" to { viewModel.sendText("^") },
                "\$" to { viewModel.sendText("\$") },
                "\\" to { viewModel.sendText("\\") }
            ),
            activeCtrl = ctrlActive,
            activeAlt = altActive
        )
        Spacer(modifier = Modifier.height(2.dp))
        KeyRow(
            keys = listOf(
                "CTRL" to { ctrlActive = !ctrlActive },
                "ALT" to { altActive = !altActive },
                "-" to { viewModel.sendText("-") },
                "_" to { viewModel.sendText("_") },
                "=" to { viewModel.sendText("=") },
                "+" to { viewModel.sendText("+") },
                "*" to { viewModel.sendText("*") },
                "/" to { viewModel.sendText("/") },
                "." to { viewModel.sendText(".") },
                "/" to { viewModel.sendText("/") }
            ),
            activeCtrl = ctrlActive,
            activeAlt = altActive
        )
        Spacer(modifier = Modifier.height(2.dp))
        KeyRow(
            keys = listOf(
                "(" to { viewModel.sendText("(") },
                ")" to { viewModel.sendText(")") },
                "{" to { viewModel.sendText("{") },
                "}" to { viewModel.sendText("}") },
                "[" to { viewModel.sendText("[") },
                "]" to { viewModel.sendText("]") },
                "'" to { viewModel.sendText("'") },
                "\"" to { viewModel.sendText("\"") },
                "`" to { viewModel.sendText("`") },
                "?" to { viewModel.sendText("?") }
            ),
            activeCtrl = ctrlActive,
            activeAlt = altActive,
            lastRow = true
        )
    }
}

@Composable
private fun KeyRow(
    keys: List<Pair<String, () -> Unit>>,
    activeCtrl: Boolean = false,
    activeAlt: Boolean = false,
    lastRow: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        keys.forEach { (label, onClick) ->
            KeyButton(
                label = label,
                isToggle = label == "CTRL" || label == "ALT",
                isActive = (label == "CTRL" && activeCtrl) || (label == "ALT" && activeAlt),
                onClick = {
                    if (label == "CTRL" || label == "ALT") {
                        onClick()
                    } else {
                        if (activeCtrl && label.length == 1 && label[0] in 'A'..'Z') {
                            viewModel.sendControl(label[0] - 'A' + 1)
                        } else if (activeAlt && label.length == 1) {
                            viewModel.sendText("\u001b${label}")
                        } else {
                            onClick()
                        }
                        // Auto-release modifier keys after sending
                        // (ctrlActive/altActive toggled manually)
                    }
                }
            )
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
    val textColor = if (isActive) AccentCyan else Color(0xFFCCCCDD)
    val shape = RoundedCornerShape(4.dp)

    Box(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = if (label.length > 2) 6.dp else 8.dp, vertical = 4.dp),
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
