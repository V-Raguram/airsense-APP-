package com.airsense.companion

import com.airsense.companion.data.trimIncompleteCsvTail
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `completed CSV bytes and CRLF endings are preserved`() {
        val bytes = "Date,Time\r\n2026-10-29,12:00:00\r\n".toByteArray()
        val file = temporary.newFile().apply { writeBytes(bytes) }
        trimIncompleteCsvTail(file)
        assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun `only the partially appended last row is omitted`() {
        val complete = "Date,Time\n2026-10-29,12:00:00\n"
        val file = temporary.newFile().apply { writeText(complete + "2026-10-29,12:") }
        trimIncompleteCsvTail(file)
        assertEquals(complete, file.readText())
    }
    @Test fun `large unbroken tails are handled without per byte seeks`() {
        val file = temporary.newFile().apply { writeText("header\n" + "x".repeat(2_000_000)) }
        trimIncompleteCsvTail(file)
        assertEquals("header\n", file.readText())
        file.writeText("")
        trimIncompleteCsvTail(file)
        assertEquals(0, file.length())
    }
}
