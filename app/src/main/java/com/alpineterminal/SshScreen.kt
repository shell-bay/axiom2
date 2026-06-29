package com.alpineterminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0D1117)
private val Surface = Color(0xFF161B22)
private val AccentGreen = Color(0xFF3FB950)
private val AccentBlue = Color(0xFF1F6FEB)
private val AccentOrange = Color(0xFFD29922)
private val AccentRed = Color(0xFFF85149)
private val AccentCyan = Color(0xFF58A6FF)
private val TextMain = Color(0xFFE6EDF3)
private val TextDim = Color(0xFF8B949E)
private val KeyBg = Color(0xFF21262D)
private val KeyActiveBg = Color(0xFF30363D)

@Composable
fun SshScreen(viewModel: SshViewModel) {
    val screen by viewModel.screen
    when (screen) {
        SshViewModel.SshScreenState.CONNECTION_LIST -> SshConnectionList(viewModel)
        SshViewModel.SshScreenState.CONNECTION_FORM -> SshConnectionForm(viewModel)
        SshViewModel.SshScreenState.TERMINAL -> SshTerminalScreen(viewModel)
    }
}

@Composable
private fun SshConnectionList(viewModel: SshViewModel) {
    val connections by viewModel.connections
    val error by viewModel.error
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    if (showDeleteDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Connection", color = TextMain) },
            text = { Text("Remove this SSH connection?", color = TextDim) },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog?.let { viewModel.deleteConnection(it) }; showDeleteDialog = null }) {
                    Text("Delete", color = AccentRed)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = null }) { Text("Cancel", color = TextDim) } },
            containerColor = Surface
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SSH Connections", color = TextMain, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            FilledTonalButton(
                onClick = { viewModel.showNewConnection() },
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = AccentBlue)
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New", fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (connections.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Lan, null, tint = TextDim.copy(alpha = 0.4f), modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No SSH connections", color = TextDim, fontSize = 14.sp)
                    Text("Tap 'New' to add a server", color = TextDim.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(connections) { conn ->
                    SshConnectionCard(
                        conn = conn,
                        onConnect = { viewModel.connectTo(conn) },
                        onEdit = { viewModel.showEditConnection(conn) },
                        onDelete = { showDeleteDialog = conn.id }
                    )
                }
            }
        }
    }
}

@Composable
private fun SshConnectionCard(
    conn: SshConnection,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onConnect() },
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(AccentBlue.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Dns, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(conn.name.ifBlank { conn.host }, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("${conn.username}@${conn.host}:${conn.port}", color = TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, null, tint = TextDim, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, null, tint = AccentRed.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshConnectionForm(viewModel: SshViewModel) {
    Column(modifier = Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.showConnectionList() }) {
                Icon(Icons.Default.ArrowBack, null, tint = TextMain)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("SSH Connection", color = TextMain, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(20.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(
                    value = viewModel.formName.value, onValueChange = { viewModel.updateFormName(it) },
                    label = { Text("Display Name") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = sshFieldColors()
                )
            }
            item {
                OutlinedTextField(
                    value = viewModel.formHost.value, onValueChange = { viewModel.updateFormHost(it) },
                    label = { Text("Host") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = sshFieldColors()
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = viewModel.formPort.value, onValueChange = { viewModel.updateFormPort(it) },
                        label = { Text("Port") }, modifier = Modifier.weight(1f),
                        singleLine = true, colors = sshFieldColors()
                    )
                    OutlinedTextField(
                        value = viewModel.formUsername.value, onValueChange = { viewModel.updateFormUsername(it) },
                        label = { Text("Username") }, modifier = Modifier.weight(2f),
                        singleLine = true, colors = sshFieldColors()
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auth:", color = TextDim, fontSize = 13.sp, modifier = Modifier.width(60.dp))
                    FilterChip(
                        selected = viewModel.formAuthType.value == AuthType.PASSWORD,
                        onClick = { viewModel.updateFormAuthType(AuthType.PASSWORD) },
                        label = { Text("Password", fontSize = 12.sp) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = viewModel.formAuthType.value == AuthType.KEY,
                        onClick = { viewModel.updateFormAuthType(AuthType.KEY) },
                        label = { Text("Key", fontSize = 12.sp) }
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = viewModel.formPassword.value, onValueChange = { viewModel.updateFormPassword(it) },
                    label = { Text(if (viewModel.formAuthType.value == AuthType.PASSWORD) "Password" else "Private Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = viewModel.formAuthType.value == AuthType.PASSWORD,
                    visualTransformation = if (viewModel.formAuthType.value == AuthType.PASSWORD) PasswordVisualTransformation() else VisualTransformation.None,
                    minLines = if (viewModel.formAuthType.value == AuthType.KEY) 3 else 1,
                    colors = sshFieldColors()
                )
            }
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.saveConnection() },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    enabled = viewModel.formHost.value.isNotBlank() && viewModel.formUsername.value.isNotBlank()
                ) {
                    Text("Save Connection")
                }
            }
        }
    }
}

@Composable
private fun sshFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentBlue, cursorColor = AccentCyan,
    focusedLabelColor = AccentBlue, unfocusedTextColor = TextMain,
    focusedTextColor = TextMain, unfocusedLabelColor = TextDim
)

@Composable
private fun SshTerminalScreen(viewModel: SshViewModel) {
    val lines by viewModel.screenLines
    val cursorRow by viewModel.cursorRow; val cursorCol by viewModel.cursorCol
    val cursorVisible by viewModel.cursorVisible
    val isConnected by viewModel.isConnected
    val connectionStatus by viewModel.connectionStatus
    val error by viewModel.error

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Surface).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (isConnected) AccentGreen else AccentRed))
                Spacer(modifier = Modifier.width(6.dp))
                Text("SSH", color = AccentCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(4.dp))
                Text(connectionStatus.take(30), color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Row {
                IconButton(onClick = { viewModel.clearTerminal() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, null, tint = TextDim, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { viewModel.disconnectFromSsh(); viewModel.showConnectionList() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, null, tint = AccentRed, modifier = Modifier.size(16.dp))
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp)) {
            val listState = rememberLazyListState()
            LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }

            if (error != null && lines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error ?: "", color = AccentRed, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }

            LazyColumn(state = listState) {
                itemsIndexed(lines) { idx, line ->
                    if (!line.isEmpty) {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 0.dp)) {
                            if (idx == cursorRow && cursorVisible) {
                                renderCursorLine(line, cursorCol, fontSize = 13)
                            } else {
                                androidx.compose.foundation.text.BasicText(
                                    text = line.toAnnotatedString(),
                                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 17.sp)
                                )
                            }
                        }
                    }
                }
            }
        }

        SshInputBar(viewModel)
        SshExtraKeys(viewModel)
    }
}

