package com.airsense.companion.data

import java.io.File
import java.io.RandomAccessFile

/** Keep the original CSV bytes through its last complete line, with bounded memory. */
fun trimIncompleteCsvTail(file: File) {
    RandomAccessFile(file, "rw").use { handle ->
        val buffer = ByteArray(65536)
        var end = handle.length()
        while (end > 0) {
            val start = (end - buffer.size).coerceAtLeast(0)
            val size = (end - start).toInt()
            handle.seek(start)
            handle.readFully(buffer, 0, size)
            for (index in size - 1 downTo 0) {
                if (buffer[index] == '\n'.code.toByte()) {
                    handle.setLength(start + index + 1)
                    return
                }
            }
            end = start
        }
        handle.setLength(0)
    }
}
