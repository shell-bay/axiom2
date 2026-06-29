package com.alpineterminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

data class StyledSegment(
    val text: String,
    val foreground: Color = Color(0xFF00FF00),
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
            builder.pushStyle(style)
            builder.append(seg.text)
            builder.pop()
        }
        return builder.toAnnotatedString()
    }
}

private val ansiColors = mapOf(
    0 to Color(0xFF000000),
    1 to Color(0xFFCD3131),
    2 to Color(0xFF0DBC79),
    3 to Color(0xFFE5E510),
    4 to Color(0xFF2472C8),
    5 to Color(0xFFBC3FBC),
    6 to Color(0xFF11A8CD),
    7 to Color(0xFFE5E5E5),
    8 to Color(0xFF666666),
    9 to Color(0xFFF14C4C),
    10 to Color(0xFF23D18B),
    11 to Color(0xFFF5F543),
    12 to Color(0xFF3B8EEA),
    13 to Color(0xFFD670D6),
    14 to Color(0xFF29B8DB),
    15 to Color(0xFFE5E5E5)
)

class TerminalStateMachine(
    val columns: Int = 80,
    val rows: Int = 24,
    val scrollbackLines: Int = 2000
) {
    private val buffer = StringBuilder()
    val scrollback = ArrayDeque<MutableList<Char>>()

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
    private var originMode = false

    private var curFg = 7
    private var curBg = 0
    private var curBold = false
    private var curItalic = false
    private var curUnderline = false
    private var curReverse = false

    private var parseState = 0
    private val params = mutableListOf<Int>()
    private val paramBuf = StringBuilder()
    private var privateMarker = false
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    private var cursorVisible = true

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
                1 -> { handleEscape(c); if (parseState != 2 && parseState != 4) parseState = 0 }
                2 -> {} // CSI entry handled in handleEscape
                3 -> {} // CSI param handled in handleEscape
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
                cursorCol = ((cursorCol / 8) + 1) * 8
                if (cursorCol >= columns) { cursorCol = 0; newline() }
            }
            '\u0007' -> {}
            '\u001b' -> parseState = 1
            in '\u0020'..'\u00ff' -> putChar(c)
        }
    }

    private fun handleEscape(c: Char) {
        when (c) {
            '[' -> { parseState = 2; params.clear(); paramBuf.clear(); privateMarker = false }
            ']' -> { parseState = 4 }
            '7' -> { savedCursorRow = cursorRow; savedCursorCol = cursorCol }
            '8' -> { cursorRow = savedCursorRow; cursorCol = savedCursorCol; clampCursor() }
            'c' -> resetTerminal()
            'D' -> {} // Index
            'M' -> {} // Reverse index
            '(', ')' -> {} // Charset selection
        }
    }

    private fun handleCsi(c: Char) {
        when {
            c == '?' -> { privateMarker = true; parseState = 3 }
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

    private fun executeCsi(c: Char) {
        when (c) {
            'A' -> cursorUp(param(1))
            'B' -> cursorDown(param(1))
            'C' -> cursorForward(param(1))
            'D' -> cursorBack(param(1))
            'E' -> { cursorDown(param(1)); cursorCol = 0 }
            'F' -> { cursorUp(param(1)); cursorCol = 0 }
            'G' -> cursorCol = (param(1) - 1).coerceIn(0, columns - 1)
            'd' -> cursorRow = (param(1) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                cursorRow = (paramOr(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (paramOr(1, 1) - 1).coerceIn(0, columns - 1)
                if (originMode) { cursorRow = (cursorRow + scrollTop).coerceIn(scrollTop, scrollBottom) }
            }
            'J' -> eraseDisplay(param(0))
            'K' -> eraseLine(param(0))
            'L' -> insertLines(param(1))
            'M' -> deleteLines(param(1))
            'P' -> deleteChars(param(1))
            '@' -> insertChars(param(1))
            'X' -> eraseChars(param(1))
            'S' -> scrollUp(param(1))
            'T' -> scrollDown(param(1))
            'm' -> applySgr()
            's' -> { savedCursorRow = cursorRow; savedCursorCol = cursorCol }
            'u' -> { cursorRow = savedCursorRow; cursorCol = savedCursorCol; clampCursor() }
            'h' -> if (privateMarker) handleDecSet(param(0))
            'l' -> if (privateMarker) handleDecReset(param(0))
            'r' -> {
                scrollTop = (param(1) - 1).coerceIn(0, rows - 1)
                scrollBottom = (paramOr(1, rows) - 1).coerceIn(0, rows - 1)
                if (scrollTop > scrollBottom) { val t = scrollTop; scrollTop = scrollBottom; scrollBottom = t }
                cursorRow = scrollTop; cursorCol = 0
            }
        }
        privateMarker = false
    }

    private fun param(idx: Int, default: Int = 0): Int =
        params.getOrElse(idx) { default }.coerceAtLeast(0)

    private fun paramOr(idx: Int, default: Int): Int =
        params.getOrElse(idx) { default }.let { if (it == 0) default else it }

    private fun handleDecSet(mode: Int) {
        when (mode) {
            25 -> cursorVisible = true
            1049 -> {} // Alternate screen - simplified
            6 -> originMode = true
        }
    }

    private fun handleDecReset(mode: Int) {
        when (mode) {
            25 -> cursorVisible = false
            1049 -> {}
            6 -> { originMode = false; cursorRow = 0; cursorCol = 0 }
        }
    }

    private fun handleOsc(c: Char) {
        if (c == '\u0007' || (c == '\\' && buffer.length > 1 && buffer[buffer.length - 2] == '\u001b')) {
            parseState = 0
        }
    }

    private fun cursorUp(n: Int) {
        cursorRow = maxOf(if (originMode) scrollTop else 0, cursorRow - n)
    }

    private fun cursorDown(n: Int) {
        cursorRow = minOf(if (originMode) scrollBottom else rows - 1, cursorRow + n)
    }

    private fun cursorForward(n: Int) {
        cursorCol = minOf(columns - 1, cursorCol + n)
    }

    private fun cursorBack(n: Int) {
        cursorCol = maxOf(0, cursorCol - n)
    }

    private fun applySgr() {
        val pl = params.toList()
        if (pl.isEmpty() || pl.first() == 0) { resetStyle(); return }
        var i = 0
        while (i < pl.size) {
            val p = pl[i]
            when {
                p == 0 -> resetStyle()
                p == 1 -> curBold = true
                p == 2 -> curBold = false
                p == 3 -> curItalic = true
                p == 4 -> curUnderline = true
                p == 7 -> curReverse = true
                p == 22 -> curBold = false
                p == 23 -> curItalic = false
                p == 24 -> curUnderline = false
                p == 27 -> curReverse = false
                p in 30..37 -> curFg = p - 30
                p == 38 -> {
                    if (i + 2 < pl.size && pl[i + 1] == 5) { curFg = pl[i + 2].coerceIn(0, 255); i += 2 }
                    else if (i + 4 < pl.size && pl[i + 1] == 2) { i += 4 }
                }
                p == 39 -> curFg = 7
                p in 40..47 -> curBg = p - 40
                p == 48 -> {
                    if (i + 2 < pl.size && pl[i + 1] == 5) { curBg = pl[i + 2].coerceIn(0, 255); i += 2 }
                    else if (i + 4 < pl.size && pl[i + 1] == 2) { i += 4 }
                }
                p == 49 -> curBg = 0
                p in 90..97 -> curFg = p - 90 + 8
                p in 100..107 -> curBg = p - 100 + 8
            }
            i++
        }
    }

    private fun resetStyle() {
        curFg = 7; curBg = 0; curBold = false; curItalic = false; curUnderline = false; curReverse = false
    }

    private fun putChar(c: Char) {
        clampCursor()
        if (cursorCol >= columns) { cursorCol = 0; newline() }
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
        if (cursorRow >= scrollBottom) { scrollUp(1) }
        else { cursorRow++ }
    }

    private fun scrollUp(n: Int) {
        val count = minOf(n, scrollBottom - scrollTop + 1)
        for (i in 0 until count) {
            val scrolledLine = screen[scrollTop].toMutableList()
            scrollback.addLast(scrolledLine)
            if (scrollback.size > scrollbackLines) scrollback.removeFirst()
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
        for (i in (scrollBottom - count + 1)..scrollBottom) { clearRow(i) }
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
        for (i in scrollTop until scrollTop + count) { clearRow(i) }
    }

    private fun clearRow(row: Int) {
        for (col in 0 until columns) {
            screen[row][col] = ' '
            screenFg[row][col] = 7; screenBg[row][col] = 0
            screenBold[row][col] = false; screenItalic[row][col] = false
            screenUnderline[row][col] = false; screenReverse[row][col] = false
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                for (col in cursorCol until columns) clearCell(cursorRow, col)
                for (row in cursorRow + 1 until rows) clearRow(row)
            }
            1 -> {
                for (row in 0 until cursorRow) clearRow(row)
                for (col in 0..cursorCol) clearCell(cursorRow, col)
            }
            2, 3 -> { for (row in 0 until rows) clearRow(row) }
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> for (col in cursorCol until columns) clearCell(cursorRow, col)
            1 -> for (col in 0..cursorCol) clearCell(cursorRow, col)
            2 -> clearRow(cursorRow)
        }
    }

    private fun insertLines(n: Int) { scrollDownAt(cursorRow, minOf(n, scrollBottom - cursorRow + 1)) }
    private fun deleteLines(n: Int) { scrollUpAt(cursorRow, minOf(n, scrollBottom - cursorRow + 1)) }

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
        for (i in (end - count + 1)..end) clearRow(i)
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
        for (i in row until row + count) clearRow(i)
    }

    private fun clearCell(row: Int, col: Int) {
        screen[row][col] = ' '
        screenFg[row][col] = 7; screenBg[row][col] = 0
        screenBold[row][col] = false; screenItalic[row][col] = false
        screenUnderline[row][col] = false; screenReverse[row][col] = false
    }

    private fun clampCursor() {
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, columns - 1)
    }

    private fun resetTerminal() {
        for (row in 0 until rows) clearRow(row)
        cursorRow = 0; cursorCol = 0
        scrollTop = 0; scrollBottom = rows - 1; originMode = false
        resetStyle()
        savedCursorRow = 0; savedCursorCol = 0; cursorVisible = true
        scrollback.clear()
    }

    fun getScreenLines(): List<StyledLine> {
        if (!linesDirty) return _screenLines
        rebuildScreenLines()
        linesDirty = false
        return _screenLines
    }

    fun getCursorRow(): Int = cursorRow
    fun getCursorCol(): Int = cursorCol
    fun isCursorVisible(): Boolean = cursorVisible

    fun getPlainText(): String {
        val sb = StringBuilder()
        for (row in 0 until rows) {
            var end = columns - 1
            while (end >= 0 && screen[row][end] == ' ') end--
            for (col in 0..end) sb.append(screen[row][col])
            if (end >= 0) sb.append('\n')
        }
        return sb.toString()
    }

    private fun rebuildScreenLines() {
        for (row in 0 until rows) {
            val segments = mutableListOf<StyledSegment>()
            var segStart = 0
            var segFg = screenFg[row][0]; var segBg = screenBg[row][0]
            var segBold = screenBold[row][0]; var segItalic = screenItalic[row][0]
            var segUnderline = screenUnderline[row][0]; var segRev = screenReverse[row][0]

            for (col in 1..columns) {
                if (col == columns ||
                    screenFg[row][col - 1] != segFg ||
                    screenBg[row][col - 1] != segBg ||
                    screenBold[row][col - 1] != segBold ||
                    screenItalic[row][col - 1] != segItalic ||
                    screenUnderline[row][col - 1] != segUnderline ||
                    screenReverse[row][col - 1] != segRev) {

                    val text = screen[row].slice(segStart until col).joinToString("")
                    if (text.isNotEmpty() || segments.isEmpty()) {
                        segments.add(StyledSegment(
                            text = text,
                            foreground = resolveColor(segFg, false),
                            background = resolveColor(segBg, false),
                            bold = segBold, italic = segItalic, underline = segUnderline
                        ))
                    }
                    if (col < columns) {
                        segStart = col
                        segFg = screenFg[row][col]; segBg = screenBg[row][col]
                        segBold = screenBold[row][col]; segItalic = screenItalic[row][col]
                        segUnderline = screenUnderline[row][col]; segRev = screenReverse[row][col]
                    }
                }
            }
            val isEmpty = segments.all { it.text.isBlank() }
            _screenLines[row] = StyledLine(segments, isEmpty)
        }
    }

    private fun resolveColor(ansiColor: Int, _reverse: Boolean): Color {
        return ansiColors[ansiColor.coerceIn(0, 255)] ?: ansiColors[7]!!
    }

    fun clear() { resetTerminal(); linesDirty = true }
}
