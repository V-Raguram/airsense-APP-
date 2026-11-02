package com.airsense.companion.data

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Sensor(val key: String, val title: String, val unit: String, val column: String, val safe: Double, val danger: Double) {
    NOX("nox", "NOx", "ppm", "NOx (ppm)", 1.0, 2.0),
    SO2("so2", "SO₂", "ppm", "SO2 (ppm)", .25, .5),
    NH3("ammonia", "NH₃", "ppm", "NH3 (ppm)", 25.0, 50.0),
    PM25("pm25", "PM2.5", "µg/m³", "PM2.5 (ug/m3)", 60.0, 120.0),
    PM10("pm10", "PM10", "µg/m³", "PM10 (ug/m3)", 100.0, 200.0),
    NOISE("noise_level", "Noise", "dB", "Noise (dB)", 85.0, 170.0),
    LUX("lux", "Light", "lx", "Light (lux)", 250.0, 1000.0);

    fun status(value: Double?): ReadingStatus = when {
        value == null || !value.isFinite() -> ReadingStatus.OFFLINE
        this == LUX && value in 250.0..500.0 -> ReadingStatus.NORMAL
        this == LUX && (value < 125.0 || value > 1000.0) -> ReadingStatus.HIGH
        this == LUX -> ReadingStatus.CAUTION
        value >= danger -> ReadingStatus.HIGH
        value > safe -> ReadingStatus.CAUTION
        else -> ReadingStatus.NORMAL
    }
    fun display(value: Double?): String = value?.let {
        if (this == LUX || this == PM25 || this == PM10) String.format(Locale.US, "%.1f", it)
        else String.format(Locale.US, "%.2f", it)
    } ?: "—"
}

enum class ReadingStatus { NORMAL, CAUTION, HIGH, OFFLINE }

data class DeviceSettings(
    val host: String = "192.168.4.1",
    val sshPort: Int = 22,
    val username: String = "ragur",
    val password: String = "",
    val privateKey: String = "",
    val keyPassphrase: String = "",
    val logDirectory: String = "/home/ragur/airsense_logs",
    val mqttPort: Int = 1883,
    val mqttTopic: String = "esp32/sensors",
    val mqttUsername: String = "",
    val mqttPassword: String = "",
    val configured: Boolean = false,
) {
    fun validate(): String? = when {
        host.isBlank() || host.any { it.isWhitespace() || it in "/\\\u0000" } -> "Enter the Pi IP address or hostname, without a URL or port."
        sshPort !in 1..65535 || mqttPort !in 1..65535 -> "Ports must be between 1 and 65535."
        username.isBlank() -> "Enter the Pi SSH username."
        !logDirectory.startsWith('/') || logDirectory.any { it == '\u0000' || it == '\n' || it == '\r' } -> "Enter an absolute log folder, such as /home/ragur/airsense_logs."
        mqttTopic.isBlank() || mqttTopic.any { it in "#+\u0000" } -> "Enter a single MQTT topic without wildcards."
        mqttPassword.isNotEmpty() && mqttUsername.isEmpty() -> "Enter the MQTT username for this password, or clear both fields."
        else -> null
    }
}

data class LiveReading(
    val values: Map<Sensor, Double?> = emptyMap(),
    val deviceTime: String = "",
    val receivedElapsedMs: Long = 0,
)

data class RemoteLog(val name: String, val bytes: Long, val modifiedSeconds: Long)
data class LocalLog(val file: File) {
    val name: String get() = file.name
    val bytes: Long get() = file.length()
}
data class TerminalPane(val id: String, val label: String, val command: String)
data class HostChallenge(val host: String, val port: Int, val fingerprint: String, val changed: Boolean)
class HostVerificationRequired(val challenge: HostChallenge) : Exception("Confirm the Pi SSH fingerprint before connecting.")

data class PlotPoint(val time: LocalDateTime, val value: Double?, val index: Long)
data class Analysis(
    val sensor: Sensor,
    val count: Long,
    val missing: Long,
    val minimum: Double?,
    val maximum: Double?,
    val average: Double?,
    val first: LocalDateTime?,
    val last: LocalDateTime?,
    val points: List<PlotPoint>,
    val malformedRows: Long,
    val preview: List<List<String>>,
    val header: List<String>,
)

val logDateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT)
fun logNameAllowed(name: String) = Regex("airsense_log_\\d{4}-\\d{2}-\\d{2}\\.csv(?:\\.\\d+)?").matches(name)
fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
fun humanBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
