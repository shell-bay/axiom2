package com.alpineterminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Visual Transformation for Live IDE Syntax Highlighting
val codeHighlightTransformation = VisualTransformation { text ->
    TransformedText(highlightCode(text.text), OffsetMapping.Identity)
}

fun highlightCode(code: String): AnnotatedString {
    return buildAnnotatedString {
        var lastIndex = 0
        val keywords = setOf(
            "fun", "val", "var", "class", "interface", "import", "package", "return", "if", "else", 
            "for", "while", "def", "print", "from", "as", "in", "break", "continue",
            "try", "catch", "throw", "null", "true", "false", "this", "super", "private", "public"
        )
        // Regex pattern to extract strings, comments, and alphanumeric words
        val pattern = Regex("(\"[^\"]*\")|(//.*)|(/\\*.*?\\*/)|(\\b\\w+\\b)", RegexOption.DOT_MATCHES_ALL)
        
        pattern.findAll(code).forEach { match ->
            val matchRange = match.range
            val matchText = match.value
            
            if (matchRange.start > lastIndex) {
                append(code.substring(lastIndex, matchRange.start))
            }
            
            when {
                // Comment highlighting (Green)
                matchText.startsWith("//") || matchText.startsWith("/*") -> {
                    withStyle(style = SpanStyle(color = Color(0xFF6A9955))) {
                        append(matchText)
                    }
                }
                // String literal highlighting (Peach/Orange)
                matchText.startsWith("\"") && matchText.endsWith("\"") -> {
                    withStyle(style = SpanStyle(color = Color(0xFFCE9178))) {
                        append(matchText)
                    }
                }
                // Reserved keyword highlighting (Sky Blue)
                keywords.contains(matchText) -> {
                    withStyle(style = SpanStyle(color = Color(0xFF569CD6), fontWeight = FontWeight.Bold)) {
                        append(matchText)
                    }
                }
                // Number highlighting (Light Green)
                matchText.all { it.isDigit() } -> {
                    withStyle(style = SpanStyle(color = Color(0xFFB5CEA8))) {
                        append(matchText)
                    }
                }
                else -> {
                    append(matchText)
                }
            }
            lastIndex = matchRange.endInclusive + 1
        }
        
        if (lastIndex < code.length) {
            append(code.substring(lastIndex))
        }
    }
}

@Composable
fun TextEditorScreen(
    file: AlpineFile,
    fileManager: FileResourceManager,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    var content by remember { mutableStateOf(fileManager.readFile(file.path)) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E)) // Obsidian dark mode theme
    ) {
        // Toolbar Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF252526))
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.LightGray)
            }
            Text(
                text = file.name,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                fileManager.writeFile(file.path, content)
                android.widget.Toast.makeText(context, "File saved successfully!", android.widget.Toast.LENGTH_SHORT).show()
                onSave()
            }) {
                Icon(Icons.Default.Save, contentDescription = "Save", tint = Color(0xFF4ADE80))
            }
        }

        HorizontalDivider(color = Color.DarkGray, thickness = 1.dp)

        // Text Editing Area with Line Numbers
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val lines = content.split("\n")
            val lineCount = lines.size.coerceAtLeast(1)

            // Line numbers gutter
            Column(
                modifier = Modifier
                    .background(Color(0xFF1A1A1A))
                    .padding(vertical = 12.dp, horizontal = 8.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.End
            ) {
                for (i in 1..lineCount) {
                    Text(
                        text = "$i",
                        color = Color.DarkGray,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.height(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Scrollable Content Editor with Live Syntax Highlighting
            BasicTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 12.dp),
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF4ADE80)),
                visualTransformation = codeHighlightTransformation,
                decorationBox = { innerTextField ->
                    innerTextField()
                }
            )
        }

        // Quick Editor Access Helper Keys
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val keys = listOf("{ }", "[ ]", "( )", "Tab", "\"", "'", "&&", "||")
            keys.forEach { label ->
                Card(
                    modifier = Modifier
                        .clickable {
                            val insertText = when (label) {
                                "Tab" -> "    "
                                "{ }" -> "{}"
                                "[ ]" -> "[]"
                                "( )" -> "()"
                                else -> label
                            }
                            content = content + insertText
                        }
                        .padding(2.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
                ) {
                    Text(
                        text = label,
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
