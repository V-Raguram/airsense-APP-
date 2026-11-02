package com.airsense.companion.data

import java.io.File
import java.io.PushbackReader
import java.io.Reader
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.max

/** Streaming RFC4180 reader. Unterminated quoted rows are ignored at EOF. */
object CsvReader {
    fun read(reader: Reader, visit: (List<String>) -> Unit) {
        val input = PushbackReader(reader.buffered(), 1)
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var firstCharacter = true
        fun emit() {
            row += field.toString()
            if (row.any { it.isNotBlank() }) visit(row.toList())
            row.clear()
            field.setLength(0)
        }
        while (true) {
            val next = input.read()
            if (next == -1) break
            val c = next.toChar()
            if (firstCharacter && c == '\uFEFF') { firstCharacter = false; continue }
            firstCharacter = false
            if (quoted) {
                if (c == '"') {
                    val after = input.read()
                    if (after == '"'.code) field.append('"')
                    else { quoted = false; if (after != -1) input.unread(after) }
                } else field.append(c)
            } else when (c) {
                '"' -> if (field.isEmpty()) quoted = true else field.append(c)
                ',' -> { row += field.toString(); field.setLength(0) }
                '\n' -> emit()
                '\r' -> { val after = input.read(); if (after != '\n'.code && after != -1) input.unread(after); emit() }
                else -> field.append(c)
            }
            require(field.length <= 1_048_576 && row.size <= 256) { "CSV field or column count is too large." }
        }
        if (!quoted && (row.isNotEmpty() || field.isNotEmpty())) emit()
    }
}

object CsvAnalysis {
    /** Two passes keep memory bounded even for a full 100 MB daily log. */
    fun analyse(file: File, sensor: Sensor, from: LocalDateTime? = null, until: LocalDateTime? = null): Analysis {
        require(from == null || until == null || from <= until) { "The start must be before the end." }
        var count = 0L
        var missing = 0L
        var malformed = 0L
        var min: Double? = null
        var max: Double? = null
        var mean = 0.0
        var first: LocalDateTime? = null
        var last: LocalDateTime? = null
        var header: List<String> = emptyList()
        val preview = mutableListOf<List<String>>()

        fun scan(visit: (LocalDateTime, Double?, Long) -> Unit, collect: Boolean) {
            var columns: List<String>? = null
            var sensorIndex = -1
            var dateIndex = -1
            var timeIndex = -1
            var index = 0L
            file.reader(Charsets.UTF_8).use { reader ->
                CsvReader.read(reader) { row ->
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    if (columns == null) {
                        columns = row.map { it.trim() }
                        sensorIndex = columns!!.indexOf(sensor.column)
                        // Accept the older display's names as well as current logs.
                        if (sensorIndex < 0 && sensor == Sensor.SO2) sensorIndex = columns!!.indexOf("SOx (ppm)")
                        if (sensorIndex < 0 && sensor == Sensor.NOISE) sensorIndex = columns!!.indexOf("Noise (lvl)")
                        dateIndex = columns!!.indexOf("Date")
                        timeIndex = columns!!.indexOf("Time")
                        require(dateIndex >= 0 && timeIndex >= 0) { "This CSV needs Date and Time columns from Airsense." }
                        require(sensorIndex >= 0) { "This log does not contain ${sensor.title}." }
                        if (collect) header = row
                    } else {
                        index++
                        if (row.size != columns!!.size) { if (collect) malformed++; return@read }
                        val time = runCatching { LocalDateTime.parse("${row[dateIndex].trim()} ${row[timeIndex].trim()}", logDateTime) }.getOrNull()
                        if (time == null) { if (collect) malformed++; return@read }
                        if ((from != null && time < from) || (until != null && time > until)) return@read
                        val value = row[sensorIndex].trim().toDoubleOrNull()?.takeIf { it.isFinite() }
                        if (collect && preview.size < 8) preview += row
                        visit(time, value, index)
                    }
                }
            }
        }
        scan({ time, value, _ ->
            if (first == null || time < first) first = time
            if (last == null || time > last) last = time
            if (value == null) missing++ else {
                count++
                min = min?.coerceAtMost(value) ?: value
                max = max?.coerceAtLeast(value) ?: value
                mean += (value - mean) / count
            }
        }, true)

        // At most 2048 time buckets, keeping extrema and an explicit gap marker.
        // A bucket containing missing data is rendered as a gap, never a safe zero.
        class Bucket {
            var bucketFirst: PlotPoint? = null
            var bucketLast: PlotPoint? = null
            var bucketMin: PlotPoint? = null
            var bucketMax: PlotPoint? = null
            var gap: PlotPoint? = null
            fun add(point: PlotPoint) {
                if (bucketFirst == null) bucketFirst = point
                bucketLast = point
                if (point.value == null) gap = point else {
                    if (bucketMin?.value == null || point.value < bucketMin!!.value!!) bucketMin = point
                    if (bucketMax?.value == null || point.value > bucketMax!!.value!!) bucketMax = point
                }
            }
            fun points(): List<PlotPoint> = if (gap != null) listOf(gap!!) else listOfNotNull(bucketFirst, bucketMin, bucketMax, bucketLast).distinctBy { it.index }.sortedBy { it.time }
        }
        val buckets = Array(2048) { Bucket() }
        if (first != null && last != null) {
            val start = first!!.toEpochSecond(ZoneOffset.UTC)
            val span = max(1, last!!.toEpochSecond(ZoneOffset.UTC) - start + 1)
            scan({ time, value, index ->
                val bucket = (((time.toEpochSecond(ZoneOffset.UTC) - start) * 2048) / span).toInt().coerceIn(0, 2047)
                buckets[bucket].add(PlotPoint(time, value, index))
            }, false)
        }
        return Analysis(sensor, count, missing, min, max, if (count > 0) mean else null, first, last,
            buckets.flatMap { it.points() }, malformed, preview, header)
    }
}
