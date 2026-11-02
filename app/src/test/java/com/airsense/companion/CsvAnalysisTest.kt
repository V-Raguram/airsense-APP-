package com.airsense.companion

import com.airsense.companion.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.StringReader
import java.time.LocalDateTime

class CsvAnalysisTest {
    @get:Rule val temporary = TemporaryFolder()
    private val header = "Date,Time,NOx (ppm),Light (lux),Noise (dB),PM2.5 (ug/m3),PM10 (ug/m3),SO2 (ppm),NH3 (ppm)"
    private fun fixture(text: String) = temporary.newFile().apply { writeText(text) }

    @Test fun `current dashboard logs preserve missing values and exact statistics`() {
        val file = fixture("$header\r\n2026-10-29,10:00:00,1.2,400,65,20,40,0.1,8\r\n" +
            "2026-10-29,10:00:01,Nil,400,65,20,40,0.1,8\r\n" +
            "2026-10-29,10:00:02,2.4,400,65,20,40,0.1,8\r\n")
        val result = CsvAnalysis.analyse(file, Sensor.NOX)
        assertEquals(2, result.count)
        assertEquals(1, result.missing)
        assertEquals(1.2, result.minimum!!, .00001)
        assertEquals(2.4, result.maximum!!, .00001)
        assertEquals(1.8, result.average!!, .00001)
        assertTrue(result.points.any { it.value == null })
        assertEquals(3, result.preview.size)
    }
    @Test fun `quoted fields BOM escaped quotes and line endings parse correctly`() {
        val rows = mutableListOf<List<String>>()
        CsvReader.read(StringReader("\uFEFFA,B,C\r\n\"a,b\",\"say \"\"hi\"\"\",\"two\nlines\"\r\n")) { rows += it }
        assertEquals(listOf("A", "B", "C"), rows[0])
        assertEquals(listOf("a,b", "say \"hi\"", "two\nlines"), rows[1])
    }
    @Test fun `unterminated quoted append is not included`() {
        val rows = mutableListOf<List<String>>()
        CsvReader.read(StringReader("A,B\n1,2\n3,\"unfinished")) { rows += it }
        assertEquals(2, rows.size)
    }
    @Test fun `invalid dates and partial records are skipped not invented`() {
        val file = fixture("$header\n2026-02-30,10:00:00,1,2,3,4,5,6,7\n2026-10-29,10:00:00,2,2,3,4,5,6,7\n2026-10-29,10:00:01,3")
        val result = CsvAnalysis.analyse(file, Sensor.NOX)
        assertEquals(1, result.count)
        assertEquals(2, result.malformedRows)
    }
    @Test fun `non finite and absent measurements become gaps`() {
        val file = fixture("$header\n2026-10-29,10:00:00,NaN,400,65,20,40,0.1,8\n2026-10-29,10:00:01,Infinity,400,65,20,40,0.1,8\n")
        val result = CsvAnalysis.analyse(file, Sensor.NOX)
        assertEquals(0, result.count)
        assertEquals(2, result.missing)
        assertNull(result.average)
    }
    @Test fun `time filtering includes both endpoints and keeps true statistics`() {
        val file = fixture("$header\n" + (0..9).joinToString("\n") { "2026-10-29,10:00:0$it,$it,400,65,20,40,0.1,8" })
        val result = CsvAnalysis.analyse(file, Sensor.NOX, LocalDateTime.parse("2026-10-29T10:00:03"), LocalDateTime.parse("2026-10-29T10:00:05"))
        assertEquals(3, result.count)
        assertEquals(4.0, result.average!!, .00001)
    }
    @Test fun `old CSV sensor labels remain readable`() {
        val file = fixture("Date,Time,NOx (ppm),Light (lux),Noise (lvl),PM2.5 (ug/m3),PM10 (ug/m3),SOx (ppm)\n2026-10-29,10:00:00,1,2,3,4,5,6\n")
        assertEquals(6.0, CsvAnalysis.analyse(file, Sensor.SO2).average!!, .00001)
        assertEquals(3.0, CsvAnalysis.analyse(file, Sensor.NOISE).average!!, .00001)
    }
    @Test fun `large logs use bounded graph memory and keep spikes`() {
        val file = temporary.newFile()
        file.bufferedWriter().use { output ->
            output.appendLine(header)
            repeat(25000) { index ->
                val time = LocalDateTime.of(2026, 10, 29, 0, 0).plusSeconds(index.toLong()).format(logDateTime).split(' ')
                output.appendLine("${time[0]},${time[1]},${if (index == 12345) 999 else 1},400,65,20,40,0.1,8")
            }
        }
        val result = CsvAnalysis.analyse(file, Sensor.NOX)
        assertEquals(25000, result.count)
        assertTrue(result.points.size <= 8192)
        assertTrue(result.points.any { it.value == 999.0 })
        assertEquals(999.0, result.maximum!!, .00001)
    }
    @Test fun `out of order timestamps produce chronological graph points`() {
        val file = fixture("$header\n2026-10-29,10:00:02,2,2,3,4,5,6,7\n2026-10-29,10:00:00,1,2,3,4,5,6,7\n")
        val result = CsvAnalysis.analyse(file, Sensor.NOX)
        assertEquals(LocalDateTime.parse("2026-10-29T10:00:00"), result.first)
        assertTrue(result.points.zipWithNext().all { (a, b) -> a.time <= b.time })
    }
}
