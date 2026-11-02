package com.airsense.companion

import com.airsense.companion.data.*
import org.junit.Assert.*
import org.junit.Test

class TerminalAndSettingsTest {
    @Test fun `ANSI colours reset without leaking into the next run`() {
        val runs = AnsiParser.parse("\u001b[1;32mNORMAL\u001b[0m plain")
        assertEquals("NORMAL", runs[0].text)
        assertTrue(runs[0].bold)
        assertNotNull(runs[0].foreground)
        assertEquals(" plain", runs[1].text)
        assertNull(runs[1].foreground)
        assertFalse(runs[1].bold)
    }
    @Test fun `256 and RGB colours are reproduced`() {
        val indexed = AnsiParser.parse("\u001b[38;5;196mred").single()
        assertEquals(0xFFFF0000.toInt(), indexed.foreground)
        val rgb = AnsiParser.parse("\u001b[48;2;12;34;56mbackground").single()
        assertEquals(0xFF0C2238.toInt(), rgb.background)
    }
    @Test fun `terminal titles and control escapes are not displayed`() {
        val text = AnsiParser.parse("\u001b]0;private title\u0007screen\u001b[2J\nrow").joinToString("") { it.text }
        assertEquals("screen\nrow", text)
    }
    @Test fun `rotated CSVs are accepted but remote paths are rejected`() {
        assertTrue(logNameAllowed("airsense_log_2026-10-29.csv"))
        assertTrue(logNameAllowed("airsense_log_2026-10-29.csv.1793250000"))
        assertFalse(logNameAllowed("../airsense_log_2026-10-29.csv"))
        assertFalse(logNameAllowed("airsense_log_2026-10-29.csv; reboot"))
    }
    @Test fun `shell arguments are quoted as one literal word`() {
        assertEquals("'a'\\''b'", shellQuote("a'b"))
        assertEquals("'$(touch /tmp/x)'", shellQuote("$(touch /tmp/x)"))
    }
    @Test fun `connection validation rejects malformed addresses and paths`() {
        assertNull(DeviceSettings().validate())
        assertNotNull(DeviceSettings(host = "http://192.168.4.1").validate())
        assertNotNull(DeviceSettings(sshPort = 0).validate())
        assertNotNull(DeviceSettings(logDirectory = "relative/path").validate())
        assertNotNull(DeviceSettings(mqttTopic = "esp32/#").validate())
    }
    @Test fun `missing sensors cannot show normal status`() {
        Sensor.entries.forEach { assertEquals(ReadingStatus.OFFLINE, it.status(null)) }
        assertEquals(ReadingStatus.OFFLINE, Sensor.NOX.status(Double.NaN))
        assertEquals(ReadingStatus.HIGH, Sensor.NOX.status(2.0))
        assertEquals(ReadingStatus.NORMAL, Sensor.LUX.status(350.0))
        assertEquals(ReadingStatus.HIGH, Sensor.LUX.status(100.0))
    }
}
