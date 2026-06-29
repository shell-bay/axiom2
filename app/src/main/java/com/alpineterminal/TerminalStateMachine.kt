package com.alpineterminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

data class StyledSegment(
    val text: String,
    val foreground: Color = Color.Green,
    val background: Color = Color.Transparent,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false
)

data class StyledLine(
    val segments: List<StyledSegment> = listOf(StyledSegment("")),
    val isEmpty: Boolean = false
) {
    val text: String get() = segments.joinToString("") { it.text }

    fun toAnnotatedString(): AnnotatedString {
        val builder = AnnotatedString.Builder()
        for (seg in segments) {
            val style = SpanStyle(
                color = seg.foreground,
                background = seg.background,
                fontWeight = if (seg.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (seg.italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = if (seg.underline) TextDecoration.Underline else null
            )
            builder.withStyle(style) { append(seg.text) }
        }
        return builder.toAnnotatedString()
    }
}

private val ansiColors = mapOf(
    0 to Color(0xFF000000),   // Black
    1 to Color(0xFFAA0000),   // Red
    2 to Color(0xFF00AA00),   // Green
    3 to Color(0xFFAA5500),   // Yellow/Brown
    4 to Color(0xFF0000AA),   // Blue
    5 to Color(0xFFAA00AA),   // Magenta
    6 to Color(0xFF00AAAA),   // Cyan
    7 to Color(0xFFAAAAAA),   // White
    8 to Color(0xFF555555),   // Bright Black (Gray)
    9 to Color(0xFFFF5555),   // Bright Red
    10 to Color(0xFF55FF55),  // Bright Green
    11 to Color(0xFFFFFF55),  // Bright Yellow
    12 to Color(0xFF5555FF),  // Bright Blue
    13 to Color(0xFFFF55FF),  // Bright Magenta
    14 to Color(0xFF55FFFF),  // Bright Cyan
    15 to Color(0xFFFFFFFF)   // Bright White
)

class TerminalStateMachine(
    val columns: Int = 80,
    val rows: Int = 24,
    val scrollbackLines: Int = 2000
) {
    private val buffer = StringBuilder()
    private val scrollback = ArrayDeque<MutableList<Char>>()
    private val screen = MutableList(rows) { MutableList(columns) { ' ' } }
    private val screenFg = MutableList(rows) { MutableList(columns) { 7 } }
    private val screenBg = MutableList(rows) { MutableList(columns) { 0 } }
    private val screenBold = MutableList(rows) { MutableList(columns) { false } }
    private val screenItalic = MutableList(rows) { MutableList(columns) { false } }
    private val screenUnderline = MutableList(rows) { MutableList(columns) { false } }
    private val screenReverse = MutableList(rows) { MutableList(columns) { false } }

    private var cursorRow = 0
    private var cursorCol = 0
    private var scrollTop = 0
    private var scrollBottom = rows - 1

    private var curFg = 7
    private var curBg = 0
    private var curBold = false
    private var curItalic = false
    private var curUnderline = false
    private var curReverse = false

    private var parseState = 0
    private val params = mutableListOf<Int>()
    private val paramBuf = StringBuilder()
    private var oscBuf = StringBuilder()
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    private var cursorVisible = true
    private var _needsFullRebuild = true

    private val _screenLines = MutableList(rows) { StyledLine(isEmpty = true) }
    private var linesDirty = true

    fun feed(data: String) {
        buffer.append(data)
        processBuffer()
    }

    private fun processBuffer() {
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            when (parseState) {
                0 -> handleNormalChar(c)
                1 -> handleEscape(c)
                2 -> handleCsi(c)
                3 -> handleCsiParam(c)
                4 -> handleOsc(c)
            }
            i++
        }
        buffer.clear()
        linesDirty = true
    }

    private fun handleNormalChar(c: Char) {
        when (c) {
            '\r' -> cursorCol = 0
            '\n' -> newline()
            '\b' -> if (cursorCol > 0) cursorCol--
            '\t' -> {
                val tabStop = 8
                cursorCol = (cursorCol / tabStop + 1) * tabStop
                if (cursorCol >= columns) {
                    cursorCol = 0
                    newline()
                }
            }
            '\u0007' -> {} // Bell - ignore
            '\u001b' -> parseState = 1
            in '\u0020'..'\u007e', in '\u00a0'..'\u00ff' -> putChar(c)
            else -> {} // Skip other control chars
        }
    }

    private fun handleEscape(c: Char) {
        when (c) {
            '[' -> { parseState = 2; params.clear(); paramBuf.clear() }
            ']' -> { parseState = 4; oscBuf.clear() }
            '7' -> { savedCursorRow = cursorRow; savedCursorCol = cursorCol; parseState = 0 }
            '8' -> { cursorRow = savedCursorRow; cursorCol = savedCursorCol; parseState = 0 }
            'c' -> resetTerminal()
            '(' -> parseState = 0  // G0 charset - ignore
            ')' -> parseState = 0  // G1 charset - ignore
            else -> parseState = 0
        }
    }

    private fun handleCsi(c: Char) {
        when {
            c == '?' -> parseState = 3  // DEC private mode
            c in '0'..'9' -> { paramBuf.append(c); parseState = 3 }
            c == ';' -> {
                params.add(paramBuf.toString().toIntOrNull() ?: 0)
                paramBuf.clear()
            }
            else -> {
                params.add(paramBuf.toString().toIntOrNull() ?: 0)
                executeCsi(c)
                parseState = 0
            }
        }
    }

    private fun handleCsiParam(c: Char) {
        when {
            c in '0'..'9' -> paramBuf.append(c)
            c == ';' -> {
                params.add(paramBuf.toString().toIntOrNull() ?: 0)
                paramBuf.clear()
                parseState = 2
            }
            else -> {
                params.add(paramBuf.toString().toIntOrNull() ?: 0)
                executeCsi(c)
                parseState = 0
            }
        }
    }

    private fun handleOsc(c: Char) {
        if (c == '\u0007' || c == '\u001b') {
            parseState = 0
            return
        }
        if (c == '\\' && oscBuf.isNotEmpty() && oscBuf.last() == '\u001b') {
            parseState = 0
            return
        }
        oscBuf.append(c)
    }

    private fun executeCsi(c: Char) {
        val isPrivate = params.firstOrNull() == -1
        if (isPrivate && params.size > 1) params.removeAt(0)

        // Remove the fake -1 marker if it wasn't a private mode
        if (params.firstOrNull() == -1 && !isPrivate) params.removeAt(0)

        when (c) {
            'A' -> cursorUp(params.lastOrNull() ?: 1)
            'B' -> cursorDown(params.lastOrNull() ?: 1)
            'C' -> cursorForward(params.lastOrNull() ?: 1)
            'D' -> cursorBack(params.lastOrNull() ?: 1)
            'E' -> { cursorDown(params.lastOrNull() ?: 1); cursorCol = 0 }
            'F' -> { cursorUp(params.lastOrNull() ?: 1); cursorCol = 0 }
            'G' -> cursorCol = (params.lastOrNull() ?: 1) - 1
            'd' -> cursorRow = (params.lastOrNull() ?: 1) - 1
            'H', 'f' -> {
                val r = params.getOrElse(0) { 1 }
                val c2 = params.getOrElse(1) { 1 }
                cursorRow = r - 1
                cursorCol = c2 - 1
            }
            'J' -> eraseDisplay(params.lastOrNull() ?: 0)
            'K' -> eraseLine(params.lastOrNull() ?: 0)
            'L' -> insertLines(params.lastOrNull() ?: 1)
            'M' -> deleteLines(params.lastOrNull() ?: 1)
            'P' -> deleteChars(params.lastOrNull() ?: 1)
            '@' -> insertChars(params.lastOrNull() ?: 1)
            'X' -> eraseChars(params.lastOrNull() ?: 1)
            'S' -> scrollUp(params.lastOrNull() ?: 1)
            'T' -> scrollDown(params.lastOrNull() ?: 1)
            'm' -> applySgr()
            's' -> { savedCursorRow = cursorRow; savedCursorCol = cursorCol }
            'u' -> { cursorRow = savedCursorRow; cursorCol = savedCursorCol }
            'h' -> if (isPrivate && params.size == 1) handleDecSet(params[0])
            'l' -> if (isPrivate && params.size == 1) handleDecReset(params[0])
            'r' -> {
                val t = params.getOrElse(0) { 1 }
                val b = params.getOrElse(1) { rows }
                scrollTop = t - 1
                scrollBottom = b - 1
            }
        }
    }

    private fun handleDecSet(mode: Int) {
        when (mode) {
            25 -> cursorVisible = true
            1049 -> {} // Enter alternate screen - simplified
        }
    }

    private fun handleDecReset(mode: Int) {
        when (mode) {
            25 -> cursorVisible = false
            1049 -> {} // Exit alternate screen - simplified
        }
    }

    private fun cursorUp(n: Int) {
        cursorRow = maxOf(0, cursorRow - n)
    }

    private fun cursorDown(n: Int) {
        cursorRow = minOf(rows - 1, cursorRow + n)
    }

    private fun cursorForward(n: Int) {
        cursorCol = minOf(columns - 1, cursorCol + n)
    }

    private fun cursorBack(n: Int) {
        cursorCol = maxOf(0, cursorCol - n)
    }

    private fun applySgr() {
        val pl = params.toList()
        if (pl.isEmpty() || pl.first() == 0) {
            resetStyle()
            return
        }
        var i = 0
        while (i < pl.size) {
            val p = pl[i]
            when {
                p == 0 -> resetStyle()
                p == 1 -> curBold = true
                p == 2 -> curBold = false
                p == 3 -> curItalic = true
                p == 4 -> curUnderline = true
                p == 5 || p == 6 -> {}  // Blink - ignore
                p == 7 -> curReverse = true
                p == 8 -> {}  // Conceal - ignore
                p == 9 -> {}  // Strikethrough - ignore
                p == 22 -> curBold = false
                p == 23 -> curItalic = false
                p == 24 -> curUnderline = false
                p == 27 -> curReverse = false
                p in 30..37 -> curFg = p - 30
                p == 38 && i + 2 < pl.size && pl[i + 1] == 5 -> {
                    curFg = pl[i + 2]
                    i += 2
                }
                p == 38 && i + 4 < pl.size && pl[i + 1] == 2 -> {
                    curFg = 7 // approximation for true color
                    i += 4
                }
                p == 39 -> curFg = 7
                p in 40..47 -> curBg = p - 40
                p == 48 && i + 2 < pl.size && pl[i + 1] == 5 -> {
                    curBg = pl[i + 2]
                    i += 2
                }
                p == 48 && i + 4 < pl.size && pl[i + 1] == 2 -> {
                    curBg = 0
                    i += 4
                }
                p == 49 -> curBg = 0
                p in 90..97 -> curFg = p - 90 + 8
                p in 100..107 -> curBg = p - 100 + 8
            }
            i++
        }
    }

    private fun resetStyle() {
        curFg = 7
        curBg = 0
        curBold = false
        curItalic = false
        curUnderline = false
        curReverse = false
    }

    private fun putChar(c: Char) {
        clampCursor()
        if (cursorCol >= columns) {
            cursorCol = 0
            newline()
        }
        screen[cursorRow][cursorCol] = c
        screenFg[cursorRow][cursorCol] = curFg
        screenBg[cursorRow][cursorCol] = curBg
        screenBold[cursorRow][cursorCol] = curBold
        screenItalic[cursorRow][cursorCol] = curItalic
        screenUnderline[cursorRow][cursorCol] = curUnderline
        screenReverse[cursorRow][cursorCol] = curReverse
        cursorCol++
    }

    private fun newline() {
        if (cursorRow >= scrollBottom) {
            scrollUp(1)
        } else {
            cursorRow++
        }
    }

    private fun scrollUp(n: Int) {
        val count = minOf(n, scrollBottom - scrollTop + 1)
        for (i in 0 until count) {
            val scrolledLine = screen[scrollTop]
            scrollback.addLast(scrolledLine.map { it }.toMutableList())
            if (scrollback.size > scrollbackLines) scrollback.removeFirst()
            for (col in 0 until columns) {
                screen[scrollTop][col] = ' '
                screenFg[scrollTop][col] = 7
                screenBg[scrollTop][col] = 0
                screenBold[scrollTop][col] = false
                screenItalic[scrollTop][col] = false
                screenUnderline[scrollTop][col] = false
                screenReverse[scrollTop][col] = false
            }
        }
        for (i in scrollTop until scrollBottom - count + 1) {
            for (col in 0 until columns) {
                screen[i][col] = screen[i + count][col]
                screenFg[i][col] = screenFg[i + count][col]
                screenBg[i][col] = screenBg[i + count][col]
                screenBold[i][col] = screenBold[i + count][col]
                screenItalic[i][col] = screenItalic[i + count][col]
                screenUnderline[i][col] = screenUnderline[i + count][col]
                screenReverse[i][col] = screenReverse[i + count][col]
            }
        }
        for (i in (scrollBottom - count + 1)..scrollBottom) {
            for (col in 0 until columns) {
                screen[i][col] = ' '
                screenFg[i][col] = 7
                screenBg[i][col] = 0
            }
        }
    }

    private fun scrollDown(n: Int) {
        val count = minOf(n, scrollBottom - scrollTop + 1)
        for (i in scrollBottom downTo scrollTop + count) {
            for (col in 0 until columns) {
                screen[i][col] = screen[i - count][col]
                screenFg[i][col] = screenFg[i - count][col]
                screenBg[i][col] = screenBg[i - count][col]
            }
        }
        for (i in scrollTop until scrollTop + count) {
            for (col in 0 until columns) {
                screen[i][col] = ' '
                screenFg[i][col] = 7
                screenBg[i][col] = 0
            }
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { // Clear from cursor to end
                for (col in cursorCol until columns) clearCell(cursorRow, col)
                for (row in cursorRow + 1 until rows) {
                    for (col in 0 until columns) clearCell(row, col)
                }
            }
            1 -> { // Clear from beginning to cursor
                for (row in 0 until cursorRow) {
                    for (col in 0 until columns) clearCell(row, col)
                }
                for (col in 0..cursorCol) clearCell(cursorRow, col)
            }
            2, 3 -> { // Clear entire screen
                for (row in 0 until rows) {
                    for (col in 0 until columns) clearCell(row, col)
                }
            }
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> for (col in cursorCol until columns) clearCell(cursorRow, col)
            1 -> for (col in 0..cursorCol) clearCell(cursorRow, col)
            2 -> for (col in 0 until columns) clearCell(cursorRow, col)
        }
    }

    private fun insertLines(n: Int) {
        val count = minOf(n, scrollBottom - cursorRow)
        scrollDownAt(cursorRow, count)
    }

    private fun deleteLines(n: Int) {
        val count = minOf(n, scrollBottom - cursorRow + 1)
        scrollUpAt(cursorRow, count)
    }

    private fun insertChars(n: Int) {
        val count = minOf(n, columns - cursorCol)
        for (col in columns - 1 downTo cursorCol + count) {
            screen[cursorRow][col] = screen[cursorRow][col - count]
            screenFg[cursorRow][col] = screenFg[cursorRow][col - count]
            screenBg[cursorRow][col] = screenBg[cursorRow][col - count]
        }
        for (col in cursorCol until cursorCol + count) clearCell(cursorRow, col)
    }

    private fun deleteChars(n: Int) {
        val count = minOf(n, columns - cursorCol)
        for (col in cursorCol until columns - count) {
            screen[cursorRow][col] = screen[cursorRow][col + count]
            screenFg[cursorRow][col] = screenFg[cursorRow][col + count]
            screenBg[cursorRow][col] = screenBg[cursorRow][col + count]
        }
        for (col in columns - count until columns) clearCell(cursorRow, col)
    }

    private fun eraseChars(n: Int) {
        val count = minOf(n, columns - cursorCol)
        for (col in cursorCol until cursorCol + count) clearCell(cursorRow, col)
    }

    private fun scrollUpAt(row: Int, count: Int) {
        val end = scrollBottom
        for (i in row until end - count + 1) {
            for (col in 0 until columns) {
                screen[i][col] = screen[i + count][col]
                screenFg[i][col] = screenFg[i + count][col]
                screenBg[i][col] = screenBg[i + count][col]
            }
        }
        for (i in (end - count + 1)..end) {
            for (col in 0 until columns) clearCell(i, col)
        }
    }

    private fun scrollDownAt(row: Int, count: Int) {
        val end = scrollBottom
        for (i in end downTo row + count) {
            for (col in 0 until columns) {
                screen[i][col] = screen[i - count][col]
                screenFg[i][col] = screenFg[i - count][col]
                screenBg[i][col] = screenBg[i - count][col]
            }
        }
        for (i in row until row + count) {
            for (col in 0 until columns) clearCell(i, col)
        }
    }

    private fun clearCell(row: Int, col: Int) {
        screen[row][col] = ' '
        screenFg[row][col] = 7
        screenBg[row][col] = 0
        screenBold[row][col] = false
        screenItalic[row][col] = false
        screenUnderline[row][col] = false
        screenReverse[row][col] = false
    }

    private fun clampCursor() {
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, columns - 1)
    }

    private fun resetTerminal() {
        for (row in 0 until rows) {
            for (col in 0 until columns) clearCell(row, col)
        }
        cursorRow = 0
        cursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        resetStyle()
        savedCursorRow = 0
        savedCursorCol = 0
        cursorVisible = true
        scrollback.clear()
    }

    fun getScreenLines(): List<StyledLine> {
        if (!linesDirty) return _screenLines
        rebuildScreenLines()
        linesDirty = false
        return _screenLines
    }

    fun getCursorPosition(): Pair<Int, Int> = Pair(cursorRow, cursorCol)

    fun isCursorVisible(): Boolean = cursorVisible

    private fun rebuildScreenLines() {
        for (row in 0 until rows) {
            val segments = mutableListOf<StyledSegment>()
            var segStart = 0
            var segFg = screenFg[row][0]
            var segBg = screenBg[row][0]
            var segBold = screenBold[row][0]
            var segItalic = screenItalic[row][0]
            var segUnderline = screenUnderline[row][0]
            var segReverse = screenReverse[row][0]

            for (col in 1..columns) {
                val currentCol = if (col < columns) col else columns - 1
                val styleChanged = col == columns ||
                    screenFg[row][currentCol] != segFg ||
                    screenBg[row][currentCol] != segBg ||
                    screenBold[row][currentCol] != segBold ||
                    screenItalic[row][currentCol] != segItalic ||
                    screenUnderline[row][currentCol] != segUnderline ||
                    screenReverse[row][currentCol] != segReverse

                if (styleChanged) {
                    val text = screen[row].slice(segStart until col).joinToString("")
                    if (text.isNotEmpty() || segments.isEmpty()) {
                        segments.add(StyledSegment(
                            text = text,
                            foreground = resolveColor(segFg, segReverse),
                            background = resolveColor(segBg, !segReverse),
                            bold = segBold,
                            italic = segItalic,
                            underline = segUnderline
                        ))
                    }
                    if (col < columns) {
                        segStart = col
                        segFg = screenFg[row][col]
                        segBg = screenBg[row][col]
                        segBold = screenBold[row][col]
                        segItalic = screenItalic[row][col]
                        segUnderline = screenUnderline[row][col]
                        segReverse = screenReverse[row][col]
                    }
                }
            }
            val isEmpty = segments.all { it.text.isBlank() }
            _screenLines[row] = StyledLine(segments, isEmpty)
        }
    }

    private fun resolveColor(ansiColor: Int, reverse: Boolean): Color {
        val base = ansiColors[ansiColor.coerceIn(0, 255)] ?: ansiColors[7]!!
        return if (reverse && ansiColor == 0) Color.Green else base
    }

    fun setSize(cols: Int, rows2: Int) {
        // Simple resize - clear and reset
    }

    fun clear() {
        resetTerminal()
        linesDirty = true
    }
}
