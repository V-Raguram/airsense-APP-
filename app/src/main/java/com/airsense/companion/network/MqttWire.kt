package com.airsense.companion.network

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Small MQTT 3.1.1 subscriber codec. No Android services or Pi agent are needed. */
object MqttWire {
    const val MAX_PACKET_BYTES = 1_048_576
    data class Packet(val header: Int, val body: ByteArray) { val type: Int get() = header ushr 4 }
    fun utf8(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65535 && '\u0000' !in value) { "Invalid MQTT string." }
        return ByteArrayOutputStream().also { stream -> DataOutputStream(stream).apply { writeShort(bytes.size); write(bytes) } }.toByteArray()
    }
    fun send(output: OutputStream, header: Int, body: ByteArray = byteArrayOf()) {
        require(body.size <= MAX_PACKET_BYTES)
        output.write(header)
        var length = body.size
        do {
            var next = length % 128
            length /= 128
            if (length > 0) next = next or 128
            output.write(next)
        } while (length > 0)
        output.write(body)
        output.flush()
    }
    fun read(input: InputStream): Packet {
        val header = input.read()
        if (header < 0) throw EOFException("MQTT disconnected.")
        var length = 0
        var multiplier = 1
        var finished = false
        for (n in 0..3) {
            val next = input.read()
            if (next < 0) throw EOFException("Incomplete MQTT packet.")
            length += (next and 127) * multiplier
            if (next and 128 == 0) { finished = true; break }
            multiplier *= 128
        }
        require(finished && length <= MAX_PACKET_BYTES) { "MQTT packet is too large or malformed." }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) throw EOFException("Incomplete MQTT payload.")
            offset += read
        }
        return Packet(header, body)
    }
    fun connect(clientId: String, username: String, password: String): ByteArray {
        var flags = 2 // clean session
        if (username.isNotEmpty()) flags = flags or 128
        if (password.isNotEmpty()) { require(username.isNotEmpty()) { "MQTT password requires a username." }; flags = flags or 64 }
        return utf8("MQTT") + byteArrayOf(4, flags.toByte(), 0, 20) + utf8(clientId) +
            (if (username.isNotEmpty()) utf8(username) else byteArrayOf()) +
            (if (password.isNotEmpty()) utf8(password) else byteArrayOf())
    }
    data class Publish(val topic: String, val payload: ByteArray, val retained: Boolean, val packetId: Int?)
    fun publish(packet: Packet): Publish {
        require(packet.type == 3 && packet.body.size >= 2)
        val body = packet.body
        val length = ((body[0].toInt() and 255) shl 8) or (body[1].toInt() and 255)
        val qos = (packet.header ushr 1) and 3
        require(qos <= 1) { "Unsupported incoming MQTT QoS." }
        val offset = 2 + length + if (qos > 0) 2 else 0
        require(length > 0 && offset <= body.size) { "Malformed MQTT publish." }
        val id = if (qos > 0) ((body[offset - 2].toInt() and 255) shl 8) or (body[offset - 1].toInt() and 255) else null
        return Publish(String(body, 2, length, Charsets.UTF_8), body.copyOfRange(offset, body.size), packet.header and 1 != 0, id)
    }
}