@Composable
private fun renderCursorLine(line: StyledLine, cursorCol: Int, fontSize: Int = 13) {
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
    Box(modifier = Modifier.width((fontSize * 0.6).sp.value.dp).background(Color.White)) {
        Text(if (cursorChar == ' ') "\u00A0" else cursorChar.toString(),
            color = Bg, fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
    }
    if (after.isNotEmpty()) {
        androidx.compose.foundation.text.BasicText(
            text = StyledLine(cropSegmentsFrom(line.segments, cursorCol + 1)).toAnnotatedString(),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize + 4).sp)
        )
    }
}

private fun cropSegmentsToLength(segments: List<StyledSegment>, maxLen: Int): List<StyledSegment> {
    val result = mutableListOf<StyledSegment>(); var rem = maxLen
    for (s in segments) { if (rem <= 0) break
        if (s.text.length <= rem) { result.add(s); rem -= s.text.length }
        else { result.add(s.copy(text = s.text.take(rem))); break } }
    return result
}

private fun cropSegmentsFrom(segments: List<StyledSegment>, start: Int): List<StyledSegment> {
    val result = mutableListOf<StyledSegment>(); var off = 0; var started = false
    for (s in segments) { val e = off + s.text.length
        if (!started && e > start) { result.add(s.copy(text = s.text.drop(start - off))); started = true }
        else if (started) result.add(s)
        off = e }
    return result
}

@Composable
private fun SshInputBar(viewModel: SshViewModel) {
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    Row(modifier = Modifier.fillMaxWidth().background(Surface).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f).background(Color(0xFF0D1117), RoundedCornerShape(4.dp)).border(1.dp, Color(0xFF21262D), RoundedCornerShape(4.dp))) {
            BasicTextField(
                value = inputText, onValueChange = { inputText = it },
                textStyle = TextStyle(color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                cursorBrush = SolidColor(AccentCyan),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                        val t = inputText.text.trim()
                        if (t.isNotEmpty()) viewModel.sendCommand(t)
                        else viewModel.sendEnter()
                        inputText = TextFieldValue(""); true
                    } else false
                },
                singleLine = true,
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$ ", color = AccentGreen, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        inner()
                    }
                }
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        FilledTonalButton(
            onClick = {
                val t = inputText.text.trim()
                if (t.isNotEmpty()) viewModel.sendCommand(t) else viewModel.sendEnter()
                inputText = TextFieldValue("")
            },
            modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = AccentBlue)
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SshExtraKeys(viewModel: SshViewModel) {
    var ctrl by remember { mutableStateOf(false) }; var alt by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().background(Surface).padding(horizontal = 4.dp, vertical = 3.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("ESC" to "\u001b", "TAB" to "\t", "|" to "|", ";" to ";", "#" to "#", "~" to "~", "\$" to "\$", "\\" to "\\", "&" to "&")
                .forEach { (l, v) -> SshKey(l, ctrl, alt) { viewModel.sendText(v) } }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("CTRL" to null, "ALT" to null, "-" to "-", "_" to "_", "=" to "=", "+" to "+", "*" to "*", "." to ".", "?" to "?")
                .forEach { (l, v) ->
                    val isMod = l == "CTRL" || l == "ALT"
                    SshKey(l, ctrl, alt, isMod, (l == "CTRL" && ctrl) || (l == "ALT" && alt)) {
                        if (isMod) { if (l == "CTRL") ctrl = !ctrl else alt = !alt }
                        else if (ctrl && v != null && v.length == 1 && v[0] in 'A'..'Z') viewModel.sendData(byteArrayOf((v[0] - 'A' + 1).toByte()))
                        else if (alt && v != null) viewModel.sendText("\u001b$v")
                        else v?.let { viewModel.sendText(it) }
                    }
                }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("(" to "(", ")" to ")", "{" to "{", "}" to "}", "[" to "[", "]" to "]", "'" to "'", "\"" to "\"", "`" to "`")
                .forEach { (l, v) -> SshKey(l, ctrl, alt) { viewModel.sendText(v) } }
        }
    }
}

@Composable
private fun SshKey(label: String, ctrl: Boolean, alt: Boolean, isToggle: Boolean = false, isActive: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (isActive) KeyActiveBg else KeyBg)
            .clickable(onClick = onClick).padding(horizontal = if (label.length > 2) 6.dp else 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (isActive) AccentCyan else TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
    }
}
