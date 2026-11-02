package com.airsense.companion.data

data class TerminalRun(val text: String, val foreground: Int?, val background: Int?, val bold: Boolean)

/** Render tmux capture-pane SGR colour output; discard non-display escape sequences. */
object AnsiParser {
    private val palette = intArrayOf(0xFF11191D.toInt(), 0xFFFF8E89.toInt(), 0xFF7EE2C4.toInt(), 0xFFF4C574.toInt(),
        0xFF81ADFF.toInt(), 0xFFD3A5EF.toInt(), 0xFF7BD6E5.toInt(), 0xFFE8F0F1.toInt(),
        0xFF71838A.toInt(), 0xFFFFA7A3.toInt(), 0xFFABF1D8.toInt(), 0xFFFFE1A7.toInt(),
        0xFFA8C5FF.toInt(), 0xFFE3C6F5.toInt(), 0xFFAFE6EE.toInt(), 0xFFFFFFFF.toInt())
    private fun rgb(r: Int, g: Int, b: Int) = (255 shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    fun indexed(index: Int): Int {
        val n = index.coerceIn(0, 255)
        if (n < 16) return palette[n]
        if (n >= 232) return (8 + (n - 232) * 10).let { rgb(it, it, it) }
        val cube = n - 16
        fun level(n: Int) = if (n == 0) 0 else 55 + n * 40
        return rgb(level(cube / 36), level(cube / 6 % 6), level(cube % 6))
    }
    fun parse(text: String): List<TerminalRun> {
        val runs = mutableListOf<TerminalRun>()
        val buffer = StringBuilder()
        var foreground: Int? = null
        var background: Int? = null
        var bold = false
        var inverse = false
        fun flush() {
            if (buffer.isNotEmpty()) {
                runs += TerminalRun(buffer.toString(), if (inverse) background ?: palette[0] else foreground,
                    if (inverse) foreground ?: palette[7] else background, bold)
                buffer.setLength(0)
            }
        }
        var i = 0
        while (i < text.length) {
            val char = text[i++]
            if (char != '\u001b') {
                if (char == '\n' || char == '\t' || char >= ' ') buffer.append(char)
                continue
            }
            if (i >= text.length) break
            when (text[i++]) {
                '[' -> {
                    val start = i
                    while (i < text.length && text[i] !in '@'..'~') i++
                    if (i >= text.length) break
                    val command = text[i++]
                    if (command != 'm') continue
                    flush()
                    val codes = text.substring(start, i - 1).split(';').map { it.toIntOrNull() ?: 0 }
                    var c = 0
                    while (c < codes.size) {
                        when (val code = codes[c++]) {
                            0 -> { foreground = null; background = null; bold = false; inverse = false }
                            1 -> bold = true
                            22 -> bold = false
                            7 -> inverse = true
                            27 -> inverse = false
                            39 -> foreground = null
                            49 -> background = null
                            in 30..37 -> foreground = palette[code - 30]
                            in 40..47 -> background = palette[code - 40]
                            in 90..97 -> foreground = palette[code - 90 + 8]
                            in 100..107 -> background = palette[code - 100 + 8]
                            38, 48 -> {
                                var color: Int? = null
                                if (c < codes.size) when (codes[c++]) {
                                    5 -> if (c < codes.size) color = indexed(codes[c++])
                                    2 -> if (c + 2 < codes.size) { color = rgb(codes[c], codes[c + 1], codes[c + 2]); c += 3 }
                                }
                                if (color != null) { if (code == 38) foreground = color else background = color }
                            }
                        }
                    }
                }
                ']' -> {
                    while (i < text.length) {
                        if (text[i++] == '\u0007') break
                        if (i >= 2 && text[i - 2] == '\u001b' && text[i - 1] == '\\') break
                    }
                }
            }
        }
        flush()
        return runs
    }
}
